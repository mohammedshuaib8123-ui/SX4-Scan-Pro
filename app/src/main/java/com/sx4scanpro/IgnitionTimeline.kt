package com.sx4scanpro

/**
 * Timing of the ignition sequence, in seconds:
 *  0.0 - 1.2  void; a laser line cuts across the centre, then splits to frame the layout
 *  1.2 - 2.5  the holographic car fades in and the headline is typed out character by character
 *  2.5 - 4.5  gauges sweep to full scale, then settle to the live reading (or zero when offline)
 *  4.5+       status line shows ECU LINK ESTABLISHED, or falls back to ECU OFFLINE
 */
internal object IgnitionTimeline {
    const val LASER_END = 0.6f
    const val SPLIT_END = 1.2f
    const val UNFOLD_END = 2.5f
    const val SWEEP_UP_END = 3.5f
    const val SETTLE_END = 4.5f
    const val TOTAL = 6.5f

    fun laser(t: Float): Float = ClusterMath.easeOut(ClusterMath.phase(t, 0f, LASER_END))
    fun split(t: Float): Float = ClusterMath.easeOut(ClusterMath.phase(t, LASER_END, SPLIT_END))
    fun unfold(t: Float): Float = ClusterMath.easeOut(ClusterMath.phase(t, SPLIT_END, UNFOLD_END))

    /** Needle position 0..1: up to full scale, then settles onto [live] (0 when there is no signal). */
    fun sweep(t: Float, live: Float): Float {
        val up = ClusterMath.easeOut(ClusterMath.phase(t, UNFOLD_END, SWEEP_UP_END))
        val settle = ClusterMath.easeInOut(ClusterMath.phase(t, SWEEP_UP_END, SETTLE_END))
        val target = live.coerceIn(0f, 1f)
        return if (t < SWEEP_UP_END) up else 1f + (target - 1f) * settle
    }

    /** True while the gauges are still running their calibration move (readouts are not live data). */
    fun calibrating(t: Float): Boolean = t in UNFOLD_END..SETTLE_END

    /** Characters of a [total]-long string shown at time [t] within the window [start, end]. */
    fun typed(t: Float, total: Int, start: Float, end: Float): Int =
        Math.floor((ClusterMath.phase(t, start, end) * total).toDouble()).toInt().coerceIn(0, total)

    fun finished(t: Float): Boolean = t >= SETTLE_END
}
