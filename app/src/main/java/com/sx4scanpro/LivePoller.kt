package com.sx4scanpro

/** The single-command-at-a-time view of the adapter that the poller needs. */
internal interface ElmPort { fun send(command: String, timeoutMs: Long): ElmResult }

internal data class Reading(val value: Double, val atMs: Long)

internal sealed class PollEvent {
    data class Polled(val pid: Int, val reading: Reading?, val kind: ElmKind) : PollEvent()
    data class Recovery(val step: RecoveryStep, val recovered: Boolean) : PollEvent()
    object Idle : PollEvent()
}

/**
 * Drives one command at a time: reads the most overdue PID, or, while the ECU is silent, runs the next
 * recovery action. Readings carry their own timestamp so the UI can show their age; nothing is invented
 * and a failed read leaves the previous reading (with its old timestamp) rather than a fake fresh one.
 */
internal class LivePoller(
    private val port: ElmPort,
    val scheduler: PollScheduler,
    val capabilities: PidCapabilities,
    val monitor: LinkMonitor = LinkMonitor(),
    private val lightReinit: List<String> = listOf("ATPC"),
    private val fullReinit: List<String> = listOf("ATZ"),
    private val pidTimeoutMs: Long = 2500L
) {
    private val readings = HashMap<Int, Reading>()
    fun reading(pid: Int): Reading? = readings[pid]

    fun step(now: Long): PollEvent {
        if (monitor.state != LinkState.COMMUNICATING) return recover(now)
        val pid = scheduler.next(now) { capabilities.isPollable(it, now) } ?: return PollEvent.Idle
        val spec = PidCatalog.forPid(pid)
        scheduler.markPolled(pid, now)
        if (spec == null) return PollEvent.Polled(pid, null, ElmKind.UNKNOWN_COMMAND)
        val res = port.send(spec.command, pidTimeoutMs)
        val value = if (res.kind == ElmKind.DATA) PidCatalog.decode(spec, ElmReply.stripBanners(res.raw)) else null
        monitor.onResult(res.kind, res.timedOut)
        return if (value != null) {
            val r = Reading(value, now)
            readings[pid] = r
            capabilities.recordSuccess(pid)
            monitor.onGood(now)
            PollEvent.Polled(pid, r, res.kind)
        } else {
            if (res.kind == ElmKind.NO_DATA && monitor.state == LinkState.COMMUNICATING && now - monitor.lastGoodMs < 3000L) capabilities.recordNoData(pid, now)
            PollEvent.Polled(pid, null, res.kind)
        }
    }

    private fun recover(now: Long): PollEvent {
        val action = monitor.nextRecovery()
        when (action.step) {
            RecoveryStep.LIGHT_REINIT -> lightReinit.forEach { port.send(it, 3000L) }
            RecoveryStep.FULL_REINIT -> fullReinit.forEach { port.send(it, 5000L) }
            RecoveryStep.PROBE -> Unit
        }
        val probe = port.send("0100", 6000L)
        val ok = probe.kind == ElmKind.DATA && ElmReply.stripBanners(probe.raw).startsWith("4100")
        if (ok) monitor.onGood(now)
        return PollEvent.Recovery(action.step, ok)
    }

    /** How long the caller should pause before the next step (recovery back-off, or time to the next due PID). */
    fun pauseMs(now: Long): Long =
        if (monitor.state != LinkState.COMMUNICATING) 1000L
        else (scheduler.waitMs(now) { capabilities.isPollable(it, now) } ?: 100L).coerceIn(0L, 250L)
}
