package com.sx4scanpro

/**
 * Mode 02 freeze frame: the sensor values the ECU stored at the moment a fault was set.
 * Request "02<pid>00", reply "42<pid>00<data>". Only an exact reply is decoded.
 *
 * [command] and [decode] are the original single-PID helpers (null means "not a valid reply"). The complete read, with a
 * separate state for every item, is [FreezeFrameReader] below.
 */
internal object FreezeFrame {
    /** PIDs worth asking for first, if the ECU lists them. */
    val PIDS = listOf(0x0C, 0x0D, 0x05, 0x04, 0x0B, 0x11, 0x0F, 0x10, 0x2C)

    fun command(pid: Int): String = String.format("02%02X00", pid)

    data class Entry(val name: String, val value: String, val unit: String)

    fun decode(spec: PidSpec, response: String): Entry? {
        val r = response.uppercase().filterNot { it.isWhitespace() }
        val prefix = "42" + String.format("%02X", spec.pid) + "00"
        if (!r.startsWith(prefix)) return null
        // Reuse the Mode 01 decoder by rewriting the verified header.
        val v = PidCatalog.decode(spec, "41" + String.format("%02X", spec.pid) + r.removePrefix(prefix)) ?: return null
        return Entry(spec.name, PidCatalog.format(spec, v), spec.unit)
    }
}

internal enum class FreezeStatus {
    /** Decoded value with a known formula. A valid zero is a VALUE. */
    VALUE,
    /** Valid positive reply for a PID this app has no formula for: shown as raw hex, never as a number. */
    RAW_ONLY,
    NO_DATA,
    UNSUPPORTED,
    MALFORMED,
    TIMEOUT,
    LINK_FAILURE,
    /** Not requested (for example because the ECU said it holds no freeze frame). */
    NOT_ASKED;

    val hasData: Boolean get() = this == VALUE || this == RAW_ONLY
}

internal data class FreezeItem(
    val pid: Int,
    val name: String,
    val status: FreezeStatus,
    val value: String = "",
    val unit: String = "",
    val raw: String = "",
    val detail: String = ""
)

internal data class FreezeFrameResult(
    val capturedAtMs: Long,
    val frameNumber: Int,
    /** Outcome of the Mode 02 "which PIDs do you hold" request (PID 00). */
    val supportStatus: FreezeStatus,
    /** PIDs the ECU says it holds, or null when it did not say. */
    val supportedPids: Set<Int>?,
    /** Outcome of the request for the DTC that caused the freeze frame (PID 02). */
    val dtcStatus: FreezeStatus,
    /** The DTC the ECU names for this frame; null with [dtcStatus] VALUE means the ECU reported "no DTC". */
    val dtc: String?,
    val dtcRaw: String,
    val items: List<FreezeItem>,
    val notes: List<String>
) {
    val dataItems: List<FreezeItem> get() = items.filter { it.status.hasData }
    val hasData: Boolean get() = dataItems.isNotEmpty() || (dtcStatus == FreezeStatus.VALUE && dtc != null)

    fun headline(): String = when {
        hasData -> "Freeze frame $frameNumber: ${dataItems.size} value(s) read" + (dtc?.let { ", caused by $it" } ?: "") + "."
        supportStatus == FreezeStatus.NO_DATA && dtcStatus == FreezeStatus.NO_DATA ->
            "The ECU answered NO DATA: it is not holding a freeze frame, or it does not offer Mode 02."
        else -> "No freeze-frame values could be read. See the status of each request below."
    }
}

internal data class FreezeReply(val status: FreezeStatus, val dataHex: String = "", val detail: String = "")

internal object FreezeFrameDecoder {
    private const val HEX = "0123456789ABCDEF"

