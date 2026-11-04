package com.sx4scanpro

internal enum class PidState { NOT_ADVERTISED, READY, TEMP_UNAVAILABLE }

/**
 * Tracks what each PID has actually done. A PID that the ECU advertised but keeps answering NO DATA
 * (while other PIDs answer) is parked and retried later; it is never disabled for good, and a PID the
 * ECU did not advertise is never polled at all.
 */
internal class PidCapabilities(private val noDataLimit: Int = 3, private val retryMs: Long = 30_000L) {
    private var advertised: Set<Int> = emptySet()
    private val streak = HashMap<Int, Int>()
    private val retryAt = HashMap<Int, Long>()

    fun setAdvertised(pids: Set<Int>) { advertised = pids; streak.clear(); retryAt.clear() }

    fun stateOf(pid: Int, now: Long): PidState = when {
        pid !in advertised -> PidState.NOT_ADVERTISED
        (retryAt[pid] ?: 0L) > now -> PidState.TEMP_UNAVAILABLE
        else -> PidState.READY
    }

    fun isPollable(pid: Int, now: Long) = stateOf(pid, now) == PidState.READY

    fun recordSuccess(pid: Int) { streak[pid] = 0; retryAt.remove(pid) }

    /** Call for a NO DATA reply only while other PIDs are answering; ECU-wide silence is not the PID's fault. */
    fun recordNoData(pid: Int, now: Long) {
        val n = (streak[pid] ?: 0) + 1
        streak[pid] = n
        if (n >= noDataLimit) retryAt[pid] = now + retryMs
    }
}
