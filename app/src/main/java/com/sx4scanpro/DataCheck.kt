package com.sx4scanpro

/**
 * Data check: tallies live sensor values and warns when something looks wrong. Pure Kotlin, no Android, fully unit tested.
 *
 * Three kinds of rule, none of which invents an OEM limit:
 *  1. Impossible or electrical values (sensor reads open/short, rail pressure collapsed, charging voltage out of the normal range).
 *  2. Cross-checks between sensors (manifold pressure vs barometric pressure with the engine stopped, air flow vs what engine speed and
 *     manifold pressure imply, boost at high load).
 *  3. "Normal for this car": reference values learned from the car itself (warm idle rail pressure and air flow, loaded rail pressure per
 *     speed/load bin, air-flow efficiency). A reference is locked once it has enough samples and is only learned while no warning is active,
 *     so a developing fault is not absorbed into "normal". The owner can reset it after a repair.
 *
 * Calibrated on the 2026-11-15 real-car capture (SX4 ZDi): warm idle rail 28-29 MPa, steady air-flow efficiency 0.48-0.88, steady
 * high-load boost 109-130 kPa. Generic limits are marked as such in the message text.
 */
internal enum class CheckLevel { INFO, WARN, ALERT }

internal data class DataFinding(val id: String, val level: CheckLevel, val title: String, val detail: String, val sinceMs: Long)

internal data class TallyRow(val label: String, val unit: String, val seconds: Int, val min: Double, val avg: Double, val max: Double, val last: Double)

internal data class BaselineRow(val label: String, val unit: String, val have: Int, val need: Int, val reference: Double?)

internal data class TripSummary(val runSeconds: Int, val movingSeconds: Int, val idleSeconds: Int, val highRpmSeconds: Int, val heavyLoadSeconds: Int, val maxCoolantC: Double?) {
    fun pct(part: Int): Int = if (runSeconds <= 0) 0 else (100.0 * part / runSeconds).toInt()
}

/** Locked reference samples per key. A key collects up to [REF_SIZE] samples, then stays fixed until [reset]. */
internal class Baselines(initial: Map<String, List<Double>> = emptyMap()) {
    companion object {
        const val REF_SIZE = 120
        const val MIN_READY = 40
        fun fromJson(text: String?): Baselines {
            val m = (text?.let { MiniJson.parse(it) } as? Map<*, *>) ?: return Baselines()
            val out = mutableMapOf<String, List<Double>>()
            for ((k, v) in m) if (k is String && v is List<*>) out[k] = v.mapNotNull { (it as? Number)?.toDouble() }.filter { !it.isNaN() }.take(REF_SIZE)
            return Baselines(out)
        }
    }

    private val refs = initial.mapValues { it.value.toMutableList() }.toMutableMap()
    @Volatile var dirty = false; private set

    fun count(key: String) = refs[key]?.size ?: 0
    fun isReady(key: String, need: Int = MIN_READY) = count(key) >= need
    fun median(key: String): Double? = refs[key]?.takeIf { it.isNotEmpty() }?.sorted()?.let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    fun learn(key: String, v: Double, size: Int = REF_SIZE) { val l = refs.getOrPut(key) { mutableListOf() }; if (l.size < size) { l += v; dirty = true } }
    fun reset() { refs.clear(); dirty = true }
    fun toJson(): String { dirty = false; return MiniJson.write(refs.toSortedMap(), -1) }
}

internal class DataCheckMonitor(private val baselines: Baselines = Baselines()) {
    companion object {
        const val RPM = 0x0C; const val SPEED = 0x0D; const val COOLANT = 0x05; const val LOAD = 0x04; const val MAP = 0x0B
        const val IAT = 0x0F; const val MAF = 0x10; const val RAIL = 0x23; const val VOLT = 0x42; const val BARO = 0x33
        const val CLEAR_MS = 15_000L
        const val LEARN_GAP_MS = 3_000L
        private const val DISPLACEMENT_L = 1.248            // SX4 ZDi (1.3 DDiS), used only for the air-flow cross-check
        val TALLIED = listOf(
            Triple(RPM, "Engine speed", "rpm"), Triple(SPEED, "Vehicle speed", "km/h"), Triple(COOLANT, "Coolant", "°C"), Triple(LOAD, "Engine load", "%"),
            Triple(MAP, "Manifold pressure", "kPa"), Triple(MAF, "Air flow (MAF)", "g/s"), Triple(IAT, "Intake air", "°C"),
            Triple(RAIL, "Fuel rail pressure", "MPa"), Triple(VOLT, "Module voltage", "V"), Triple(BARO, "Barometric", "kPa")
        )
        private const val IDLE_RAIL = "idle_rail_kpa"
        private const val IDLE_MAF = "idle_maf_gps"
        private const val VE = "air_flow_efficiency"
        private fun railBin(bin: String) = "rail_kpa_$bin"
    }