    /** Classifies one verbatim Mode 02 reply for [pid] at [frame]. Echoes, spaces and banners are tolerated; guesses are not. */
    fun reply(raw: String, pid: Int, frame: Int = 0, timedOut: Boolean = false): FreezeReply {
        if (timedOut) return FreezeReply(FreezeStatus.TIMEOUT, detail = "no reply before the timeout")
        val lines = FrameAssembler.lines(raw)
        if (lines.isEmpty()) return FreezeReply(FreezeStatus.MALFORMED, detail = "empty reply")
        if (lines.any { it == "NODATA" }) return FreezeReply(FreezeStatus.NO_DATA)
        // 7F 02 xx: service not supported (7F 02 78 is "response pending", not an answer).
        if (lines.any { it.startsWith("7F02") && it.length >= 6 && !it.startsWith("7F0278") })
            return FreezeReply(FreezeStatus.UNSUPPORTED, detail = "negative response")
        val prefix = "42" + "%02X".format(pid)
        val full = prefix + "%02X".format(frame)
        val hexLines = lines.filter { l -> l.isNotEmpty() && l.all { it in HEX } }
        hexLines.firstOrNull { it.startsWith(full) }?.let { line ->
            val data = line.removePrefix(full)
            return if (data.isEmpty()) FreezeReply(FreezeStatus.MALFORMED, detail = "reply carries no data bytes")
            else FreezeReply(FreezeStatus.VALUE, data)
        }
        if (hexLines.any { it.startsWith(prefix) }) return FreezeReply(FreezeStatus.MALFORMED, detail = "reply is for a different frame number")
        return FreezeReply(FreezeStatus.MALFORMED, detail = "reply is not a Mode 02 answer for PID %02X".format(pid))
    }

    /** One PID with its data bytes turned into a [FreezeItem]. A valid zero stays a value; wrong length is MALFORMED. */
    fun item(pid: Int, name: String, r: FreezeReply, rawText: String): FreezeItem {
        if (r.status != FreezeStatus.VALUE) return FreezeItem(pid, name, r.status, raw = rawText, detail = r.detail)
        val spec = PidCatalog.forPid(pid)
            ?: return FreezeItem(pid, name, FreezeStatus.RAW_ONLY, value = r.dataHex, raw = rawText, detail = "no decoder for this PID; raw hex shown")
        val v = PidCatalog.decode(spec, "41" + "%02X".format(pid) + r.dataHex)
            ?: return FreezeItem(pid, spec.name, FreezeStatus.MALFORMED, raw = rawText, detail = "data length does not match this PID")
        return FreezeItem(pid, spec.name, FreezeStatus.VALUE, PidCatalog.format(spec, v), spec.unit, rawText)
    }

    /** PID 02 data: two DTC bytes; 0000 means the ECU names no DTC. Returns null for anything that is not two well-formed bytes. */
    fun dtcOf(dataHex: String): Pair<Boolean, String?>? {
        if (dataHex.length != 4 || dataHex.any { it !in HEX }) return null
        if (dataHex == "0000") return true to null
        val code = ObdTroubleCodeDecoder.decode("43$dataHex", "03").firstOrNull() ?: return null
        return true to code
    }

    /** Mode 02 supported-PID block: 4 bytes, bit 31 = base+1. */
    fun supportBlock(dataHex: String, base: Int): Set<Int>? {
        if (dataHex.length != 8 || dataHex.any { it !in HEX }) return null
        return ObdPidSupport.pidsOf(base, dataHex.toLong(16))
    }
}

/** Complete, read-only Mode 02 read: PID 02 (causing DTC), the support list, then each supported PID. Uses requests the read-only guard already allows. */
internal object FreezeFrameReader {
    private const val MAX_ITEMS = 40
    private const val TIMEOUT_MS = 5_000L
    private val BITMAPS = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

