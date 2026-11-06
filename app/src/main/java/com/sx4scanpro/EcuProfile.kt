package com.sx4scanpro

internal data class ProfileField(val status: ScanStatus, val items: List<String> = emptyList(), val rawHex: String = "", val note: String = "") {
    companion object { val NOT_ATTEMPTED = ProfileField(ScanStatus.NOT_ATTEMPTED) }
}

/**
 * What the engine ECU actually reported on one scan. Fields the ECU did not provide stay empty with a status saying why;
 * nothing is filled in from assumptions.
 */
internal data class EcuProfile(
    val capturedAtMs: Long,
    val appVersion: String,
    val adapterName: String,
    val adapterId: String,
    val adapterVoltage: String,
    val protocol: String,
    val framing: Framing,
    val mode01Supported: List<Int>,
    val mode09Supported: ProfileField,
    val vin: ProfileField,
    val calIds: ProfileField,
    val cvns: ProfileField,
    val ecuName: ProfileField,
    val notes: List<String> = emptyList()
) {
    private fun f(x: ProfileField) = mapOf("status" to x.status.name, "items" to x.items, "rawHex" to x.rawHex, "note" to x.note)

    fun toJson(): String = MiniJson.write(linkedMapOf(
        "schema" to 1L, "capturedAtMs" to capturedAtMs, "appVersion" to appVersion,
        "adapter" to linkedMapOf("name" to adapterName, "id" to adapterId, "voltage" to adapterVoltage),
        "protocol" to protocol, "framing" to framing.name,
        "mode01Supported" to mode01Supported.map { "%02X".format(it) },
        "mode09Supported" to f(mode09Supported), "vin" to f(vin), "calibrationIds" to f(calIds), "cvns" to f(cvns), "ecuName" to f(ecuName),
        "notes" to notes
    ))

    /** Flat rows for spreadsheets: field, status, value, raw hex. */
    fun toCsv(): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = mutableListOf("field,status,value,raw")
        fun add(n: String, x: ProfileField) = rows.add(listOf(n, x.status.name, x.items.joinToString(" | "), x.rawHex).joinToString(",") { q(it) })
        rows.add(listOf("captured", "", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(capturedAtMs)), "").joinToString(",") { q(it) })
        rows.add(listOf("adapter", "", "$adapterName / $adapterId / $adapterVoltage", "").joinToString(",") { q(it) })
        rows.add(listOf("protocol", "", protocol, framing.name).joinToString(",") { q(it) })
        rows.add(listOf("mode01_supported", "", mode01Supported.joinToString(" ") { "%02X".format(it) }, "").joinToString(",") { q(it) })
        add("mode09_supported", mode09Supported); add("vin", vin); add("calibration_ids", calIds); add("cvns", cvns); add("ecu_name", ecuName)
        return rows.joinToString("\n") + "\n"
    }

    companion object {
        private fun field(m: Any?): ProfileField? {
            val o = m as? Map<*, *> ?: return null
            val st = runCatching { ScanStatus.valueOf(o["status"] as String) }.getOrNull() ?: return null
            return ProfileField(st, (o["items"] as? List<*>)?.map { it.toString() } ?: emptyList(), o["rawHex"] as? String ?: "", o["note"] as? String ?: "")
        }

        /** Null if the text is not a profile this app wrote. */
        fun fromJson(text: String): EcuProfile? {
            val o = MiniJson.parse(text) as? Map<*, *> ?: return null
            if (o["schema"] != 1L) return null
            val ad = o["adapter"] as? Map<*, *> ?: return null
            return EcuProfile(
                capturedAtMs = o["capturedAtMs"] as? Long ?: return null,
                appVersion = o["appVersion"] as? String ?: "",
                adapterName = ad["name"] as? String ?: "", adapterId = ad["id"] as? String ?: "", adapterVoltage = ad["voltage"] as? String ?: "",
                protocol = o["protocol"] as? String ?: "",
                framing = runCatching { Framing.valueOf(o["framing"] as String) }.getOrDefault(Framing.UNKNOWN),
                mode01Supported = (o["mode01Supported"] as? List<*>)?.mapNotNull { it.toString().toIntOrNull(16) } ?: emptyList(),
                mode09Supported = field(o["mode09Supported"]) ?: return null,
                vin = field(o["vin"]) ?: return null, calIds = field(o["calibrationIds"]) ?: return null,
                cvns = field(o["cvns"]) ?: return null, ecuName = field(o["ecuName"]) ?: return null,
                notes = (o["notes"] as? List<*>)?.map { it.toString() } ?: emptyList()
            )
        }
    }
}

internal enum class DiffKind { CHANGED, NOT_COMPARABLE, PIDS_ADDED, PIDS_REMOVED }
internal data class DiffEntry(val field: String, val kind: DiffKind, val before: String, val after: String)

internal data class ProfileDiff(val entries: List<DiffEntry>) {
    /** True only when a field that identifies the ECU software was read on both scans and differs. */
    val identityChanged: Boolean get() = entries.any { it.kind == DiffKind.CHANGED && it.field in IDENTITY }
    val unchanged: Boolean get() = entries.isEmpty()

    companion object {
        private val IDENTITY = setOf("VIN", "Calibration IDs", "CVNs", "ECU name")

        fun compare(old: EcuProfile, new: EcuProfile): ProfileDiff {
            val out = mutableListOf<DiffEntry>()
            fun cmp(name: String, a: ProfileField, b: ProfileField) {
                if (a.status != ScanStatus.READ || b.status != ScanStatus.READ) {
                    if (a.status != b.status) out += DiffEntry(name, DiffKind.NOT_COMPARABLE, a.status.name, b.status.name)
                } else if (a.items != b.items) out += DiffEntry(name, DiffKind.CHANGED, a.items.joinToString(", "), b.items.joinToString(", "))
            }
            cmp("VIN", old.vin, new.vin); cmp("Calibration IDs", old.calIds, new.calIds)
            cmp("CVNs", old.cvns, new.cvns); cmp("ECU name", old.ecuName, new.ecuName)
            if (old.protocol.isNotBlank() && new.protocol.isNotBlank() && old.protocol != new.protocol)
                out += DiffEntry("Protocol", DiffKind.CHANGED, old.protocol, new.protocol)
            val added = new.mode01Supported - old.mode01Supported.toSet()
            val removed = old.mode01Supported - new.mode01Supported.toSet()
            if (added.isNotEmpty()) out += DiffEntry("Mode 01 PIDs", DiffKind.PIDS_ADDED, "", added.joinToString(" ") { "%02X".format(it) })
            if (removed.isNotEmpty()) out += DiffEntry("Mode 01 PIDs", DiffKind.PIDS_REMOVED, removed.joinToString(" ") { "%02X".format(it) }, "")
            return ProfileDiff(out)
        }
    }
}
