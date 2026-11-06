package com.sx4scanpro

/** How the vehicle bus frames multi-byte replies. It cannot be guessed from the bytes alone, so it comes from ATDP. */
enum class Framing {
    KLINE, CAN, UNKNOWN;

    companion object {
        fun fromProtocolText(atdp: String?): Framing {
            val t = atdp.orEmpty().uppercase()
            return when {
                t.isBlank() -> UNKNOWN
                "CAN" in t || "15765" in t -> CAN
                "9141" in t || "14230" in t || "KWP" in t || "J1850" in t -> KLINE
                else -> UNKNOWN
            }
        }
    }
}

internal object FrameAssembler {
    private val indexed = Regex("^[0-9A-F]:[0-9A-F]*$")
    private val lengthLine = Regex("^[0-9A-F]{3}$")

    /** Cleaned reply lines: upper-case, no spaces, no banners. Input lines are separated by CR or LF. */
    fun lines(raw: String): List<String> = raw.uppercase().split('\r', '\n')
        .map { it.filterNot { c -> c.isWhitespace() } }
        .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && !(it.startsWith("BUSINIT") && it.endsWith("OK")) }

    /**
     * Joins an ISO-TP style reply (a 3-digit length line followed by `N:` lines) into one hex payload truncated to the
     * declared length. Returns null if the lines are out of order or shorter than declared. A reply without indexed
     * lines is returned unchanged (single frame).
     */
    fun canPayload(lines: List<String>): String? {
        val idx = lines.filter { indexed.matches(it) }
        if (idx.isEmpty()) return lines.firstOrNull { it.all { c -> c in "0123456789ABCDEF" } }
        var expect = 0
        val sb = StringBuilder()
        for (l in idx) {
            if (l[0].digitToInt(16) != expect % 16) return null
            expect++
            sb.append(l.substring(2))
        }
        val declared = lines.firstOrNull { lengthLine.matches(it) }?.toIntOrNull(16)
        if (sb.length % 2 != 0) return null
        if (declared != null) { if (sb.length / 2 < declared) return null; sb.setLength(declared * 2) }
        return sb.toString()
    }
}