    fun read(port: ElmPort, mode01Supported: Set<Int>, nowMs: Long, onStep: (String) -> Unit = {}): FreezeFrameResult {
        val notes = mutableListOf<String>()
        var linkLost = false

        fun ask(pid: Int): Pair<FreezeReply, String> {
            if (linkLost) return FreezeReply(FreezeStatus.NOT_ASKED, detail = "link lost earlier in this read") to ""
            onStep("Reading 02%02X".format(pid))
            val res = try { port.sendLines(FreezeFrame.command(pid), TIMEOUT_MS) }
            catch (e: Exception) {
                linkLost = true
                notes += "The link failed while reading PID " + "%02X".format(pid) + " (" + (e.message ?: e.javaClass.simpleName) + "); the remaining requests were not sent."
                return FreezeReply(FreezeStatus.LINK_FAILURE, detail = e.message ?: "link failure") to ""
            }
            return FreezeFrameDecoder.reply(res.raw, pid, 0, res.timedOut) to res.raw
        }

        // 1. The DTC that caused this frame.
        val (dtcReply, dtcRaw) = ask(0x02)
        var dtc: String? = null
        var dtcStatus = dtcReply.status
        if (dtcReply.status == FreezeStatus.VALUE) {
            val parsed = FreezeFrameDecoder.dtcOf(dtcReply.dataHex)
            if (parsed == null) { dtcStatus = FreezeStatus.MALFORMED; notes += "The causing-DTC reply was not two well-formed bytes." }
            else dtc = parsed.second
        }

        // 2. Which PIDs the ECU holds. Chained blocks 00, 20, 40, 60 while the last bit says there is another block.
        val supported = linkedSetOf<Int>()
        var base = 0
        var firstBlock: FreezeReply? = null
        while (true) {
            val (r, _) = ask(base)
            if (base == 0) firstBlock = r
            val block = if (r.status == FreezeStatus.VALUE) FreezeFrameDecoder.supportBlock(r.dataHex, base) else null
            if (block == null) {
                if (base == 0 && r.status == FreezeStatus.VALUE) firstBlock = FreezeReply(FreezeStatus.MALFORMED, detail = "support list was not 4 bytes")
                else if (base != 0) notes += "Support block %02X was not readable; later PIDs may be missing.".format(base)
                break
            }
            supported += block
            val next = base + 0x20
            if (next !in block || next > 0x60) break
            base = next
        }
        val supportStatus = firstBlock?.status ?: FreezeStatus.NOT_ASKED
        val haveList = supportStatus == FreezeStatus.VALUE

        // 3. Decide which PIDs to request.
        val toAsk: List<Int> = when {
            haveList -> {
                val wanted = supported.filter { it !in BITMAPS && it != 0x02 }
                (FreezeFrame.PIDS.filter { it in wanted } + wanted.filter { it !in FreezeFrame.PIDS }).take(MAX_ITEMS)
            }
            supportStatus == FreezeStatus.NO_DATA || supportStatus == FreezeStatus.UNSUPPORTED || supportStatus == FreezeStatus.LINK_FAILURE -> {
                notes += if (supportStatus == FreezeStatus.NO_DATA)
                    "The ECU answered NO DATA to the Mode 02 support request, so no individual PIDs were requested."
                else "The Mode 02 support request was not accepted, so no individual PIDs were requested."
                emptyList()
            }
            else -> {
                notes += "The ECU did not report its Mode 02 support list; only the standard freeze-frame PIDs it advertises in Mode 01 were requested."
                FreezeFrame.PIDS.filter { it in mode01Supported }
            }
        }
        if (haveList && supported.count { it !in BITMAPS && it != 0x02 } > MAX_ITEMS)
            notes += "The ECU lists more than $MAX_ITEMS freeze-frame PIDs; the first $MAX_ITEMS were requested."

        val items = toAsk.map { pid ->
            val (r, raw) = ask(pid)
            FreezeFrameDecoder.item(pid, PidCatalog.forPid(pid)?.name ?: "PID %02X".format(pid), r, raw)
        }
        return FreezeFrameResult(nowMs, 0, supportStatus, if (haveList) supported else null, dtcStatus, dtc, dtcRaw, items, notes)
    }
}

/** Wording shared by the screen and the report. */
internal object FreezeFrameText {
    fun status(s: FreezeStatus): String = when (s) {
        FreezeStatus.VALUE -> "Read"
        FreezeStatus.RAW_ONLY -> "Read (raw, no decoder)"
        FreezeStatus.NO_DATA -> "No reply (NO DATA)"
        FreezeStatus.UNSUPPORTED -> "Not supported by the ECU"
        FreezeStatus.MALFORMED -> "Reply could not be read"
        FreezeStatus.TIMEOUT -> "Timed out"
        FreezeStatus.LINK_FAILURE -> "Link failure"
        FreezeStatus.NOT_ASKED -> "Not requested"
    }

    fun itemLine(i: FreezeItem): String = when {
        i.status == FreezeStatus.VALUE -> "${i.name}: ${i.value} ${i.unit}".trimEnd()
        i.status == FreezeStatus.RAW_ONLY -> "${i.name}: raw ${i.value}"
        else -> "${i.name}: ${status(i.status)}" + (if (i.detail.isNotBlank()) " (${i.detail})" else "")
    }

    fun dtcLine(r: FreezeFrameResult): String = when {
        r.dtcStatus == FreezeStatus.VALUE && r.dtc != null -> "Fault that set this frame: ${r.dtc}"
        r.dtcStatus == FreezeStatus.VALUE -> "The ECU names no fault for this frame (reported 0000)"
        else -> "Fault that set this frame: ${status(r.dtcStatus)}"
    }
}
