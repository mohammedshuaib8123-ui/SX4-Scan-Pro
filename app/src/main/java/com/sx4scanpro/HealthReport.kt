package com.sx4scanpro

/**
 * Plain-text health report built only from what the app has really read. Missing data is said to be missing.
 *
 * With the diagnostic evidence supplied ([Input.dtcScan], [Input.freezeFrame], [Input.ecuProfile]) it becomes the full
 * diagnosis report: every reported code with its explanation, the freeze frame, the ECU identity, what was and was not
 * scanned, and what the app cannot conclude. ECU-reported facts are kept apart from the app's interpretation.
 */
internal object HealthReport {
    data class Input(
        val whenText: String,
        val connected: Boolean,
        val adapter: String,
        val readings: List<Triple<String, String, String>>,
        val faultCodes: List<Pair<String, String>>?,
        val tripKm: Double?,
        val trackedKm: Double,
        val maintenance: List<Pair<String, String>>,
        /** Full per-service scan. When present it replaces [faultCodes]. */
        val dtcScan: DtcScanReport? = null,
        val freezeFrame: FreezeFrameResult? = null,
        val ecuProfile: EcuProfile? = null,
        /** Live readings that had no fresh value at report time (name only). */
        val unavailableReadings: List<String> = emptyList(),
        /** Fresh numeric readings for the deterministic rules (see [HealthRules]); empty means no rule can run. */
        val ruleReadings: List<RuleReading> = emptyList(),
        val ruleNowMs: Long = 0L,
        /** Owner-recorded real-car validation lines (see [ValidationPlan]); empty omits the section. */
        val validation: List<String> = emptyList()
    )

