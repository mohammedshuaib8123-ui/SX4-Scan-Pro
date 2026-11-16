package com.sx4scanpro

/** One live reading as the rules see it: a real number with the time it was read. Missing or stale readings are simply not passed in. */
internal data class RuleReading(val name: String, val value: Double, val unit: String, val atMs: Long)

internal enum class OperatingState(val text: String) {
    UNKNOWN("unknown (no fresh RPM)"),
    ENGINE_OFF("engine off (RPM below the unvalidated app band)"),
    CRANKING("cranking or just catching (RPM below running speed; unvalidated app band)"),
    RUNNING("engine running (RPM above the unvalidated app band)")
}

internal enum class HealthLevel(val text: String) {
    NORMAL("NORMAL"), ATTENTION("ATTENTION"), INVESTIGATE("INVESTIGATE"),
    /** A fact worth recording where no defensible limit exists, so it is reported without being called good or bad. */
    OBSERVATION("OBSERVATION"),
    /** The rule could not be evaluated; it says why instead of guessing. */
    INSUFFICIENT("INSUFFICIENT EVIDENCE")
}

internal data class HealthFinding(
    val ruleId: String,
    val title: String,
    val level: HealthLevel,
    val state: OperatingState,
    val summary: String,
    val evidence: List<String>,
    /** Where the criterion comes from. Never "OEM" unless a Suzuki figure is actually cited. */
    val provenance: String,
    val possibilities: List<String>,
    val missing: String,
    val nextCheck: String
) {
    fun lines(): List<String> = buildList {
        add("${level.text}: $title")
        add("  $summary")
        add("  Operating state: ${state.text}")
        evidence.forEach { add("  Evidence: $it") }
        add("  Basis: $provenance")
        possibilities.forEach { add("  Possible (not confirmed): $it") }
        if (missing.isNotBlank()) add("  Not available: $missing")
        if (nextCheck.isNotBlank()) add("  Safe next check: $nextCheck")
    }
}

/**
 * Deterministic, offline health rules over a snapshot of fresh live readings (Section 26.6).
 * No Suzuki/OEM limits are used or implied: each rule states its basis (a physical fact, the decoder's own range, or a generic
 * 12 V system convention) and where that is too weak to call anything abnormal the result is an OBSERVATION. There is no score.
 * A reading older than [MAX_AGE_MS] is treated as missing, never as a number.
 */
internal object HealthRules {
    const val RULESET_VERSION = "1"
    const val MAX_AGE_MS = 10_000L
    const val RPM = "Engine RPM"
    const val COOLANT = "Coolant temperature"
    const val IAT = "Intake air temperature"
    const val MAP = "Manifold pressure"
    const val BARO = "Barometric pressure"
    const val VOLTAGE = "Control module voltage"
    const val RAIL = "Fuel rail pressure"

    /** PIDs the rules read from the poller: (PID, rule name, unit). Only those the ECU advertised ever have a reading. */
    val PIDS = listOf(Triple(0x0C, RPM, "rpm"), Triple(0x05, COOLANT, "°C"), Triple(0x0F, IAT, "°C"), Triple(0x0B, MAP, "kPa"),
        Triple(0x33, BARO, "kPa"), Triple(0x42, VOLTAGE, "V"), Triple(0x23, RAIL, "kPa"))

    /** UNVALIDATED app heuristics: real cranking and idle RPM on this SX4 are not yet measured, and none of these bands, tolerances or ranges is a Suzuki specification. */
    private const val OFF_RPM = 50.0
    private const val RUNNING_RPM = 400.0
    /** With the engine stopped the manifold is at ambient, so MAP and BARO should agree; 15 kPa is an app tolerance, not a sensor spec. */
    private const val MAP_BARO_TOLERANCE_KPA = 15.0
    const val HEURISTIC = "UNVALIDATED app heuristic, not a Suzuki specification"

    private fun fresh(r: List<RuleReading>, name: String, nowMs: Long): RuleReading? =
        r.firstOrNull { it.name == name && it.value.isFinite() && nowMs - it.atMs in 0..MAX_AGE_MS }

    fun stateOf(readings: List<RuleReading>, nowMs: Long): OperatingState {
        val rpm = fresh(readings, RPM, nowMs)?.value ?: return OperatingState.UNKNOWN
        return when {
            rpm < OFF_RPM -> OperatingState.ENGINE_OFF
            rpm < RUNNING_RPM -> OperatingState.CRANKING
            else -> OperatingState.RUNNING
        }
    }

    private fun fmt(r: RuleReading, nowMs: Long) =
        String.format(java.util.Locale.US, "%s %.1f %s (%.0f s old)", r.name, r.value, r.unit, (nowMs - r.atMs) / 1000.0)

