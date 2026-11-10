package com.sx4scanpro

import java.util.concurrent.atomic.AtomicBoolean

/** Turns Mode 01 bitmap evidence into registry states. Only bitmap evidence from blocks that were actually read is used. */
internal object DiscoveryRegistry {
    data class Counts(val advertised: Int, val unsupported: Int, val inconclusive: Int, val rejected: Int)

    private fun hex(pid: Int) = "%02X".format(pid)
    private fun isBlockPid(pid: Int) = pid % 0x20 == 0

    fun apply(reads: List<BlockRead>, registry: ParameterRegistry, nowMs: Long): Counts {
        var adv = 0; var uns = 0; var inc = 0; var rej = 0
        fun count(t: Transition, onApplied: () -> Unit) { if (t.applied) onApplied() else rej++ }
        for (read in reads) {
            val note = "Mode 01 support block " + Mode01Blocks.command(read.base)
            val listed = read.pids
            for (pid in read.base + 1..read.base + 0x20) {
                if (isBlockPid(pid)) continue                       // 0x20, 0x40 ...: they point at the next bitmap, they are not parameters
                val inCatalog = PidCatalog.forPid(pid) != null
                val id = ParameterRegistry.idOf("01", hex(pid))
                when {
                    read.ok && pid in listed -> {
                        if (!inCatalog) registry.ensure("01", hex(pid))
                        if (registry.state(id) == ParamState.UNSUPPORTED) registry.reset(id, "support bitmap now lists it", nowMs)
                        count(registry.onAdvertised(id, Evidence(nowMs, EvidenceKind.BITMAP_ADVERTISED, note))) { adv++ }
                    }
                    // Reserved and unknown PIDs are never classified unsupported: only valid catalog PIDs, and only from a block that was read.
                    read.ok && inCatalog && registry.def(id) != null ->
                        count(registry.onUnsupported(id, Evidence(nowMs, EvidenceKind.BITMAP_NOT_ADVERTISED, note))) { uns++ }
                    !read.ok && inCatalog && registry.def(id) != null ->
                        count(registry.onInconclusive(id, Evidence(nowMs, Mode01Blocks.evidenceKind(read.kind), note + ": " + read.kind + " (" + read.elm + ")"))) { inc++ }
                }
            }
        }
        return Counts(adv, uns, inc, rej)
    }
}

internal enum class DiscoveryStatus { COMPLETE, PARTIAL, REFUSED, FAILED }

internal data class DiscoveryOutcome(
    val status: DiscoveryStatus,
    val message: String,
    val run: DiscoveryRun? = null,
    val counts: DiscoveryRegistry.Counts? = null,
    val saved: Boolean = false
)

/**
 * The one entry point for discovery, used at connect time and for any later manual request. Only one request runs at a
 * time (a second is refused, not queued). Live polling is paused for the duration and always resumed, whatever happens.
 */
internal class DiscoveryController(
    private val gate: PollGate,
    private val registry: () -> ParameterRegistry,
    private val persist: (ParameterRegistry) -> Boolean,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val pauseTimeoutMs: Long = PollGate.DEFAULT_PAUSE_MS,
    /** A reason not to start now (for example the ECU link is down or another scan is running), or null. */
    private val refusal: () -> String? = { null },
    private val discover: () -> DiscoveryRun
) {
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun request(reason: String): DiscoveryOutcome {
        if (!running.compareAndSet(false, true)) return refused("a discovery is already running")
        try {
            refusal()?.let { return refused(it) }
            if (!gate.pause(pauseTimeoutMs)) return refused("live polling did not pause in time")
            try {
                log("discovery started ($reason)")
                val run = discover()
                val counts = DiscoveryRegistry.apply(run.reads, registry(), clock())
                val saved = persist(registry())
                val restoreText = run.headerRestore?.let { if (it.restored) "headers restored" + (if (it.usedReinit) " via re-init" else "") else "HEADERS NOT RESTORED" } ?: "headers untouched"
                val msg = (if (run.complete) "complete" else "partial") + ": " + run.advertised.size + " PIDs advertised, blocks " +
                    run.reads.joinToString(",") { Mode01Blocks.command(it.base) + (if (it.ok) "" else "(" + it.kind + ")") } +
                    "; registry +" + counts.advertised + " advertised, " + counts.unsupported + " unsupported, " + counts.inconclusive + " inconclusive, " +
                    counts.rejected + " rejected" + (run.ecuAddress?.let { "; ecu=$it" } ?: "") + "; " + restoreText + (if (saved) "" else "; registry NOT saved")
                log("discovery $msg")
                return DiscoveryOutcome(if (run.complete) DiscoveryStatus.COMPLETE else DiscoveryStatus.PARTIAL, msg, run, counts, saved)
            } catch (e: Exception) {
                val msg = "discovery failed: " + (e.message ?: e.javaClass.simpleName)
                log(msg)
                return DiscoveryOutcome(DiscoveryStatus.FAILED, msg)
            } finally {
                gate.resume()
            }
        } finally {
            running.set(false)
        }
    }

    private fun refused(why: String): DiscoveryOutcome {
        log("discovery refused: $why")
        return DiscoveryOutcome(DiscoveryStatus.REFUSED, why)
    }
}
