package com.sx4scanpro

/** Plain-text health report built only from what the app has really read. Missing data is said to be missing. */
internal object HealthReport {
    data class Input(
        val whenText: String,
        val connected: Boolean,
        val adapter: String,
        val readings: List<Triple<String, String, String>>,
        val faultCodes: List<Pair<String, String>>?,
        val tripKm: Double?,
        val trackedKm: Double,
        val maintenance: List<Pair<String, String>>
    )

    fun lines(i: Input): List<String> = buildList {
        add("SX4 SCAN PRO - VEHICLE HEALTH REPORT")
        add("Suzuki SX4 1.3 DDiS")
        add("Generated: ${i.whenText}")
        add("OBD link: " + if (i.connected) "connected (${i.adapter})" else "not connected at report time")
        add("")
        add("LIVE READINGS")
        if (i.readings.isEmpty()) add("  none available")
        i.readings.forEach { (n, v, u) -> add("  $n: $v $u".trimEnd()) }
        add("")
        add("FAULT CODES (engine ECU, read-only scan)")
        val codes = i.faultCodes
        if (codes == null) add("  not scanned in this session")
        else if (codes.isEmpty()) add("  none returned by the last scan")
        else codes.forEach { (code, cat) ->
            val x = DtcExplainer.explain(code)
            add("  $code ($cat)" + (x?.let { " - " + it.system } ?: ""))
        }
        add("")
        add("DISTANCE")
        add("  This trip: " + (i.tripKm?.let { String.format(java.util.Locale.US, "%.1f km", it) } ?: "no data"))
        add("  Tracked by this app in total: " + String.format(java.util.Locale.US, "%.1f km", i.trackedKm))
        add("")
        add("MAINTENANCE REMINDERS")
        if (i.maintenance.isEmpty()) add("  none set")
        i.maintenance.forEach { (n, d) -> add("  $n: $d") }
        add("")
        add("Informational only. Not a full vehicle scan and not a substitute for a workshop inspection.")
    }
}