    fun evaluate(readings: List<RuleReading>, nowMs: Long): List<HealthFinding> {
        val st = stateOf(readings, nowMs)
        return buildList {
            mapBaro(readings, nowMs, st)?.let { add(it) }
            sensorRange(readings, nowMs, st).forEach { add(it) }
            charging(readings, nowMs, st)?.let { add(it) }
            warmUp(readings, nowMs, st)?.let { add(it) }
            rail(readings, nowMs, st)?.let { add(it) }
        }
    }

    private fun mapBaro(r: List<RuleReading>, now: Long, st: OperatingState): HealthFinding? {
        val map = fresh(r, MAP, now); val baro = fresh(r, BARO, now)
        val id = "MAP_BARO"; val title = "Manifold pressure against barometric pressure"
        if (map == null || baro == null) return HealthFinding(id, title, HealthLevel.INSUFFICIENT, st,
            "Not evaluated: " + listOfNotNull(if (map == null) "manifold pressure" else null, if (baro == null) "barometric pressure" else null).joinToString(" and ") + " has no fresh reading.",
            emptyList(), "Needs both readings, fresh and read in the same moment.", emptyList(), "A fresh MAP and BARO pair.", "Keep the ignition on with the link connected and open the report again.")
        if (baro.value !in 60.0..110.0) return HealthFinding(id, title, HealthLevel.INVESTIGATE, st,
            "Barometric pressure reads ${"%.0f".format(java.util.Locale.US, baro.value)} kPa, outside what the atmosphere can give at road altitudes. The reading is implausible; that alone does not prove a faulty sensor.",
            listOf(fmt(baro, now)), "Physical fact: ambient pressure at road altitudes lies roughly between 60 and 110 kPa.",
            listOf("BARO sensor fault or a decoding or reply problem"), "The ECU's own BARO plausibility is not exposed.", "Read the value again and compare it with a weather-station pressure for your altitude.")
        if (st != OperatingState.ENGINE_OFF) return HealthFinding(id, title, HealthLevel.OBSERVATION, st,
            "Gauge boost is MAP minus BARO = ${"%.0f".format(java.util.Locale.US, map.value - baro.value)} kPa. The agreement check only applies with the engine stopped.",
            listOf(fmt(map, now), fmt(baro, now)), "MAP is absolute pressure; boost is MAP minus BARO. No Suzuki boost limit is used.",
            emptyList(), "A target boost value is not exposed by this ECU's standard PIDs.", "With the engine off and ignition on, open the report again to run the agreement check.")
        val d = map.value - baro.value
        return if (kotlin.math.abs(d) <= MAP_BARO_TOLERANCE_KPA) HealthFinding(id, title, HealthLevel.NORMAL, st,
            "With the engine stopped MAP and BARO agree within ${"%.0f".format(java.util.Locale.US, MAP_BARO_TOLERANCE_KPA)} kPa (difference ${"%.0f".format(java.util.Locale.US, d)} kPa).",
            listOf(fmt(map, now), fmt(baro, now)), "Physical fact: a stopped engine's manifold sits at ambient pressure. The 15 kPa tolerance is an $HEURISTIC; not validated on this car.",
            emptyList(), "", "")
        else HealthFinding(id, title, HealthLevel.ATTENTION, st,
            "With the engine stopped MAP differs from BARO by ${"%.0f".format(java.util.Locale.US, d)} kPa.",
            listOf(fmt(map, now), fmt(baro, now)), "Physical fact: a stopped engine's manifold sits at ambient pressure. The 15 kPa tolerance is an $HEURISTIC; not validated on this car.",
            listOf("MAP or BARO sensor offset", "wiring or connector fault", "engine not fully stopped (still spinning down)"), "Only one moment was read; no repeat.", "Wait a few seconds with the engine off, then read again; if it persists have the MAP sensor circuit checked.")
    }

    /** A value at the decoder's own limit is only an observation: it can come from an open or shorted circuit, but also from a missing value or a decoding problem, and nothing here corroborates a cause. */
    private fun sensorRange(r: List<RuleReading>, now: Long, st: OperatingState): List<HealthFinding> = buildList {
        listOf(COOLANT, IAT).forEach { n ->
            val x = fresh(r, n, now) ?: return@forEach
            if (x.value <= -40.0 || x.value >= 215.0) add(HealthFinding("RANGE_" + n.replace(' ', '_').uppercase(), "$n at the end of its decodable range", HealthLevel.OBSERVATION, st,
                "$n reads ${"%.0f".format(java.util.Locale.US, x.value)} ${x.unit}, the limit of what the standard decoder can express. This alone is not proof of a failed sensor.",
                listOf(fmt(x, now)), "Decoder fact: the standard formula A-40 spans -40 to 215 degrees C. No second source corroborates a cause.",
                listOf("an open or shorted sensor circuit", "a missing or invalid value from the ECU", "a decoding or link problem"), "A repeat reading, the other temperature readings, or a related fault code.", "Read again; compare with the other temperature readings and check the sensor connector only if the value persists."))
        }
    }

