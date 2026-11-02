package com.sx4scanpro

/** One point of a trend line: x runs 0..1 oldest to newest, y runs 0..1 bottom to top. */
internal data class TrendPoint(val x: Float, val y: Float)

/**
 * Short rolling history of real readings per gauge, for the tap-to-see-trend tiles.
 * Readings the ECU did not supply are not recorded, so a gap is never drawn as zero.
 */
internal class GaugeHistory(private val capacity: Int = 90) {
    private val series = HashMap<String, ArrayDeque<Float>>()

    @Synchronized
    fun record(label: String, value: Double?) {
        if (value == null || value.isNaN() || value.isInfinite()) return
        val q = series.getOrPut(label) { ArrayDeque() }
        q.addLast(value.toFloat())
        while (q.size > capacity) q.removeFirst()
    }

    @Synchronized
    fun series(label: String): List<Float> = series[label]?.toList() ?: emptyList()

    @Synchronized
    fun clear() = series.clear()

    companion object {
        /**
         * Scales [values] to the unit square. The range is the data's own min..max with a margin;
         * a flat line is centred rather than stretched into false variation.
         */
        fun trend(values: List<Float>): List<TrendPoint> {
            if (values.size < 2) return emptyList()
            val lo = values.min()
            val hi = values.max()
            val span = hi - lo
            return values.mapIndexed { i, v ->
                val x = i / (values.size - 1).toFloat()
                val y = if (span < 1e-6f) 0.5f else 0.08f + 0.84f * ((v - lo) / span)
                TrendPoint(x, y)
            }
        }
    }
}
