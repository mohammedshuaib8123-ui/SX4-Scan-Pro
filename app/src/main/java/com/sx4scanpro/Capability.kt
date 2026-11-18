package com.sx4scanpro

/** One emissions self-test monitor from Mode 01 PID 01. */
internal data class MonitorState(val name: String, val complete: Boolean)

/** Decoded Mode 01 PID 01 (SAE J1979 monitor status since DTCs were cleared). Only monitors the ECU says it supports are listed. */
internal data class ReadinessStatus(val milOn: Boolean, val storedDtcCount: Int, val compressionIgnition: Boolean, val monitors: List<MonitorState>, val raw: String) {
    val incomplete: List<String> get() = monitors.filter { !it.complete }.map { it.name }
}

internal object ReadinessDecoder {
    // Bit meanings follow SAE J1979 / ISO 15031-5 for PID 01 [S: standard, from recollection of the table, not yet confirmed against the ECU's own replies].
    private val spark = listOf(0 to "Catalyst", 1 to "Heated catalyst", 2 to "Evaporative system", 3 to "Secondary air", 5 to "Oxygen sensor", 6 to "Oxygen sensor heater", 7 to "EGR / VVT")
    private val diesel = listOf(0 to "NMHC catalyst", 1 to "NOx / SCR aftertreatment", 3 to "Boost pressure", 5 to "Exhaust gas sensor", 6 to "Particulate filter", 7 to "EGR / VVT")
    private val continuous = listOf(0 to "Misfire", 1 to "Fuel system", 2 to "Comprehensive components")

    /** `41 01 A B C D`, headers off. Null when the reply is not a well-formed positive answer. */
    fun decode(raw: String): ReadinessStatus? {
        val n = raw.uppercase().filterNot { it.isWhitespace() }
        val i = n.indexOf("4101")
        if (i < 0) return null
        val d = n.substring(i + 4).take(8)
        if (d.length != 8 || d.any { it !in "0123456789ABCDEF" }) return null
        val a = d.substring(0, 2).toInt(16); val b = d.substring(2, 4).toInt(16); val c = d.substring(4, 6).toInt(16); val e = d.substring(6, 8).toInt(16)
        val ci = (b shr 3) and 1 == 1
        val out = ArrayList<MonitorState>()
        for ((bit, name) in continuous) if ((b shr bit) and 1 == 1) out += MonitorState(name, (b shr (bit + 4)) and 1 == 0)
        for ((bit, name) in if (ci) diesel else spark) if ((c shr bit) and 1 == 1) out += MonitorState(name, (e shr bit) and 1 == 0)
        return ReadinessStatus((a shr 7) and 1 == 1, a and 0x7F, ci, out, n.substring(i, i + 12))
    }
}

internal enum class Mode06Outcome { SUPPORTED, NOT_SUPPORTED, NO_DATA, UNREADABLE }

/** Result of asking Mode 06 which test identifiers it holds (06 00). Says nothing about the test values themselves. */
internal data class Mode06Probe(val outcome: Mode06Outcome, val ids: Set<Int>, val raw: String) {
    companion object {
        fun classify(raw: String): Mode06Probe {
            val n = raw.uppercase().filterNot { it.isWhitespace() }
            val i = n.indexOf("4600")
            return when {
                i >= 0 && n.substring(i + 4).take(8).let { it.length == 8 && it.all { c -> c in "0123456789ABCDEF" } } ->
                    Mode06Probe(Mode06Outcome.SUPPORTED, ObdPidSupport.pidsOf(0, n.substring(i + 4, i + 12).toLong(16)), raw)
                n.contains("7F06") -> Mode06Probe(Mode06Outcome.NOT_SUPPORTED, emptySet(), raw)
                n.contains("NODATA") || n.isEmpty() -> Mode06Probe(Mode06Outcome.NO_DATA, emptySet(), raw)
                else -> Mode06Probe(Mode06Outcome.UNREADABLE, emptySet(), raw)
            }
        }
    }
}

internal data class CapabilitySnapshot(val capturedAtMs: Long, val readiness: ReadinessStatus?, val readinessRaw: String, val mode06: Mode06Probe)

/** The coverage list for one session: what this ECU reports it can do, built only from replies. Pure text, shared by the report. */
internal object CapabilityInventory {
    fun lines(p: EcuProfile, snap: CapabilitySnapshot?): List<String> = buildList {
        add("Capability inventory (what the ECU reported in this session):")
        add("  Mode 01 live data: ${p.mode01Supported.size} PIDs advertised")
        add("  Mode 09 vehicle info: " + (if (p.mode09Supported.status == ScanStatus.READ) p.mode09Supported.items.size.toString() + " items offered" else EcuReport.statusText(p.mode09Supported.status)))
        if (snap == null) { add("  Mode 06 test results and readiness: not read in this session"); return@buildList }
        add("  Mode 06 test results: " + when (snap.mode06.outcome) {
            Mode06Outcome.SUPPORTED -> "offered (${snap.mode06.ids.size} test-id bits set in 06 00)"
            Mode06Outcome.NOT_SUPPORTED -> "not supported by the ECU"
            Mode06Outcome.NO_DATA -> "no answer (NO DATA)"
            Mode06Outcome.UNREADABLE -> "reply could not be read"
        })
        val r = snap.readiness
        if (r == null) add("  Readiness (PID 01): not read or reply unreadable (raw: ${snap.readinessRaw.ifBlank { "none" }})")
        else {
            add("  Warning lamp: " + (if (r.milOn) "ON" else "off") + "   Stored codes counted by the ECU: ${r.storedDtcCount}   Engine type flag: " + (if (r.compressionIgnition) "compression ignition (diesel)" else "spark ignition"))
            if (r.monitors.isEmpty()) add("  Monitors: none reported as supported")
            else r.monitors.forEach { add("    " + it.name + ": " + (if (it.complete) "complete" else "not complete")) }
        }
    }
}
