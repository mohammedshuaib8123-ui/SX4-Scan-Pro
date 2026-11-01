package com.sx4scanpro

/** What the ECU said to one DTC request (mode 03, 07 or 0A), line by line. */
data class DtcReadResult(
    /** Decoded codes from every well-formed reply line, in reply order. */
    val codes: List<String>,
    /** Number of reply lines that were well-formed positive responses (an all-zero line counts). */
    val validFrames: Int,
    /** The adapter answered NO DATA: the ECU did not reply at all. */
    val noData: Boolean,
    /** The ECU answered with a negative response (7F <mode> ..): service not supported. */
    val unsupported: Boolean,
    /** Reply lines that could not be interpreted. They are reported, never guessed at. */
    val unreadable: List<String>
)

/**
 * Strict decoder for headerless ELM OBD-II Mode 03/07/0A responses.
 * It accepts only the expected positive-response prefix and aligned DTC pairs.
 * It intentionally does not guess at CAN headers, ISO-TP PCI bytes or multiple ECU frames.
 */
object ObdTroubleCodeDecoder {
    private const val HEX = "0123456789ABCDEF"

    private fun prefixFor(mode: String): String? = when (mode) {
        "03" -> "43"
        "07" -> "47"
        "0A" -> "4A"
        else -> null
    }

    fun decode(response: String, mode: String): List<String> {
        val prefix = prefixFor(mode) ?: return emptyList()
        val normalized = response.uppercase().filterNot { it.isWhitespace() }
        return parseLine(normalized, prefix) ?: emptyList()
    }

    /**
     * Decodes a reply that may span several lines (one per ECU frame), as the ELM327 returns
     * on K-line when more than three codes are stored. Each line is validated on its own with
     * exactly the same strict rules as [decode]; anything that fails is listed in
     * [DtcReadResult.unreadable] instead of being dropped, so a scan can never silently look clean.
     */
    fun decodeResponse(raw: String, mode: String): DtcReadResult {
        val prefix = prefixFor(mode)
            ?: return DtcReadResult(emptyList(), 0, false, false, listOf("unknown mode $mode"))
        val codes = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        var valid = 0
        var noData = false
        var unsupported = false

        raw.uppercase().split('\r', '\n').forEach { rawLine ->
            val line = rawLine.filterNot { it.isWhitespace() }
            when {
                line.isEmpty() -> Unit
                line.startsWith("SEARCHING") -> Unit
                line.startsWith("BUSINIT") && line.endsWith("OK") -> Unit
                line == "NODATA" -> noData = true
                // 7F <mode> 78 is "response pending", not "unsupported": do not treat it as an answer.
                line.startsWith("7F$mode") && line.length >= 6 && !line.startsWith("7F${mode}78") ->
                    unsupported = true
                else -> {
                    val parsed = parseLine(line, prefix)
                    if (parsed == null) unreadable.add(line)
                    else {
                        valid++
                        codes.addAll(parsed)
                    }
                }
            }
        }
        return DtcReadResult(codes.distinct(), valid, noData, unsupported, unreadable)
    }

    /** Null means the line is not a well-formed positive response for [prefix]. */
    private fun parseLine(normalized: String, prefix: String): List<String>? {
        if (!normalized.startsWith(prefix)) return null
        val payload = normalized.removePrefix(prefix)
        if (payload.isBlank() || payload.length % 4 != 0 || payload.any { it !in HEX }) return null

        return payload.chunked(4).mapNotNull { pair ->
            val a = pair.substring(0, 2).toIntOrNull(16) ?: return@mapNotNull null
            val b = pair.substring(2, 4).toIntOrNull(16) ?: return@mapNotNull null
            if (a == 0 && b == 0) return@mapNotNull null
            val system = when ((a shr 6) and 0x03) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                else -> "U"
            }
            val digit2 = ((a shr 4) and 0x03).toString()
            system + digit2 + "%X%02X".format(a and 0x0F, b)
        }
    }
}