    // ---- state ----
    private class Acc { var n = 0; var sum = 0.0; var min = Double.MAX_VALUE; var max = -Double.MAX_VALUE; var last = 0.0
        fun add(v: Double) { n++; sum += v; if (v < min) min = v; if (v > max) max = v; last = v } }
    private val acc = HashMap<Int, Acc>()
    private val since = HashMap<String, Long>()
    private val clearSince = HashMap<String, Long>()
    private val active = LinkedHashMap<String, DataFinding>()
    private val rpmHist = ArrayDeque<Pair<Long, Double>>()      // 90 s
    private val loadHist = ArrayDeque<Pair<Long, Double>>()     // 6 s
    private val lastLearn = HashMap<String, Long>()
    private val veRecent = ArrayDeque<Double>()
    private val lastChange = HashMap<Int, Pair<Double, Long>>()
    private var lastNow = -1L
    private var runningSince: Long? = null
    private var stoppedSince: Long? = null
    private var runSec = 0; private var movingSec = 0; private var idleSec = 0; private var highRpmSec = 0; private var heavySec = 0
    private var movingMs = 0L
    private var maxCoolant: Double? = null

    fun findings(): List<DataFinding> = active.values.sortedWith(compareByDescending<DataFinding> { it.level }.thenBy { it.sinceMs })
    fun summary() = TripSummary(runSec, movingSec, idleSec, highRpmSec, heavySec, maxCoolant)

    fun tally(): List<TallyRow> = TALLIED.mapNotNull { (pid, label, unit) ->
        val a = acc[pid]?.takeIf { it.n > 0 } ?: return@mapNotNull null
        val d = if (pid == RAIL) 1000.0 else 1.0
        TallyRow(label, unit, a.n, a.min / d, a.sum / a.n / d, a.max / d, a.last / d)
    }

    fun baselineRows(): List<BaselineRow> = listOf(
        BaselineRow("Warm idle rail pressure", "MPa", baselines.count(IDLE_RAIL), Baselines.MIN_READY, baselines.median(IDLE_RAIL)?.div(1000.0)),
        BaselineRow("Warm idle air flow", "g/s", baselines.count(IDLE_MAF), Baselines.MIN_READY, baselines.median(IDLE_MAF)),
        BaselineRow("Air-flow efficiency (steady)", "", baselines.count(VE), Baselines.MIN_READY, baselines.median(VE))
    )

    fun resetLearning() { baselines.reset(); lastLearn.clear() }
    fun baselinesJsonIfDirty(): String? = if (baselines.dirty) baselines.toJson() else null

    /** A new trip: counters restart, learned references and nothing else are kept. */
    fun startTrip() {
        acc.clear(); since.clear(); clearSince.clear(); active.clear(); rpmHist.clear(); loadHist.clear(); veRecent.clear(); lastChange.clear()
        lastNow = -1L; runningSince = null; stoppedSince = null
        runSec = 0; movingSec = 0; idleSec = 0; highRpmSec = 0; heavySec = 0; movingMs = 0L; maxCoolant = null
    }

    // ---- rule plumbing ----
    private fun rule(id: String, level: CheckLevel, cond: Boolean, holdMs: Long, now: Long, title: String, detail: () -> String) {
        if (cond) {
            clearSince.remove(id)
            val s = since.getOrPut(id) { now }
            if (now - s >= holdMs) {
                val old = active[id]
                active[id] = DataFinding(id, level, title, detail(), old?.sinceMs ?: now)
            }
        } else {
            since.remove(id)
            if (id in active) { val c = clearSince.getOrPut(id) { now }; if (now - c >= CLEAR_MS) { active.remove(id); clearSince.remove(id) } }
        }
    }

