package com.sx4scanpro

/**
 * Earliest-deadline-first scheduling of Mode 01 PIDs. Fast PIDs come round often, slow ones rarely,
 * so the cockpit gauges refresh quickly without every sensor being read on every pass.
 * Pure logic: it only decides which PID is next; it never talks to the adapter.
 */
internal class PollScheduler {
    private val intervals = linkedMapOf<Int, Long>()
    private val last = HashMap<Int, Long>()
    private val priority = HashMap<Int, Int>()
    private val stretch = HashMap<Int, Double>()

    fun set(pid: Int, intervalMs: Long) { intervals[pid] = intervalMs }
    fun remove(pid: Int) { intervals.remove(pid); last.remove(pid); priority.remove(pid); stretch.remove(pid) }

    /** Lower number = more important (0 = RPM/speed). Unset PIDs are priority 0, so plain callers keep pure earliest-deadline order. */
    fun setPriority(pid: Int, p: Int) { priority[pid] = p }
    fun priorityOf(pid: Int): Int = priority[pid] ?: 0

    /** Adaptive pacing: the interval is multiplied by [factor] (never below 1, so polling is never made more aggressive than the plan). */
    fun setStretch(pid: Int, factor: Double) { if (factor <= 1.0) stretch.remove(pid) else stretch[pid] = factor }
    fun effectiveIntervalOf(pid: Int): Long? = intervals[pid]?.let { (it * (stretch[pid] ?: 1.0)).toLong() }
    fun pids(): Set<Int> = intervals.keys.toSet()
    fun intervalOf(pid: Int): Long? = intervals[pid]

    private fun due(pid: Int): Long = (last[pid] ?: Long.MIN_VALUE / 2).let { if (it < 0) 0L else it + (effectiveIntervalOf(pid) ?: 0L) }

    /**
     * The PID to request at [now] among those [allowed], or null when nothing is due yet. Higher priority first, most overdue within
     * a priority; a PID that has waited more than two further intervals is served next, so optional sensors cannot be starved for good.
     */
    fun next(now: Long, allowed: (Int) -> Boolean = { true }): Int? {
        val ready = intervals.keys.filter(allowed).filter { due(it) <= now }
        if (ready.isEmpty()) return null
        val starved = ready.filter { now - due(it) >= 2 * maxOf(effectiveIntervalOf(it) ?: 0L, 1L) }
        if (starved.isNotEmpty()) return starved.minByOrNull { due(it) }
        return ready.sortedWith(compareBy({ priorityOf(it) }, { due(it) })).first()
    }

    fun markPolled(pid: Int, now: Long) { last[pid] = now }

    /** Milliseconds until something is due (0 if already due), or null when no PID is scheduled. */
    fun waitMs(now: Long, allowed: (Int) -> Boolean = { true }): Long? =
        intervals.keys.filter(allowed).minOfOrNull { due(it) }?.let { (it - now).coerceAtLeast(0L) }
}
