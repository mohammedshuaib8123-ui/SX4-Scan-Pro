package com.sx4scanpro

/** What one request turned out to be, as far as pacing and statistics are concerned. */
internal enum class PollOutcome { OK, NO_DATA, TIMEOUT, MALFORMED, OTHER_ERROR }

internal data class PidPerf(
    val pid: Int, val requests: Int, val avgMs: Double, val medianMs: Double, val p95Ms: Double,
    val timeouts: Int, val noData: Int, val malformed: Int, val consecutiveFailures: Int, val avgUpdateMs: Double
)

private class Ring(private val cap: Int) {
    private val v = LongArray(cap)
    var size = 0; private set
    private var pos = 0
    fun add(x: Long) { v[pos] = x; pos = (pos + 1) % cap; if (size < cap) size++ }
    fun sorted(): LongArray = v.copyOf(size).also { it.sort() }
}

/**
 * Lightweight, bounded measurements of live polling: fixed-size windows per PID (default 64 samples), plain counters, no files and
 * no network. Everything here is observed from real replies; it never invents a number.
 */
internal class PollMetrics(private val window: Int = 64) {
    private class Pid(window: Int) {
        val rtt = Ring(window); val updates = Ring(window)
        var requests = 0; var timeouts = 0; var noData = 0; var malformed = 0; var consecutive = 0; var lastOkMs = -1L
    }
    private val pids = HashMap<Int, Pid>()
    private val cycles = Ring(window)
    private var cycleStart = -1L
    private val seen = HashSet<Int>()

    private fun of(pid: Int) = pids.getOrPut(pid) { Pid(window) }

    fun record(pid: Int, outcome: PollOutcome, durationMs: Long, now: Long) {
        val p = of(pid)
        p.requests++
        p.rtt.add(durationMs.coerceAtLeast(0L))
        when (outcome) {
            PollOutcome.OK -> { p.consecutive = 0; if (p.lastOkMs >= 0) p.updates.add(now - p.lastOkMs); p.lastOkMs = now }
            PollOutcome.NO_DATA -> { p.noData++; p.consecutive++ }
            PollOutcome.TIMEOUT -> { p.timeouts++; p.consecutive++ }
            PollOutcome.MALFORMED -> { p.malformed++; p.consecutive++ }
            PollOutcome.OTHER_ERROR -> p.consecutive++
        }
    }

    /** A cycle is one pass in which every PID in [active] was requested at least once; its length is measured start to start. */
    fun cycleTick(pid: Int, active: Set<Int>, now: Long) {
        if (cycleStart < 0) { cycleStart = now; seen.clear() }
        seen.add(pid)
        if (active.isNotEmpty() && seen.containsAll(active)) {
            cycles.add(now - cycleStart); cycleStart = now; seen.clear(); seen.add(pid)
        }
    }

    fun consecutiveFailures(pid: Int): Int = pids[pid]?.consecutive ?: 0
    fun medianMs(pid: Int): Double? = pids[pid]?.rtt?.let { median(it.sorted()) }

    fun forPid(pid: Int): PidPerf? = pids[pid]?.let { p ->
        val s = p.rtt.sorted(); val u = p.updates.sorted()
        PidPerf(pid, p.requests, avg(s), median(s), percentile(s, 95), p.timeouts, p.noData, p.malformed, p.consecutive, avg(u))
    }
    fun allPids(): List<Int> = pids.keys.sorted()
    fun cycleAvgMs(): Double = avg(cycles.sorted())
    fun cycleCount(): Int = cycles.size

    /** One compact line per PID for the session log. */
    fun summary(): List<String> = allPids().mapNotNull { forPid(it) }.map { f ->
        "perf PID %02X: n=%d avg=%.0f med=%.0f p95=%.0f ms, update=%.0f ms, timeout=%d nodata=%d malformed=%d fails=%d".format(
            java.util.Locale.US, f.pid, f.requests, f.avgMs, f.medianMs, f.p95Ms, f.avgUpdateMs, f.timeouts, f.noData, f.malformed, f.consecutiveFailures)
    } + "perf cycle: avg=%.0f ms over %d cycles".format(java.util.Locale.US, cycleAvgMs(), cycleCount())

    companion object {
        fun avg(s: LongArray): Double = if (s.isEmpty()) 0.0 else s.sum().toDouble() / s.size
        fun median(s: LongArray): Double = when {
            s.isEmpty() -> 0.0
            s.size % 2 == 1 -> s[s.size / 2].toDouble()
            else -> (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
        /** Nearest-rank percentile of an ascending array. */
        fun percentile(s: LongArray, pct: Int): Double =
            if (s.isEmpty()) 0.0 else s[(Math.ceil(pct / 100.0 * s.size).toInt() - 1).coerceIn(0, s.size - 1)].toDouble()
    }
}

/**
 * Conservative adaptive pacing on top of [PollScheduler]. It only ever slows things down, never below the plan:
 * - a PID that fails on its own backs off (x2 per further failure, capped), and recovers as soon as it answers;
 * - when the measured bus time of the plan is more than [budget] of wall time, optional PIDs are stretched (at most x2);
 *   RPM, speed, coolant and load are never stretched for load.
 */
internal class AdaptivePacer(
    private val scheduler: PollScheduler,
    private val metrics: PollMetrics,
    private val budget: Double = 0.8,
    private val maxLoadStretch: Double = 2.0
) {
    private val backoff = HashMap<Int, Double>()
    private val load = HashMap<Int, Double>()

    fun onResult(pid: Int, outcome: PollOutcome, pidFailedAlone: Boolean) {
        val n = metrics.consecutiveFailures(pid)
        if (outcome == PollOutcome.OK || !pidFailedAlone || n < 2) backoff.remove(pid)
        else {
            val cap = if (scheduler.priorityOf(pid) <= 1) 4.0 else 8.0
            backoff[pid] = minOf(Math.pow(2.0, (n - 1).toDouble()), cap)
        }
        rebalance()
    }

    /** Forget all backoff (after the ECU link was recovered: earlier failures were the link's, not the PID's). */
    fun reset() { backoff.clear(); load.clear(); scheduler.pids().forEach { scheduler.setStretch(it, 1.0) } }

    private fun rebalance() {
        val pids = scheduler.pids()
        var demand = 0.0
        pids.forEach { p ->
            val m = metrics.medianMs(p) ?: return@forEach
            val iv = scheduler.intervalOf(p) ?: return@forEach
            demand += m / iv
        }
        load.clear()
        if (demand > budget) {
            val f = minOf(maxLoadStretch, demand / budget)
            pids.filter { scheduler.priorityOf(it) >= 2 }.forEach { load[it] = f }
        }
        pids.forEach { p -> scheduler.setStretch(p, maxOf(backoff[p] ?: 1.0, load[p] ?: 1.0)) }
    }
}
