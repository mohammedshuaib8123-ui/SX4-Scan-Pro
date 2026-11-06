package com.sx4scanpro

internal data class LiveGauge(val label: String, val value: String, val unit: String, val available: Boolean, val capturedAtMs: Long)

/** Which PIDs the dashboard polls and how often. Intervals are starting points to be tuned from the session log. */
internal object PollPlan {
    /** label, PID, unit, interval ms. Order is the dashboard order (boost is inserted after manifold pressure). */
    val core = listOf(
        Quad("Engine RPM", 0x0C, "rpm", 600L),
        Quad("Vehicle speed", 0x0D, "km/h", 1000L),
        Quad("Coolant temperature", 0x05, "°C", 3500L),
        Quad("Intake air temperature", 0x0F, "°C", 3500L),
        Quad("Manifold pressure", 0x0B, "kPa", 1000L),
        Quad("MAF airflow", 0x10, "g/s", 1500L),
        Quad("Calculated engine load", 0x04, "%", 2000L),
        Quad("Throttle position", 0x11, "%", 2000L),
        Quad("EGR commanded", 0x2C, "%", 3500L)
    )
    data class Quad(val label: String, val pid: Int, val unit: String, val intervalMs: Long)

    const val BARO_PID = 0x33
    const val BARO_INTERVAL_MS = 10_000L
    const val BARO_MAX_AGE_MS = 60_000L
    const val EXTRA_INTERVAL_MS = 3500L

    /** A reading older than this is shown as unavailable, never as live. */
    fun freshMs(intervalMs: Long): Long = maxOf(4000L, intervalMs * 3)

    fun configure(s: PollScheduler, extras: Set<Int>) {
        core.forEach { s.set(it.pid, it.intervalMs) }
        s.set(BARO_PID, BARO_INTERVAL_MS)
        extras.filter { it != BARO_PID && core.none { c -> c.pid == it } }.forEach { s.set(it, EXTRA_INTERVAL_MS) }
    }
}

internal object GaugeAssembler {
    fun build(reading: (Int) -> Reading?, advertised: Set<Int>, extras: Set<Int>, now: Long): List<LiveGauge> {
        fun fresh(pid: Int, interval: Long): Reading? = reading(pid)?.takeIf { now - it.atMs <= PollPlan.freshMs(interval) }
        fun core(c: PollPlan.Quad): LiveGauge {
            val spec = PidCatalog.forPid(c.pid)
            val r = if (c.pid in advertised && spec != null) fresh(c.pid, c.intervalMs) else null
            return if (r != null && spec != null) LiveGauge(c.label, PidCatalog.format(spec, r.value), c.unit, true, r.atMs)
            else LiveGauge(c.label, "--", c.unit, false, now)
        }
        val base = PollPlan.core.map { core(it) }
        val map = reading(0x0B)?.takeIf { 0x0B in advertised && now - it.atMs <= PollPlan.freshMs(1000L) }
        val baro = reading(PollPlan.BARO_PID)?.takeIf { PollPlan.BARO_PID in advertised && now - it.atMs <= PollPlan.BARO_MAX_AGE_MS }
        val boost = if (map != null && baro != null)
            LiveGauge("Turbo boost", String.format(java.util.Locale.US, "%.1f", (map.value - baro.value).coerceAtLeast(0.0)), "kPa", true, map.atMs)
        else LiveGauge("Turbo boost", "--", "kPa", false, now)
        val extraGauges = (extras + HOME).sorted().mapNotNull { PidCatalog.forPid(it) }
            .filter { s -> PollPlan.core.none { it.pid == s.pid } && s.pid != PollPlan.BARO_PID }
            .map { spec ->
                val r = if (spec.pid in advertised) fresh(spec.pid, PollPlan.EXTRA_INTERVAL_MS) else null
                if (r != null) LiveGauge(spec.name, PidCatalog.format(spec, r.value), spec.unit, true, r.atMs)
                else LiveGauge(spec.name, "--", spec.unit, false, now)
            }
        return base.take(5) + boost + base.drop(5) + extraGauges
    }

    /** Fuel/boost PIDs the Home screen always wants (mirrors the app's HOME_PIDS). */
    val HOME = setOf(0x2F, 0x42, 0x23, 0x5E)
}
