package com.sx4scanpro

/** The groups the Live Data screen uses. A group is shown only when at least one of its parameters is being read. */
internal enum class LiveCategory(val title: String) {
    ENGINE("Engine"), AIR_BOOST("Air / Boost"), FUEL("Fuel"), TEMPERATURE("Temperature"), ELECTRICAL("Electrical"), EMISSIONS("Emissions")
}

/** What is really known about one reading right now. Only [LIVE] shows a value as current. */
internal enum class Freshness(val label: String) { LIVE("LIVE"), STALE("STALE"), UNAVAILABLE("UNAVAILABLE"), UNSUPPORTED("UNSUPPORTED") }

/** The fields of the runtime's gauge value that this screen needs (kept separate so the model has no Android dependency). */
internal data class LiveReading(val label: String, val value: String, val unit: String, val available: Boolean, val capturedAtMs: Long, val staleAfterMs: Long)

internal data class LiveRow(
    val label: String, val category: LiveCategory, val pid: Int?, val value: String, val unit: String,
    val freshness: Freshness, val ageMs: Long?, val source: String, val note: String, val key: Boolean
)

/**
 * Presentation only: turns the gauges the runtime already produces, the PIDs the ECU advertised and the registry state into rows.
 * It reads nothing from the adapter and asks for no new PID. A missing reading is shown as "--", never as zero.
 */
internal object LiveDataModel {
    private const val BOOST = "Turbo boost"
    private val KEY_PIDS = setOf(0x0C, 0x0D, 0x05, 0x23)

    private val categoryOf: Map<Int, LiveCategory> = buildMap {
        listOf(0x0C, 0x0D, 0x04, 0x43, 0x0E, 0x11, 0x45, 0x47, 0x49, 0x4A, 0x4C, 0x5A, 0x1F, 0x61, 0x62, 0x63).forEach { put(it, LiveCategory.ENGINE) }
        listOf(0x0B, 0x10, 0x33).forEach { put(it, LiveCategory.AIR_BOOST) }
        listOf(0x06, 0x07, 0x0A, 0x22, 0x23, 0x2F, 0x44, 0x5D, 0x5E).forEach { put(it, LiveCategory.FUEL) }
        listOf(0x05, 0x0F, 0x46, 0x5C, 0x3C).forEach { put(it, LiveCategory.TEMPERATURE) }
        put(0x42, LiveCategory.ELECTRICAL)
        listOf(0x2C, 0x2D, 0x21, 0x30, 0x31, 0x4D, 0x4E).forEach { put(it, LiveCategory.EMISSIONS) }
    }

    fun categoryOfPid(pid: Int): LiveCategory? = categoryOf[pid]

    /** The Mode 01 PID behind a gauge label, or null for the derived boost value and for labels this app does not know. */
    fun pidOf(label: String): Int? = PollPlan.core.firstOrNull { it.label == label }?.pid ?: PidCatalog.forName(label)?.pid

    private fun pidText(pid: Int) = "01%02X".format(pid)

    fun rows(gauges: List<LiveReading>, supported: Set<Int>, connected: Boolean, nowMs: Long, registryState: (Int) -> ParamState? = { null }): List<LiveRow> =
        gauges.mapNotNull { g ->
            val isBoost = g.label == BOOST
            val pid = if (isBoost) null else pidOf(g.label)
            val category = if (isBoost) LiveCategory.AIR_BOOST else pid?.let { categoryOf[it] } ?: return@mapNotNull null
            val needs = if (isBoost) listOf(0x0B, PollPlan.BARO_PID) else listOfNotNull(pid)
            val number = if (g.available) g.value.toDoubleOrNull() else null
            val ageMs = if (number != null) (nowMs - g.capturedAtMs).coerceAtLeast(0L) else null
            val freshness: Freshness
            val note: String
            when {
                number != null && ageMs!! > g.staleAfterMs -> { freshness = Freshness.STALE; note = "last value ${ageMs / 1000} s ago, no newer reply" }
                number != null -> { freshness = Freshness.LIVE; note = "updated ${ageMs!! / 1000} s ago" }
                !connected -> { freshness = Freshness.UNAVAILABLE; note = "not connected" }
                supported.isEmpty() -> { freshness = Freshness.UNAVAILABLE; note = "support list not read yet" }
                needs.any { it !in supported } -> { freshness = Freshness.UNSUPPORTED; note = "not advertised by the ECU: " + needs.filter { it !in supported }.joinToString(" ") { pidText(it) } }
                else -> { freshness = Freshness.UNAVAILABLE; note = "no fresh reply from the ECU" }
            }
            val reg = pid?.let { registryState(it) }
            val source = if (isBoost) "Derived: manifold pressure (01 0B) minus barometric pressure (01 33); not a direct ECU value"
            else "Mode 01 PID " + pidText(pid!!).substring(2) + (reg?.let { " • registry: ${it.name}" } ?: "")
            LiveRow(g.label, category, pid, if (number != null) g.value else "--", g.unit, freshness, ageMs, source, note, isBoost || pid in KEY_PIDS)
        }

    /** Rows grouped in the fixed category order; empty groups are left out. */
    fun grouped(rows: List<LiveRow>): List<Pair<LiveCategory, List<LiveRow>>> =
        LiveCategory.values().mapNotNull { c -> rows.filter { it.category == c }.takeIf { it.isNotEmpty() }?.let { c to it } }

    fun summary(rows: List<LiveRow>): String {
        val n = { f: Freshness -> rows.count { it.freshness == f } }
        return "${n(Freshness.LIVE)} live • ${n(Freshness.STALE)} stale • ${n(Freshness.UNAVAILABLE)} unavailable • ${n(Freshness.UNSUPPORTED)} unsupported"
    }
}
