package com.sx4scanpro

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How the exported session ended, judged only from what the session log says. */
internal enum class SessionEnding {
    DISCONNECTED, APP_CLOSED, INCOMPLETE, UNKNOWN;

    companion object {
        /** [lastMarker] is the last ending-related event found in the log; [liveNow] means the link is up while exporting. */
        fun infer(lastMarker: SessionEnding?, liveNow: Boolean): SessionEnding = when {
            liveNow -> INCOMPLETE
            lastMarker != null -> lastMarker
            else -> UNKNOWN
        }

        fun markerOf(line: String): SessionEnding? = when {
            line.contains(" EVT app closed") -> APP_CLOSED
            line.contains(" EVT connection ") && line.contains("-> DISCONNECTED") -> DISCONNECTED
            line.contains(" EVT connection ") && line.contains("-> ERROR") -> INCOMPLETE
            else -> null
        }
    }
}

/**
 * Export-time redaction. Applied to a copy while the export is written; stored diagnostic data is never changed.
 * Masks VINs (keeps the 3-character manufacturer code), VIN replies to service 09 PID 02, the Bluetooth adapter name and MAC addresses.
 */
internal class Redactor(val enabled: Boolean, vins: Collection<String> = emptyList(), adapterNames: Collection<String> = emptyList()) {
    private val knownVins = vins.map { it.trim() }.filter { it.length >= 11 }
    private val names = adapterNames.map { it.trim() }.filter { it.length >= 3 }

    fun maskVin(vin: String): String = if (vin.length <= 3) "*".repeat(vin.length) else vin.take(3) + "*".repeat(vin.length - 3)

    fun text(s: String): String {
        if (!enabled || s.isEmpty()) return s
        var r = s
        knownVins.forEach { r = r.replace(it, maskVin(it), ignoreCase = true) }
        r = VIN_LIKE.replace(r) { maskVin(it.value) }
        names.forEach { r = r.replace(it, ADAPTER_HIDDEN, ignoreCase = true) }
        return MAC.replace(r, "[mac hidden]")
    }

    /** Recursively applies [text] to every string in parsed JSON. */
    fun scrub(v: Any?): Any? = when (v) {
        is String -> text(v)
        is Map<*, *> -> v.entries.associateTo(LinkedHashMap<String, Any?>()) { it.key.toString() to scrub(it.value) }
        is List<*> -> v.map { scrub(it) }
        else -> v
    }

    companion object {
        const val ADAPTER_HIDDEN = "[adapter name hidden]"
        const val VIN_REPLY_HIDDEN = "[VIN reply redacted]"
        private val VIN_LIKE = Regex("(?<![A-Za-z0-9])(?=[A-HJ-NPR-Z0-9]*[A-HJ-NPR-Z])(?=[A-HJ-NPR-Z0-9]*[0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Za-z0-9])")
        private val MAC = Regex("(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])")
        fun isVinRequest(command: String): Boolean = command.replace(" ", "").uppercase().startsWith("0902")
    }
}

internal class ExportSources(
    /** Session log files, oldest first (see [SessionLog.files]). */
    val logFiles: List<File>,
    /** Capture JSONL files, oldest first (see [CaptureStore.files]). */
    val captureFiles: List<File>,
    val registry: ParameterRegistry?,
    val profile: EcuProfile?
)

internal data class ExportRequest(
    val buildLabel: String, val adapterName: String, val protocol: String, val currentSessionId: String,
    val liveNow: Boolean, val redact: Boolean, val nowMs: Long
)

internal data class ExportResult(val file: File?, val manifest: Map<String, Any?>, val complete: Boolean, val error: String?)

/**
 * One self-contained ZIP: session_events.log, capture.jsonl, registry_summary.json, ecu_profile.json and manifest.json (written last,
 * with a SHA-256 and size for every other entry). Reads only what the app already stored: no vehicle communication. Streams line by line,
 * so memory use does not grow with the log; source data beyond [maxSourceBytes] per component is left out and the manifest says so.
 */
internal object SessionExport {
    const val FORMAT = "sx4-session-export"
    const val FORMAT_VERSION = 1L
    const val DEFAULT_MAX_SOURCE_BYTES = 8_000_000L

    private class Counter(val name: String) {
        val sha: MessageDigest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var records = 0L
        var present = false
        var truncated = false
        var skippedFiles = 0
        fun write(out: OutputStream, text: String) { val b = text.toByteArray(Charsets.UTF_8); out.write(b); sha.update(b); bytes += b.size }
        fun hex(): String = sha.digest().joinToString("") { "%02x".format(it) }
    }

    /** Newest files whose total size fits [limit], returned oldest first. */
    private fun withinLimit(files: List<File>, limit: Long): Pair<List<File>, Int> {
        val existing = files.filter { it.isFile }
        var total = 0L
        val kept = ArrayList<File>()
        for (f in existing.asReversed()) { if (total + f.length() > limit) break; total += f.length(); kept += f }
        return kept.asReversed() to (existing.size - kept.size)
    }

