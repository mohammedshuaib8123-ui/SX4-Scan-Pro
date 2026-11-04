package com.sx4scanpro

internal enum class ScanStatus { READ, NO_DATA, REJECTED, MALFORMED, NOT_ADVERTISED, NOT_ATTEMPTED, ADAPTER_ERROR }

/** One Mode 09 item: what was decoded (never guessed), the raw reply as hex, and why it is not usable if it is not. */
internal data class Mode09Value(val pid: Int, val status: ScanStatus, val items: List<String> = emptyList(), val rawHex: String = "", val note: String = "")

internal object Mode09Decoder {
    private const val HEX = "0123456789ABCDEF"
    private val vinOk = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    private fun bytes(hex: String): List<Int> = hex.chunked(2).map { it.toInt(16) }
    private fun ascii(b: List<Int>): String = b.filter { it != 0 }.map { it.toChar() }.joinToString("").trim()
    private fun isHex(s: String) = s.isNotEmpty() && s.length % 2 == 0 && s.all { it in HEX }

    /** [raw] is the adapter reply with one line per frame (CR/LF separated). */
    fun decode(pid: Int, raw: String, framing: Framing): Mode09Value {
        val kind = ElmReply.classify(raw)
        val lines = FrameAssembler.lines(raw)
        val rawHex = lines.joinToString(" ")
        when (kind) {
            ElmKind.NO_DATA -> return Mode09Value(pid, ScanStatus.NO_DATA, rawHex = rawHex)
            ElmKind.UNKNOWN_COMMAND -> return Mode09Value(pid, ScanStatus.REJECTED, rawHex = rawHex, note = "adapter did not understand the request")
            ElmKind.EMPTY, ElmKind.UNABLE_TO_CONNECT, ElmKind.BUS_ERROR, ElmKind.CAN_ERROR, ElmKind.BUS_BUSY, ElmKind.STOPPED,
            ElmKind.DATA_ERROR, ElmKind.BUFFER_FULL, ElmKind.ACTIVITY_MONITOR, ElmKind.LOW_VOLTAGE_RESET ->
                return Mode09Value(pid, ScanStatus.ADAPTER_ERROR, rawHex = rawHex, note = kind.name)
            else -> {}
        }
        val p = "%02X".format(pid)
        if (lines.any { it.startsWith("7F09") }) return Mode09Value(pid, ScanStatus.REJECTED, rawHex = rawHex, note = "negative response from ECU")
        // Framing decides how the data is rejoined; with UNKNOWN only a headerless single frame or indexed lines are accepted.
        val payload: String? = when {
            lines.any { it.length > 1 && it[1] == ':' } -> FrameAssembler.canPayload(lines)
            framing == Framing.KLINE || framing == Framing.UNKNOWN -> klinePayload(lines, p, pid)
            else -> lines.singleOrNull()
        }
        if (payload == null || !isHex(payload) || !payload.startsWith("49$p"))
            return Mode09Value(pid, ScanStatus.MALFORMED, rawHex = rawHex, note = "reply could not be assembled")
        val body = payload.removePrefix("49$p")
        return interpret(pid, body, rawHex, indexedFraming = lines.any { it.length > 1 && it[1] == ':' } || framing == Framing.CAN)
    }

    /** K-line: every line is `49 PP NN data`; NN counts 01, 02, ... and the data parts are joined. PID 00 is a single line. */
    private fun klinePayload(lines: List<String>, p: String, pid: Int): String? {
        val sb = StringBuilder("49$p")
        if (pid == 0) return lines.singleOrNull()
        var expect = 1
        for (l in lines) {
            if (!l.startsWith("49$p") || !isHex(l) || l.length < 8) return null
            if (l.substring(4, 6).toInt(16) != expect) return null
            expect++
            sb.append(l.substring(6))
        }
        return sb.toString()
    }

    private fun interpret(pid: Int, body: String, rawHex: String, indexedFraming: Boolean): Mode09Value {
        fun bad(why: String) = Mode09Value(pid, ScanStatus.MALFORMED, rawHex = rawHex, note = why)
        if (pid == 0) {
            if (body.length != 8) return bad("support bitmap must be 4 bytes")
            val v = body.toLong(16)
            val set = (1..32).filter { v and (1L shl (32 - it)) != 0L }.map { "%02X".format(it) }
            return Mode09Value(pid, ScanStatus.READ, set, rawHex)
        }
        // Rejoined payload: one count byte (CAN) or per-line sequence numbers already removed (K-line).
        val data = if (indexedFraming) { if (body.length < 2) return bad("missing item count"); body.substring(2) } else body
        if (!isHex(data)) return bad("no data bytes")
        val b = bytes(data)
        return when (pid) {
            0x02 -> {
                val vin = ascii(b)
                if (vinOk.matches(vin)) Mode09Value(pid, ScanStatus.READ, listOf(vin), rawHex)
                else bad("not a valid 17-character VIN")
            }
            0x04 -> {
                if (b.size % 16 != 0) bad("calibration IDs are 16 bytes each") else Mode09Value(pid, ScanStatus.READ, b.chunked(16).map { ascii(it) }.filter { it.isNotEmpty() }, rawHex)
            }
            0x06 -> {
                if (b.size % 4 != 0) bad("CVNs are 4 bytes each") else Mode09Value(pid, ScanStatus.READ, b.chunked(4).map { c -> c.joinToString("") { "%02X".format(it) } }, rawHex)
            }
            0x0A -> Mode09Value(pid, ScanStatus.READ, listOf(ascii(b)).filter { it.isNotEmpty() }, rawHex).let { if (it.items.isEmpty()) bad("empty name") else it }
            else -> bad("PID not decoded by this app")
        }
    }
}
