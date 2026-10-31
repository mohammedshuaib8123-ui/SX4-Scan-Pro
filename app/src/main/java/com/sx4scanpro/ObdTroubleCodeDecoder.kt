package com.sx4scanpro

/**
 * Strict decoder for headerless ELM OBD-II Mode 03/07/0A responses.
 * It accepts only the expected positive-response prefix and aligned DTC pairs.
 * It intentionally does not guess at CAN headers, ISO-TP PCI bytes or multiple ECU frames.
 */
object ObdTroubleCodeDecoder {
    fun decode(response: String, mode: String): List<String> {
        val prefix = when (mode) {
            "03" -> "43"
            "07" -> "47"
            "0A" -> "4A"
            else -> return emptyList()
        }
        val normalized = response.uppercase().filterNot { it.isWhitespace() }
        if (!normalized.startsWith(prefix)) return emptyList()
        val payload = normalized.removePrefix(prefix)
        if (payload.isBlank() || payload.length % 4 != 0 ||
            payload.any { it !in "0123456789ABCDEF" }) return emptyList()

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
            system + digit2 + "%02X%02X".format(a and 0x0F, b)
        }
    }
}
