package com.sx4scanpro

/** Dial and wall geometry, free of Android types so it can be tested. All lengths share one unit (px or dp). */
internal object DialLayout {
    /** Tick-label size: proportional to the dial, kept between [minLabel] and [maxLabel] so it never dominates a small slot. */
    fun labelSize(side: Float, minLabel: Float, maxLabel: Float): Float = (side * 0.07f).coerceIn(minLabel, maxLabel)

    /** Radius of the ring. It never falls below 30% of the slot, so the ring cannot collapse into a small circle. */
    fun ringRadius(side: Float, stroke: Float, label: Float): Float =
        maxOf(side / 2f - (stroke / 2f + label * 1.9f), side * 0.30f)

    /** Wall row height from the tile width, so tiles look the same at any dial count and on any screen. */
    fun rowHeight(tileWidth: Float): Float = tileWidth * 0.8f
}