    private fun fmt(v: Double, d: Int = 1) = "%.${d}f".format(java.util.Locale.US, v)

    /**
     * Call about once a second with the values that are still fresh (a PID that is not fresh must be left out, never replaced by an old value).
     * [nowMs] must not go backwards.
     */
    fun update(nowMs: Long, v: Map<Int, Double>) {
        val dt = if (lastNow < 0) 0L else (nowMs - lastNow).coerceIn(0L, 5_000L)
        lastNow = nowMs
        for ((pid, x) in v) {
            acc.getOrPut(pid) { Acc() }.add(x)
            val c = lastChange[pid]; if (c == null || c.first != x) lastChange[pid] = x to nowMs
        }
        val rpm = v[RPM]; val speed = v[SPEED]; val load = v[LOAD]; val coolant = v[COOLANT]
        val map = v[MAP]; val maf = v[MAF]; val iat = v[IAT]; val baro = v[BARO]; val rail = v[RAIL]; val volt = v[VOLT]

        if (rpm != null) { rpmHist.addLast(nowMs to rpm); while (rpmHist.isNotEmpty() && nowMs - rpmHist.first().first > 90_000L) rpmHist.removeFirst() }
        if (load != null) { loadHist.addLast(nowMs to load); while (loadHist.isNotEmpty() && nowMs - loadHist.first().first > 6_000L) loadHist.removeFirst() }
        if (coolant != null) maxCoolant = maxOf(maxCoolant ?: coolant, coolant)

        if (rpm != null) {
            if (rpm >= 500) { if (runningSince == null) runningSince = nowMs; stoppedSince = null }
            else if (rpm < 300) { runningSince = null; if (stoppedSince == null) stoppedSince = nowMs }
        }
        val running = runningSince?.let { nowMs - it >= 20_000L } == true
        val runningShort = runningSince?.let { nowMs - it >= 10_000L } == true
        val engineOff = stoppedSince?.let { nowMs - it >= 10_000L } == true
        if (rpm != null && rpm >= 500) {
            runSec += (dt / 1000).toInt().coerceAtLeast(if (dt > 0) 1 else 0)
            if (rpm > 3000) highRpmSec++
            if (load != null && load > 80) heavySec++
            if (speed != null && speed > 3) { movingSec++; movingMs += dt } else if (speed != null) idleSec++
        }
        val recent4 = rpmHist.filter { nowMs - it.first <= 4_000L }.map { it.second }
        val load6 = loadHist.map { it.second }
        val steady = recent4.size >= 2 && recent4.max() - recent4.min() <= 250 && (load6.size < 2 || load6.max() - load6.min() <= 20)
        val idle = running && rpm != null && rpm in 550.0..1000.0 && speed != null && speed < 1 && load != null && load < 40 && coolant != null && coolant >= 75 && steady
        val learningAllowed = active.isEmpty()
        // A sample is never learned while a warning is active, nor when it already disagrees with a ready reference (so the seconds before a
        // warning appears cannot leak a fault into "normal"), nor when it is not physically plausible.
        fun learn(key: String, value: Double, size: Int = Baselines.REF_SIZE, tolerance: Double = Double.MAX_VALUE) {
            if (!learningAllowed || baselines.count(key) >= size) return
            val ref = baselines.median(key)
            if (ref != null && baselines.isReady(key) && Math.abs(value - ref) > tolerance) return
            if (nowMs - (lastLearn[key] ?: Long.MIN_VALUE / 2) < LEARN_GAP_MS) return
            lastLearn[key] = nowMs; baselines.learn(key, value, size)
        }

        // 1. sensors that read outside what the hardware can output
        rule("coolant-sensor", CheckLevel.ALERT, coolant != null && (coolant <= -39 || coolant >= 140), 5_000, nowMs,
            "Coolant temperature reading looks wrong") { "Coolant reads ${fmt(coolant ?: 0.0, 0)} °C. Either the sensor/wiring is faulty (open or shorted) or the engine is severely overheating; check the real temperature gauge before driving on." }
        rule("iat-sensor", CheckLevel.WARN, iat != null && (iat <= -39 || iat >= 120), 10_000, nowMs,
            "Intake air temperature reading looks wrong") { "Intake air reads ${fmt(iat ?: 0.0, 0)} °C, which is outside what is possible; the sensor or its wiring may be faulty." }

        // 2. electrical
        rule("volt-low", CheckLevel.WARN, running && volt != null && volt < 12.8 && volt >= 12.0, 30_000, nowMs,
            "Charging voltage is low") { "Module voltage ${fmt(volt ?: 0.0)} V with the engine running; a healthy charging system is normally about 13.5-14.8 V (general automotive guideline, not an SX4 specification). Check alternator belt, alternator and battery." }
        rule("volt-critical", CheckLevel.ALERT, running && volt != null && volt < 12.0, 10_000, nowMs,
            "Battery is not being charged") { "Module voltage ${fmt(volt ?: 0.0)} V with the engine running." }
        rule("volt-high", CheckLevel.ALERT, running && volt != null && volt > 15.2, 10_000, nowMs,
            "Charging voltage is too high") { "Module voltage ${fmt(volt ?: 0.0)} V with the engine running; overcharging can damage the battery and electronics." }

        // 3. fuel rail pressure
        rule("rail-collapse", CheckLevel.ALERT, runningShort && rail != null && rail < 15_000, 5_000, nowMs,
            "Fuel rail pressure has collapsed") { "Rail pressure ${fmt((rail ?: 0.0) / 1000)} MPa with the engine running; a diesel cannot run normally this low. Suspect the high-pressure pump, a pressure regulator/sensor fault or a fuel supply problem." }
        rule("rail-high", CheckLevel.ALERT, rail != null && rail > 170_000, 3_000, nowMs,
            "Fuel rail pressure is implausibly high") { "Rail pressure ${fmt((rail ?: 0.0) / 1000)} MPa is above what a passenger-car common rail reaches (general limit); suspect the rail pressure sensor or regulator." }
        val idleRef = baselines.median(IDLE_RAIL)
        if (idle && rail != null && rail in 15_000.0..170_000.0) learn(IDLE_RAIL, rail, tolerance = maxOf(0.20 * (idleRef ?: 0.0), 5_000.0))
        rule("rail-idle", CheckLevel.WARN, idle && rail != null && idleRef != null && baselines.isReady(IDLE_RAIL) && Math.abs(rail - idleRef) > maxOf(0.20 * idleRef, 5_000.0), 15_000, nowMs,
            "Idle rail pressure is out of your car's normal range") { "Warm idle rail pressure is ${fmt((rail ?: 0.0) / 1000)} MPa; your car normally idles at ${fmt((idleRef ?: 0.0) / 1000)} MPa. Possible causes: rail pressure sensor, regulator, pump wear, injector leak-back, fuel filter." }
        val binKey = if (steady && running && rpm != null && load != null && load >= 40 && speed != null) railBin("${(rpm / 500).toInt() * 500}_${(load / 25).toInt() * 25}") else null
        if (binKey != null && rail != null && rail in 15_000.0..170_000.0) learn(binKey, rail, 60, tolerance = 0.40 * (baselines.median(binKey) ?: 0.0))
        val binRef = binKey?.let { if (baselines.isReady(it)) baselines.median(it) else null }
        rule("rail-load", CheckLevel.WARN, binKey != null && rail != null && binRef != null && Math.abs(rail - binRef) > 0.40 * binRef, 12_000, nowMs,
            "Rail pressure under load is out of your car's normal range") { "Under steady load the rail reads ${fmt((rail ?: 0.0) / 1000)} MPa; at this engine speed and load your car normally shows ${fmt((binRef ?: 0.0) / 1000)} MPa." }

        // 4. air path
        val mapBaroBad = engineOff && map != null && baro != null && Math.abs(map - baro) > 15
        rule("map-baro", CheckLevel.WARN, mapBaroBad, 10_000, nowMs,
            "Manifold pressure sensor disagrees with barometric pressure") { "With the engine stopped manifold pressure reads ${fmt(map ?: 0.0, 0)} kPa but barometric pressure is ${fmt(baro ?: 0.0, 0)} kPa; they should match closely. Suspect the MAP sensor or its wiring." }
        val mafRef = baselines.median(IDLE_MAF)
        if (idle && maf != null) learn(IDLE_MAF, maf, tolerance = maxOf(0.25 * (mafRef ?: 0.0), 0.8))
        rule("maf-idle", CheckLevel.WARN, idle && maf != null && mafRef != null && baselines.isReady(IDLE_MAF) && Math.abs(maf - mafRef) > maxOf(0.25 * mafRef, 0.8), 15_000, nowMs,
            "Idle air flow is out of your car's normal range") { "Warm idle air flow is ${fmt(maf ?: 0.0, 2)} g/s; your car normally shows ${fmt(mafRef ?: 0.0, 2)} g/s. Check the air filter, MAF sensor, intake hoses and EGR valve." }
        rule("boost-low", CheckLevel.WARN, steady && running && load != null && load >= 80 && rpm != null && rpm >= 2000 && map != null && baro != null && map - baro < 40, 8_000, nowMs,
            "Very little boost at high load") { "At high load the manifold pressure is only ${fmt((map ?: 0.0) - (baro ?: 0.0), 0)} kPa above atmospheric; this car normally makes about 100+ kPa. Check intercooler hoses and clamps, turbo and the MAP sensor (general check, from your own drive)." }
        var veMedian: Double? = null
        if (steady && running && rpm != null && rpm >= 1000 && map != null && iat != null && maf != null && speed != null) {
            val density = 1.184 * (map / 101.3) * (293.15 / (iat + 273.15))                // g per litre of intake air
            val theoretical = rpm / 120.0 * DISPLACEMENT_L * density                          // g/s for a four-stroke
            if (theoretical > 0) { veRecent.addLast(maf / theoretical); while (veRecent.size > 12) veRecent.removeFirst() }
            if (veRecent.size >= 8) veMedian = veRecent.sorted().let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
            veMedian?.let { if (it in 0.40..1.15) learn(VE, it, tolerance = 0.25 * (baselines.median(VE) ?: 0.0)) }
        } else if (!steady) veRecent.clear()
        val veRef = baselines.median(VE)
        val veAbsolute = veMedian != null && (veMedian < 0.40 || veMedian > 1.15)
        val veDrift = veMedian != null && veRef != null && baselines.isReady(VE) && Math.abs(veMedian - veRef) > 0.25 * veRef
        rule("maf-ve", CheckLevel.WARN, veAbsolute || veDrift, 0, nowMs, "Air flow does not match engine speed and boost") {
            "Measured air flow is ${fmt((veMedian ?: 0.0) * 100, 0)}% of what engine speed and manifold pressure imply" +
                (if (veRef != null && !veAbsolute) " (your car usually shows ${fmt(veRef * 100, 0)}%)" else " (outside the possible range)") +
                ". Suspect the MAF or MAP sensor, a dirty air filter, an intake leak or a clogged EGR/intercooler path." }

        // 5. frozen sensors: no change at all for 90 s while engine speed moved a lot
        val moved = rpmHist.size >= 30 && rpmHist.first().let { nowMs - it.first >= 85_000L } && (rpmHist.maxOf { it.second } - rpmHist.minOf { it.second }) >= 600
        for ((pid, label) in listOf(MAP to "Manifold pressure", MAF to "Air flow (MAF)", RAIL to "Fuel rail pressure", LOAD to "Engine load")) {
            val c = lastChange[pid]
            rule("stuck-$pid", CheckLevel.WARN, running && moved && v.containsKey(pid) && c != null && nowMs - c.second >= 90_000L, 0, nowMs,
                "$label sensor looks stuck") { "$label has shown exactly the same value for over 90 s while engine speed changed a lot; the sensor or its signal may be frozen." }
        }

        // 6. slow warm-up: fuel use goes up when the thermostat never closes
        rule("thermostat", CheckLevel.WARN, movingMs >= 10 * 60_000L && (maxCoolant ?: 99.0) < 70.0, 0, nowMs,
            "Engine is not warming up") { "After ${movingMs / 60_000} min of driving the coolant has not passed ${fmt(maxCoolant ?: 0.0, 0)} °C. A thermostat stuck open makes the engine run cold and use more fuel." }
    }
}

