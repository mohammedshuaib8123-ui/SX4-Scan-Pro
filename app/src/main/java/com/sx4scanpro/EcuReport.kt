package com.sx4scanpro

/** Wording for the ECU Information screen and the PDF, kept free of Android so it can be tested. */
internal object EcuReport {
    fun statusText(s: ScanStatus): String = when (s) {
        ScanStatus.READ -> "Read from ECU"
        ScanStatus.NO_DATA -> "ECU answered NO DATA"
        ScanStatus.REJECTED -> "Request rejected"
        ScanStatus.MALFORMED -> "Reply could not be read"
        ScanStatus.NOT_ADVERTISED -> "Not offered by this ECU"
        ScanStatus.NOT_ATTEMPTED -> "Not requested"
        ScanStatus.ADAPTER_ERROR -> "Adapter or bus error"
    }

    fun linkText(state: LinkState, connected: Boolean): String = when {
        !connected -> "Not connected"
        state == LinkState.COMMUNICATING -> "ECU responding"
        state == LinkState.ECU_SILENT -> "ECU not responding"
        else -> "Adapter not responding"
    }

    fun diffText(d: ProfileDiff): List<String> = when {
        d.unchanged -> listOf("No difference from the previous scan.")
        else -> d.entries.map {
            when (it.kind) {
                DiffKind.CHANGED -> "${it.field} changed: ${it.before} -> ${it.after}"
                DiffKind.NOT_COMPARABLE -> "${it.field} not comparable (${EcuReport.statusName(it.before)} -> ${EcuReport.statusName(it.after)})"
                DiffKind.PIDS_ADDED -> "${it.field} added: ${it.after}"
                DiffKind.PIDS_REMOVED -> "${it.field} no longer listed: ${it.before}"
            }
        }
    }

    /**
     * One-paragraph outcome of a scan: how many identity items were really read, and for every one that was not, the
     * precise reason. "Scan finished" alone would hide a scan in which the ECU answered nothing.
     */
    fun scanSummary(p: EcuProfile): String {
        val items = listOf("VIN" to p.vin, "calibration IDs" to p.calIds, "CVNs" to p.cvns, "ECU name" to p.ecuName)
        val read = items.filter { it.second.status == ScanStatus.READ && it.second.items.isNotEmpty() }
        val missing = items.filter { it !in read }
        val head = "Scan finished: ${read.size} of ${items.size} identity items read from the ECU."
        if (missing.isEmpty()) return head
        return head + " Not read: " + missing.joinToString("; ") { (name, f) ->
            name + ": " + statusText(f.status) + (if (f.note.isNotBlank()) " (${f.note})" else "")
        } + "."
    }

    /** True when the profile was captured before this app process started, so it must not be presented as a fresh read. */
    fun isFromEarlierSession(profile: EcuProfile, processStartMs: Long): Boolean = profile.capturedAtMs < processStartMs

    private fun statusName(n: String) = runCatching { statusText(ScanStatus.valueOf(n)) }.getOrDefault(n)

    private fun field(title: String, f: ProfileField): List<String> = buildList {
        add(title + ": " + (if (f.status == ScanStatus.READ && f.items.isNotEmpty()) f.items.joinToString(", ") else statusText(f.status)))
        if (f.note.isNotBlank()) add("    note: " + f.note)
        if (f.rawHex.isNotBlank()) add("    raw: " + f.rawHex)
    }

    /** Adapter, protocol, every identity field and the Mode 01 list, each with its status. Shared by the ECU PDF and the full diagnosis report. */
    fun identityLines(p: EcuProfile): List<String> = buildList {
        add("Adapter: ${p.adapterName.ifBlank { "unknown" }}   ${p.adapterId}   ${p.adapterVoltage}")
        add("Protocol: ${p.protocol.ifBlank { "not reported by adapter" }}   Framing: ${p.framing.name}")
        addAll(field("VIN", p.vin)); addAll(field("Calibration IDs", p.calIds)); addAll(field("CVNs", p.cvns)); addAll(field("ECU name", p.ecuName))
        addAll(field("Mode 09 items offered", p.mode09Supported))
        add("Mode 01 PIDs advertised (${p.mode01Supported.size}): " + p.mode01Supported.joinToString(" ") { "%02X".format(it) })
        p.notes.forEach { add("Note: $it") }
    }

    /** Plain-text lines used for the PDF. Every value comes from the profile; absent values say why. */
    fun lines(p: EcuProfile, diff: ProfileDiff?): List<String> {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(p.capturedAtMs))
        return buildList {
            add("SX4 Scan Pro - ECU information")
            add("Scanned: $ts   App: ${p.appVersion}")
            add("")
            addAll(identityLines(p))
            if (diff != null) { add(""); add("Compared with previous scan:"); diffText(diff).forEach { add("  $it") } }
            add("")
            add("Read-only scan. Values are exactly what the ECU reported; missing items were not offered or not answered.")
        }
    }
}
