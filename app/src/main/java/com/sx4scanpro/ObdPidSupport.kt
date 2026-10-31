package com.sx4scanpro

/** Decodes ISO 15031-5 / SAE J1979 Mode 01 supported-PID bitmaps. */
internal object ObdPidSupport {
    fun decode(modeResponse: String): Set<Int> {
        val response = modeResponse.trim().uppercase().filterNot { it.isWhitespace() }
        val supported = linkedSetOf<Int>()
        decodeBlock(response, "4100", 1, supported)
        if (32 in supported) decodeBlock(response, "4120", 33, supported)
        return supported
    }

    private fun decodeBlock(response: String, prefix: String, firstPid: Int, output: MutableSet<Int>) {
        val index = response.indexOf(prefix)
        if (index < 0) return
        val bitmap = response.substring(index + prefix.length).take(8)
        if (bitmap.length != 8 || bitmap.any { it !in "0123456789ABCDEF" }) return
        val mask = bitmap.toLongOrNull(16) ?: return
        for (offset in 0..31) {
            if (((mask shr (31 - offset)) and 1L) == 1L) output.add(firstPid + offset)
        }
    }
}