    fun build(dest: File, req: ExportRequest, src: ExportSources, maxSourceBytes: Long = DEFAULT_MAX_SOURCE_BYTES): ExportResult {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            dest.parentFile?.mkdirs()
            val result = ZipOutputStream(tmp.outputStream().buffered()).use { zip -> write(zip, req, src, maxSourceBytes) }
            if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) throw java.io.IOException("could not move the finished export into place") }
            result.copy(file = dest)
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            ExportResult(null, emptyMap(), false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun write(zip: ZipOutputStream, req: ExportRequest, src: ExportSources, maxSourceBytes: Long): ExportResult {
        val vins = ArrayList<String>()
        src.profile?.vin?.items?.let { vins += it }
        val names = if (req.adapterName.isNotBlank()) listOf(req.adapterName) + listOfNotNull(src.profile?.adapterName?.takeIf { it.isNotBlank() }) else listOfNotNull(src.profile?.adapterName?.takeIf { it.isNotBlank() })
        val red = Redactor(req.redact, vins, names)

        fun entry(name: String, body: (Counter) -> Unit): Counter {
            val c = Counter(name)
            zip.putNextEntry(ZipEntry(name).apply { time = req.nowMs })
            body(c)
            zip.closeEntry()
            return c
        }

        // 1. Session events (the existing session log, line by line).
        var lastMarker: SessionEnding? = null
        val log = entry("session_events.log") { c ->
            val (files, skipped) = withinLimit(src.logFiles, maxSourceBytes)
            c.skippedFiles = skipped; c.truncated = skipped > 0; c.present = files.isNotEmpty()
            var vinReplyNext = false
            for (f in files) f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    SessionEnding.markerOf(line)?.let { lastMarker = it }
                    val out = if (!red.enabled) line else {
                        val parts = line.split(' ', limit = 4)
                        val tag = parts.getOrNull(2)
                        val body = parts.getOrNull(3) ?: ""
                        if (tag == "TX") vinReplyNext = Redactor.isVinRequest(body)
                        if (tag == "RX" && vinReplyNext) { vinReplyNext = false; parts[0] + " " + parts[1] + " RX " + Redactor.VIN_REPLY_HIDDEN } else red.text(line)
                    }
                    c.write(zip, out + "\n"); c.records++
                }
            }
        }

        // 2. Structured capture (JSONL, one record per exchange).
        val sessions = LinkedHashMap<String, Long>()
        var firstT: Long? = null; var lastT: Long? = null
        var curFirst: Long? = null; var curLast: Long? = null
        val capture = entry("capture.jsonl") { c ->
            val (files, skipped) = withinLimit(src.captureFiles, maxSourceBytes)
            c.skippedFiles = skipped; c.truncated = skipped > 0; c.present = files.isNotEmpty()
            for (f in files) f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val m = MiniJson.parse(line) as? Map<*, *>
                    var out = line
                    if (m != null) {
                        (m["session"] as? String)?.let { sessions[it] = (sessions[it] ?: 0L) + 1 }
                        (m["tMs"] as? Long)?.let { t ->
                            if (firstT == null) firstT = t; lastT = t
                            if (m["session"] == req.currentSessionId) { if (curFirst == null) curFirst = t; curLast = t }
                        }
                        if (red.enabled) {
                            val copy = LinkedHashMap<String, Any?>()
                            m.forEach { (k, v) -> copy[k.toString()] = v }
                            if (Redactor.isVinRequest(copy["command"] as? String ?: "") && copy.containsKey("raw")) copy["raw"] = Redactor.VIN_REPLY_HIDDEN
                            out = MiniJson.write(red.scrub(copy), -1)
                        }
                    } else if (red.enabled) out = red.text(line)
                    c.write(zip, out + "\n"); c.records++
                }
            }
        }

        // 3. Registry summary.
        val registry = entry("registry_summary.json") { c ->
            val reg = src.registry
            c.present = reg != null
            val body: Map<String, Any?> = if (reg == null) linkedMapOf("available" to false, "note" to "no parameter registry was available at export time") else {
                val counts = ParamState.values().associate { s -> s.name to reg.byState(s).size.toLong() }
                linkedMapOf(
                    "available" to true, "counts" to counts,
                    "parameters" to reg.all().map { d ->
                        val r = reg.record(d.id)
                        linkedMapOf<String, Any?>("id" to d.id, "name" to d.name, "state" to (r?.state ?: ParamState.UNTESTED).name,
                            "lastVerifiedMs" to r?.lastVerifiedMs, "lastChangeMs" to r?.lastChangeMs, "evidence" to (r?.evidence ?: emptyList()).map { e ->
                                linkedMapOf<String, Any?>("tMs" to e.tMs, "kind" to e.kind.name, "note" to e.note, "rawSeq" to e.rawSeq) })
                    }
                )
            }
            c.write(zip, MiniJson.write(red.scrub(body), 1) + "\n"); c.records = if (reg == null) 0 else reg.all().size.toLong()
        }

        // 4. Latest ECU profile.
        val profile = entry("ecu_profile.json") { c ->
            val p = src.profile
            c.present = p != null
            if (p == null) c.write(zip, MiniJson.write(linkedMapOf("available" to false, "note" to "no ECU scan has been run on this tablet"), 1) + "\n")
            else {
                val parsed = MiniJson.parse(p.toJson()) as? Map<*, *>
                val json: Any? = if (!red.enabled || parsed == null) parsed ?: p.toJson() else {
                    val m = LinkedHashMap<String, Any?>(); parsed.forEach { (k, v) -> m[k.toString()] = v }
                    (m["vin"] as? Map<*, *>)?.let { v ->
                        val vm = LinkedHashMap<String, Any?>(); v.forEach { (k, x) -> vm[k.toString()] = x }
                        vm["items"] = (v["items"] as? List<*>)?.map { red.maskVin(it.toString()) } ?: emptyList<String>()
                        if ((vm["rawHex"] as? String).orEmpty().isNotEmpty()) vm["rawHex"] = Redactor.VIN_REPLY_HIDDEN
                        m["vin"] = vm
                    }
                    (m["adapter"] as? Map<*, *>)?.let { a ->
                        val am = LinkedHashMap<String, Any?>(); a.forEach { (k, x) -> am[k.toString()] = x }
                        am["name"] = Redactor.ADAPTER_HIDDEN
                        m["adapter"] = am
                    }
                    red.scrub(m)
                }
                c.write(zip, (if (json is String) json else MiniJson.write(json, 1)) + "\n"); c.records = 1
            }
        }

        // 5. Manifest (last, so it can carry the hashes of everything above).
        val ending = SessionEnding.infer(lastMarker, req.liveNow)
        val parts = listOf(log, capture, registry, profile)
        val issues = ArrayList<String>()
        if (!log.present || log.records == 0L) issues += "session event log is empty or missing"
        if (!capture.present || capture.records == 0L) issues += "no structured capture records"
        if (!registry.present) issues += "parameter registry unavailable"
        if (!profile.present) issues += "no ECU profile (no ECU scan has been run)"
        parts.filter { it.truncated }.forEach { issues += it.name + ": " + it.skippedFiles + " older file(s) left out to keep the export bounded" }
        if (ending == SessionEnding.INCOMPLETE) issues += "session was still running or ended in an error when exported"
        if (ending == SessionEnding.UNKNOWN) issues += "session ending could not be determined"
        val complete = issues.isEmpty()
        val manifest: Map<String, Any?> = linkedMapOf(
            "format" to FORMAT, "formatVersion" to FORMAT_VERSION,
            "exportedAtMs" to req.nowMs,
            "build" to req.buildLabel,
            "adapter" to linkedMapOf("name" to (if (req.redact) Redactor.ADAPTER_HIDDEN else req.adapterName), "protocol" to req.protocol),
            "session" to linkedMapOf<String, Any?>(
                "currentId" to req.currentSessionId, "startMs" to curFirst, "endMs" to curLast,
                "ending" to ending.name,
                "endingBasis" to when (ending) {
                    SessionEnding.DISCONNECTED -> "last connection event in the session log was a disconnect"
                    SessionEnding.APP_CLOSED -> "the app recorded that it was closed"
                    SessionEnding.INCOMPLETE -> "the link was still up at export time, or the last event was an error"
                    SessionEnding.UNKNOWN -> "no connection or close event was found in the session log"
                },
                "idsInCapture" to sessions.map { linkedMapOf<String, Any?>("id" to it.key, "records" to it.value) },
                "captureFirstMs" to firstT, "captureLastMs" to lastT
            ),
            "redaction" to linkedMapOf<String, Any?>(
                "enabled" to req.redact,
                "vinMasked" to req.redact, "vinRepliesRemoved" to req.redact, "adapterNameHidden" to req.redact, "macAddressesHidden" to req.redact,
                "note" to (if (req.redact) "Applied to this export only; the tablet's stored data is unchanged." else "NOT redacted: contains the VIN and adapter name exactly as stored.")
            ),
            "complete" to complete, "completenessIssues" to issues,
            "files" to parts.map { c ->
                linkedMapOf<String, Any?>("name" to c.name, "present" to c.present, "records" to c.records, "bytes" to c.bytes, "sha256" to c.hex(),
                    "truncated" to c.truncated, "olderFilesLeftOut" to c.skippedFiles.toLong())
            }
        )
        zip.putNextEntry(ZipEntry("manifest.json").apply { time = req.nowMs })
        zip.write((MiniJson.write(manifest, 1) + "\n").toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        return ExportResult(null, manifest, complete, null)
    }
}