    private fun time(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    private fun rawOneLine(raw: String) = raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")

    fun lines(i: Input): List<String> = buildList {
        val full = i.dtcScan != null || i.freezeFrame != null || i.ecuProfile != null
        add(if (full) "SX4 SCAN PRO - FULL DIAGNOSIS REPORT" else "SX4 SCAN PRO - VEHICLE HEALTH REPORT")
        add("Suzuki SX4 1.3 DDiS")
        add("Generated: ${i.whenText}")
        add("OBD link: " + if (i.connected) "connected (${i.adapter})" else "not connected at report time")
        add("")
        add("LIVE READINGS")
        if (i.readings.isEmpty()) add("  none available")
        i.readings.forEach { (n, v, u) -> add("  $n: $v $u".trimEnd()) }
        if (i.unavailableReadings.isNotEmpty()) add("  No fresh value at report time: " + i.unavailableReadings.joinToString(", "))
        if (full) add("  Values are those shown at report time; a reading without a fresh value is not listed as a number.")
        add("")
        add("FAULT CODES (engine ECU, read-only scan)")
        val scan = i.dtcScan
        if (scan != null) addAll(dtcSection(scan, i.freezeFrame, i.readings))
        else {
            val codes = i.faultCodes
            if (codes == null) add("  not scanned in this session")
            else if (codes.isEmpty()) add("  no codes in the list passed to this report; completeness of the scan was not recorded")
            else codes.forEach { (code, cat) ->
                val x = DtcExplainer.explain(code)
                add("  $code ($cat)" + (x?.let { " - " + it.system } ?: ""))
            }
        }
        if (full) {
            add("")
            add("FREEZE FRAME (Mode 02)")
            addAll(freezeSection(i.freezeFrame))
            add("")
            add("ECU INFORMATION (Mode 09)")
            val p = i.ecuProfile
            if (p == null) add("  not read in this session")
            else { add("  Read: ${time(p.capturedAtMs)}"); EcuReport.identityLines(p).forEach { add("  $it") } }
            add("")
            add("FINDINGS (ECU-reported evidence only)")
            addAll(findings(i))
            add("")
            add("LIVE-DATA HEALTH RULES")
            addAll(HealthRules.reportLines(i.ruleReadings, i.ruleNowMs))
            add("")
            add("LIMITATIONS")
            addAll(limitations(i))
            if (i.validation.isNotEmpty()) { add(""); add("REAL-CAR VALIDATION"); addAll(i.validation) }
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

    private fun dtcSection(scan: DtcScanReport, freeze: FreezeFrameResult?, readings: List<Triple<String, String, String>>): List<String> = buildList {
        add("  Scanned: ${time(scan.capturedAtMs)}" + if (scan.protocol.isNotBlank()) "   Protocol: ${scan.protocol}" else "")
        add("  Scope: ${DtcScanReport.SCOPE}")
        add("  Result: ${scan.headline()}")
        add("  Services:")
        scan.modes.forEach { add("    " + DtcScanText.modeLine(it)) }
        add("  Codes reported by the ECU:")
        if (scan.codes.isEmpty()) add("    none")
        scan.codes.forEach { c ->
            DtcPresentation.of(c.code, c.category).lines().forEach { add("    $it") }
            if (freeze?.dtc == c.code) add("      A freeze frame names this code (see FREEZE FRAME).")
            val live = relatedReadings(c.code, readings)
            if (live.isNotEmpty()) add("      Related live readings at report time: " + live.joinToString("; ") { (n, v, u) -> "$n $v $u".trim() })
        }
        add("  Raw replies:")
        scan.modes.forEach { add("    ${it.mode}: " + (rawOneLine(it.raw).ifBlank { "(none received)" })) }
    }

    private fun freezeSection(f: FreezeFrameResult?): List<String> = buildList {
        if (f == null) { add("  not read in this session"); return@buildList }
        add("  Read: ${time(f.capturedAtMs)}   Frame: ${f.frameNumber}")
        add("  Result: ${f.headline()}")
        add("  " + FreezeFrameText.dtcLine(f))
        add("  Support list (PID 00): " + FreezeFrameText.status(f.supportStatus) + (f.supportedPids?.let { " - " + it.sorted().joinToString(" ") { p -> "%02X".format(p) } } ?: ""))
        if (f.items.isEmpty()) add("  No individual values were requested.")
        f.items.forEach { it2 ->
            add("  " + FreezeFrameText.itemLine(it2))
            if (it2.raw.isNotBlank()) add("      raw: " + rawOneLine(it2.raw))
        }
        f.notes.forEach { add("  Note: $it") }
    }

    /** Live readings whose name matches the sensor a catalogued code is about. A fixed keyword table, no scoring. */
    fun relatedReadings(code: String, readings: List<Triple<String, String, String>>): List<Triple<String, String, String>> {
        val title = DtcCatalogue.lookup(code)?.title?.lowercase() ?: return emptyList()
        val keys = RELATED.filter { (word, _) -> word in title }.flatMap { it.second }.distinct()
        if (keys.isEmpty()) return emptyList()
        return readings.filter { (n, _, _) -> keys.any { k -> k in n.lowercase() } }
    }

    private val RELATED = listOf(
        "coolant" to listOf("coolant"),
        "intake air temperature" to listOf("intake air"),
        "mass air flow" to listOf("maf", "air flow"),
        "manifold" to listOf("manifold"),
        "boost" to listOf("boost", "manifold"),
        "fuel rail" to listOf("rail"),
        "vehicle speed" to listOf("speed"),
        "throttle" to listOf("throttle", "pedal"),
        "system voltage" to listOf("voltage"),
        "recirculation" to listOf("egr")
    )

    private fun findings(i: Input): List<String> = buildList {
        val scan = i.dtcScan
        if (scan == null) { add("  No fault-code scan is part of this report, so there are no findings about stored faults."); return@buildList }
        if (scan.codes.isEmpty()) {
            add(if (scan.engineEcuReportsNoCodes) "  - The engine ECU reported no stored, pending or permanent codes on this scan."
            else "  - No codes were returned, but the scan was incomplete, so this does not show that the ECU holds none.")
            return@buildList
        }
        scan.codes.forEach { c ->
            val info = DtcCatalogue.lookup(c.code)
            add("  - ${c.code} was reported by the engine ECU as ${c.category} (service ${scan.modes.firstOrNull { it.category == c.category }?.mode ?: "?"})" +
                (info?.let { ": ${it.title}." } ?: ". No description is available in this app."))
            val ff = i.freezeFrame
            if (ff != null && ff.dtc == c.code && ff.dataItems.isNotEmpty())
                add("    Freeze-frame evidence for ${c.code}: " + ff.dataItems.joinToString("; ") { x -> FreezeFrameText.itemLine(x) })
        }
        add("  The ECU category is not proof that a fault is present at this instant; no code is marked active.")
    }

    private fun limitations(i: Input): List<String> = buildList {
        add("  - Only the engine ECU was queried, through generic OBD-II services. BCM, ABS, airbag, immobilizer and other modules were not queried; this report cannot show that the whole vehicle is free of faults.")
        val scan = i.dtcScan
        if (scan == null) add("  - Fault codes were not scanned.")
        else scan.modes.filter { it.status != DtcModeStatus.CODES_REPORTED && it.status != DtcModeStatus.VALID_EMPTY }
            .forEach { add("  - ${it.label}: ${DtcScanText.status(it.status)}" + (if (it.detail.isNotBlank()) " (${it.detail})" else "") + ".") }
        val f = i.freezeFrame
        if (f == null) add("  - Freeze frame was not read.")
        else if (!f.hasData) add("  - Freeze frame: no values were obtained (${FreezeFrameText.status(f.supportStatus)}).")
        else f.items.filter { !it.status.hasData }.forEach { add("  - Freeze frame ${it.name}: ${FreezeFrameText.status(it.status)}.") }
        if (i.ecuProfile == null) add("  - ECU information was not read.")
        else listOf("VIN" to i.ecuProfile.vin, "Calibration IDs" to i.ecuProfile.calIds, "CVNs" to i.ecuProfile.cvns, "ECU name" to i.ecuProfile.ecuName)
            .filter { it.second.status != ScanStatus.READ }.forEach { add("  - ECU information ${it.first}: ${EcuReport.statusText(it.second.status)}.") }
        if (i.unavailableReadings.isNotEmpty()) add("  - Live readings without a fresh value: " + i.unavailableReadings.joinToString(", ") + ".")
        add("  - Descriptions, causes and checks are generic possibilities from a local catalogue, not confirmed findings for this car. No severity is assigned.")
    }
}