/** A tank-to-tank fuel economy log kept by the owner; the ECU does not report fuel level or fuel rate (PIDs 2F and 5E are not supported). */
internal data class FillUp(val odoKm: Double, val litres: Double, val atMs: Long, val full: Boolean = true)
internal data class FuelSegment(val fromOdo: Double, val toOdo: Double, val litres: Double) {
    val km get() = toOdo - fromOdo
    val kmPerL get() = km / litres
    val l100 get() = 100.0 * litres / km
}

internal object FuelLog {
    const val MIN_KM = 100.0
    const val MIN_LITRES = 5.0

    fun parse(text: String?): List<FillUp> = text.orEmpty().split(';').mapNotNull { r ->
        val p = r.split(',')
        if (p.size < 3) return@mapNotNull null
        val odo = p[0].toDoubleOrNull() ?: return@mapNotNull null
        val l = p[1].toDoubleOrNull() ?: return@mapNotNull null
        val at = p[2].toLongOrNull() ?: return@mapNotNull null
        FillUp(odo, l, at, p.getOrNull(3) != "0")
    }.sortedBy { it.odoKm }

    fun serialize(l: List<FillUp>): String = l.joinToString(";") { "${it.odoKm},${it.litres},${it.atMs},${if (it.full) 1 else 0}" }

