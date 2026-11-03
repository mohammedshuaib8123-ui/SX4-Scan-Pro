package com.sx4scanpro

internal enum class ThemeMode(val label: String) { AUTO("Auto"), DAY("Day"), NIGHT("Night") }

/** Screen dimming for night driving, from the tablet's light sensor. */
internal object NightMode {
    const val MAX_DIM = 0.55f
    private const val DARK_LUX = 2f
    private const val BRIGHT_LUX = 50f

    /** Black overlay opacity 0..[MAX_DIM]. No sensor reading means no dimming in Auto. */
    fun dimAlpha(mode: ThemeMode, lux: Float?): Float = when (mode) {
        ThemeMode.DAY -> 0f
        ThemeMode.NIGHT -> MAX_DIM
        ThemeMode.AUTO -> if (lux == null || lux.isNaN()) 0f else {
            val t = ((BRIGHT_LUX - lux) / (BRIGHT_LUX - DARK_LUX)).coerceIn(0f, 1f)
            t * MAX_DIM
        }
    }

    fun modeOf(name: String?): ThemeMode = ThemeMode.values().firstOrNull { it.name == name } ?: ThemeMode.AUTO
}

/** Deep link into the Open Headunit receiver. */
internal object HeadUnitLink {
    fun normalizeIp(text: String?): String? {
        val parts = text.orEmpty().trim().split('.')
        if (parts.size != 4) return null
        val nums = parts.map { p -> if (p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() }) p.toInt() else return null }
        return if (nums.all { it in 0..255 }) nums.joinToString(".") else null
    }

    fun connectUri(ip: String?): String? = normalizeIp(ip)?.let { "headunit://connect?ip=$it" }
}
