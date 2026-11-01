package com.sx4scanpro

/** Strict decoder for single-frame Mode 01 PID responses with headers disabled. */
internal object ObdPidResponseDecoder {
    fun decode(command: String, response: String): String? {
        val pid = command.uppercase()
        if (!pid.matches(Regex("010[0-9A-F]|011[0-9A-F]|012[0-9A-F]|013[0-9A-F]"))) return null
        val expectedBytes = if (pid == "010C" || pid == "0110") 2 else 1
        val prefix = "41" + pid.substring(2)
        val normalized = response.uppercase().filterNot { it.isWhitespace() }
        if (!normalized.startsWith(prefix)) return null
        val payload = normalized.removePrefix(prefix)
        if (payload.length != expectedBytes * 2 || payload.any { it !in "0123456789ABCDEF" }) return null
        val a = payload.substring(0, 2).toIntOrNull(16) ?: return null
        val b = if (expectedBytes == 2) payload.substring(2, 4).toIntOrNull(16) ?: return null else 0
        return when (pid) {
            "010C" -> String.format(java.util.Locale.US, "%.0f", (a * 256 + b) / 4.0)
            "0105", "010F" -> (a - 40).toString()
            "010D", "010B", "0133" -> a.toString()
            "0110" -> String.format(java.util.Locale.US, "%.2f", (a * 256 + b) / 100.0)
            "0104", "0111" -> String.format(java.util.Locale.US, "%.1f", a * 100.0 / 255.0)
            else -> null
        }
    }
}
