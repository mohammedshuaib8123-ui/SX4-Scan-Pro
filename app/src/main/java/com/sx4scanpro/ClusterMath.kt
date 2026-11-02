package com.sx4scanpro

/** Pure helpers behind the cluster home page and the welcome sequence (unit-tested). */
internal object ClusterMath {
    /** How many of [count] shift-light segments are lit for a 0..1 reading. NaN lights none. */
    fun litSegments(fraction: Float, count: Int): Int =
        if (count <= 0 || fraction.isNaN()) 0 else Math.round(fraction.coerceIn(0f, 1f) * count)

    /** Progress of [t] through the window [start, end], clamped to 0..1. */
    fun phase(t: Float, start: Float, end: Float): Float =
        if (end <= start) (if (t >= end) 1f else 0f) else ((t - start) / (end - start)).coerceIn(0f, 1f)

    fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

    fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)

    /** Start-up needle sweep as on a real cluster: rests, rises to full scale, then settles at zero. */
    fun welcomeNeedle(t: Float): Float =
        if (t < 0.5f) easeOut(phase(t, 0.1f, 0.5f)) else 1f - easeInOut(phase(t, 0.5f, 0.82f))
}