    /** Null when the entry is acceptable. */
    fun validate(existing: List<FillUp>, odoKm: Double?, litres: Double?): String? = when {
        odoKm == null || litres == null -> "Enter the odometer reading and the litres added."
        odoKm <= 0 || odoKm > 2_000_000 -> "Odometer reading is not plausible."
        litres < 1 || litres > 120 -> "Litres added must be between 1 and 120."
        existing.isNotEmpty() && odoKm <= existing.maxOf { it.odoKm } -> "Odometer must be higher than the last fill-up (${existing.maxOf { it.odoKm }.toInt()} km)."
        else -> null
    }

    /** Full-tank to full-tank segments; litres of partial fills in between are added to the next full one. The first full fill only starts the count. */
    fun segments(fills: List<FillUp>): List<FuelSegment> {
        val out = mutableListOf<FuelSegment>()
        var lastFull: Double? = null; var litres = 0.0
        for (f in fills.sortedBy { it.odoKm }) {
            if (lastFull == null) { if (f.full) { lastFull = f.odoKm; litres = 0.0 }; continue }
            litres += f.litres
            if (f.full) { out += FuelSegment(lastFull, f.odoKm, litres); lastFull = f.odoKm; litres = 0.0 }
        }
        return out.filter { it.km >= MIN_KM && it.litres >= MIN_LITRES }
    }

    /** Compares the newest tank with the median of the earlier ones; needs at least two earlier tanks. */
    fun trend(segs: List<FuelSegment>, nowMs: Long): DataFinding? {
        if (segs.size < 3) return null
        val last = segs.last(); val prior = segs.dropLast(1).map { it.kmPerL }.sorted()
        val usual = if (prior.size % 2 == 1) prior[prior.size / 2] else (prior[prior.size / 2 - 1] + prior[prior.size / 2]) / 2
        val drop = 1.0 - last.kmPerL / usual
        if (drop < 0.15) return null
        val level = if (drop >= 0.30) CheckLevel.ALERT else CheckLevel.WARN
        return DataFinding("fuel-economy", level, "Fuel economy has dropped",
            "Last tank: ${"%.1f".format(java.util.Locale.US, last.kmPerL)} km/L (${"%.1f".format(java.util.Locale.US, last.l100)} L/100 km) against your usual ${"%.1f".format(java.util.Locale.US, usual)} km/L, " +
                "${(drop * 100).toInt()}% worse. Check tyre pressure, wheel alignment, brakes dragging, air filter, thermostat, injectors and driving pattern.", nowMs)
    }
}
