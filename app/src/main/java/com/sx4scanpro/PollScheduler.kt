package com.sx4scanpro

/**
 * Earliest-deadline-first scheduling of Mode 01 PIDs. Fast PIDs come round often, slow ones rarely,
 * so the cockpit gauges refresh quickly without every sensor being read on every pass.
 * Pure logic: it only decides which PID is next; it never talks to the adapter.
 */
internal class PollScheduler {
    private val intervals = linkedMapOf<Int, Long>()
    private val last = HashMap<Int, Long>()

    fun set(pid: Int, intervalMs: Long) { intervals[pid] = intervalMs }
    fun remove(pid: Int) { intervals.remove(pid); last.remove(pid) }
    fun pids(): Set<Int> = intervals.keys.toSet()
    fun intervalOf(pid: Int): Long? = intervals[pid]

    private fun due(pid: Int): Long = (last[pid] ?: Long.MIN_VALUE / 2).let { if (it < 0) 0L else it + (intervals[pid] ?: 0L) }

    /** The PID most overdue at [now] among those [allowed], or null when nothing is due yet. */
    fun next(now: Long, allowed: (Int) -> Boolean = { true }): Int? =
        intervals.keys.filter(allowed).filter { due(it) <= now }.minByOrNull { due(it) }

    fun markPolled(pid: Int, now: Long) { last[pid] = now }

    /** Milliseconds until something is due (0 if already due), or null when no PID is scheduled. */
    fun waitMs(now: Long, allowed: (Int) -> Boolean = { true }): Long? =
        intervals.keys.filter(allowed).minOfOrNull { due(it) }?.let { (it - now).coerceAtLeast(0L) }
}
