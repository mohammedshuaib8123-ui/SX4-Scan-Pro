package com.sx4scanpro

/**
 * Display range of a dial or bar. It is a drawing scale only: it is never a limit, a redline
 * or a warning zone. Limits must come from verified SX4 specifications.
 */
internal data class DialScale(
    val max: Float,
    val majorStep: Float,
    val labelDivisor: Float = 1f,
    val minorPerMajor: Int = 4,
    val caption: String = ""
) {
    /** Position of [value] on the dial, 0..1. Null or NaN (no reading) is 0, never a made-up value. */
    fun fraction(value: Double?): Float {
        if (value == null || value.isNaN() || max <= 0f) return 0f
        return (value / max).toFloat().coerceIn(0f, 1f)
    }

    /** 0, majorStep, 2*majorStep ... up to and including max. */
    fun majorTicks(): List<Float> {
        if (majorStep <= 0f) return listOf(0f)
        val count = Math.floor((max / majorStep + 1e-4f).toDouble()).toInt()
        return (0..count).map { it * majorStep }
    }

    fun tickLabel(tick: Float): String {
        val scaled = tick / labelDivisor
        return if (scaled == Math.floor(scaled.toDouble()).toFloat()) scaled.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", scaled)
    }

    companion object {
        fun forLabel(label: String): DialScale = when (label) {
            "Engine RPM" -> DialScale(5000f, 1000f, 1000f, 4, "TICKS x1000 RPM")
            "Vehicle speed" -> DialScale(180f, 20f)
            "Coolant temperature" -> DialScale(120f, 20f)
            "Intake air temperature" -> DialScale(100f, 20f)
            "Manifold pressure" -> DialScale(250f, 50f)
            "Turbo boost" -> DialScale(200f, 50f)
            "MAF airflow" -> DialScale(100f, 20f)
            else -> DialScale(100f, 20f)
        }
    }
}