    private fun charging(r: List<RuleReading>, now: Long, st: OperatingState): HealthFinding? {
        val v = fresh(r, VOLTAGE, now) ?: return null
        val id = "SUPPLY_VOLTAGE"; val title = "Supply voltage seen by the ECU"
        val basis = "$HEURISTIC: generic 12 V lead-acid convention (about 12.4-12.8 V resting, about 13.2-14.8 V while charging). Not a Suzuki figure and not validated on this car; the ECU voltage can differ slightly from the battery terminals."
        val ev = listOf(fmt(v, now))
        val value = "%.2f".format(java.util.Locale.US, v.value)
        return when (st) {
            OperatingState.RUNNING -> when {
                v.value in 13.2..14.8 -> HealthFinding(id, title, HealthLevel.NORMAL, st, "$value V with the engine running is within the generic charging range.", ev, basis, emptyList(), "", "")
                v.value < 13.2 -> HealthFinding(id, title, HealthLevel.ATTENTION, st, "$value V with the engine running is below the generic charging range.", ev, basis,
                    listOf("alternator or belt problem", "weak or heavily loaded battery", "high electrical load at this moment"), "Electrical load and battery state are not known.", "Measure at the battery with a multimeter and repeat after the engine has run a while with loads off.")
                else -> HealthFinding(id, title, HealthLevel.ATTENTION, st, "$value V with the engine running is above the generic charging range.", ev, basis,
                    listOf("regulator fault", "a measurement offset at the ECU"), "Battery terminal voltage is not known.", "Measure at the battery terminals with a multimeter.")
            }
            OperatingState.ENGINE_OFF -> if (v.value < 11.8) HealthFinding(id, title, HealthLevel.ATTENTION, st, "$value V with the engine off is low.", ev, basis,
                listOf("discharged or weak battery"), "No cranking voltage was captured.", "Charge or test the battery; watch the voltage during cranking.")
            else HealthFinding(id, title, HealthLevel.OBSERVATION, st, "$value V with the engine off; resting and charging ranges differ, so no judgement is made.", ev, basis, emptyList(), "", "")
            else -> HealthFinding(id, title, HealthLevel.OBSERVATION, st, "$value V while the engine state is ${st.text}; no judgement is made.", ev, basis, emptyList(), "Cranking behaviour needs a recorded start.", "")
        }
    }

    private fun warmUp(r: List<RuleReading>, now: Long, st: OperatingState): HealthFinding? {
        val c = fresh(r, COOLANT, now) ?: return null
        if (c.value <= -40.0 || c.value >= 215.0) return null
        if (st != OperatingState.RUNNING) return null
        return HealthFinding("WARM_UP", "Coolant temperature while running", HealthLevel.OBSERVATION, st,
            "Coolant is ${"%.0f".format(java.util.Locale.US, c.value)} ${c.unit}. One reading is not a warm-up trend, and no Suzuki thermostat figure is used.",
            listOf(fmt(c, now)), "Observation only: warm-up behaviour needs a time series.", emptyList(),
            "Time to reach operating temperature (needs a recorded cold start).", "Record a cold start to idle and compare the rise over time in the exported session.")
    }

    private fun rail(r: List<RuleReading>, now: Long, st: OperatingState): HealthFinding? {
        val p = fresh(r, RAIL, now) ?: return null
        return HealthFinding("RAIL_ACTUAL", "Common-rail pressure (actual only)", HealthLevel.INSUFFICIENT, st,
            "Actual rail pressure is ${"%.1f".format(java.util.Locale.US, p.value / 1000.0)} MPa, but there is no target value to compare it with.",
            listOf(fmt(p, now)), "Actual-versus-target is only evaluated when both are exposed and verified; this ECU's standard PIDs give the actual value only. No Suzuki limit is used.",
            emptyList(), "Target (desired) rail pressure.", "Use a Suzuki-capable tool to read desired rail pressure if a fuel-system fault is suspected.")
    }

    /** Report lines. States plainly what was and was not evaluated. */
    fun reportLines(readings: List<RuleReading>, nowMs: Long): List<String> = buildList {
        val st = stateOf(readings, nowMs)
        add("  Rule set version $RULESET_VERSION. App-generated from fresh live readings only (age up to ${MAX_AGE_MS / 1000} s); not ECU facts, no health score, no OEM limits.")
        add("  The RPM bands (50 and 400), the 15 kPa MAP/BARO tolerance and the voltage ranges are unvalidated app heuristics, not Suzuki specifications. A result is an observation about these readings, not a confirmed fault.")
        add("  Operating state: ${st.text}")
        val f = evaluate(readings, nowMs)
        if (f.isEmpty()) add("  No rule had enough fresh evidence to run.")
        f.forEach { fi -> fi.lines().forEach { add("  $it") }; add("") }
        if (f.isNotEmpty()) removeAt(lastIndex)
    }
}
