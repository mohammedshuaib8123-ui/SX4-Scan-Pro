package com.sx4scanpro

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Delay before attempt n (1-based): [baseMs] doubled each time, never more than [maxMs]. */
internal data class Backoff(val baseMs: Long, val maxMs: Long) {
    fun delayMs(attempt: Int): Long {
        var d = baseMs
        repeat((attempt - 1).coerceAtLeast(0)) { d = minOf(maxMs, d * 2) }
        return minOf(d, maxMs)
    }
}

internal data class RecoveryPolicy(
    /** Socket reconnect attempts before giving up for good (the user must tap Connect). */
    val bluetoothAttempts: Int = 5,
    val bluetoothBackoff: Backoff = Backoff(2_000L, 30_000L),
    /** Quick ECU recovery attempts, then slow standby probing. */
    val ecuAttempts: Int = 10,
    val ecuBackoff: Backoff = Backoff(1_000L, 10_000L),
    /** Gap between probes once the quick attempts are used up: slow, never a rapid loop. */
    val standbyMs: Long = 30_000L,
    /** Failed recovery attempts with the adapter itself silent before the Bluetooth link is rebuilt. */
    val adapterEscalateAfter: Int = 3,
    /** No adapter transaction at all for this long while live means polling is stuck. */
    val stallMs: Long = 20_000L
)

/** Turns adapter results and exceptions into a [FailureKind]. A single NO DATA is not a failure and never a Bluetooth one. */
internal object FailureClassifier {
    fun fromResult(res: ElmResult, adapterCommand: Boolean): FailureKind? = when {
        res.timedOut -> if (adapterCommand) FailureKind.ADAPTER_TIMEOUT else FailureKind.ELM_TIMEOUT
        res.kind == ElmKind.NO_DATA -> null
        res.kind == ElmKind.DATA || res.kind == ElmKind.OK -> null
        ElmReply.isEcuSilent(res.kind) -> FailureKind.ECU_COMMUNICATION_FAILURE
        res.kind == ElmKind.EMPTY -> if (adapterCommand) FailureKind.ADAPTER_TIMEOUT else FailureKind.ELM_TIMEOUT
        else -> FailureKind.UNEXPECTED_ADAPTER_RESPONSE
    }

    /** What the link monitor's verdict means once it has seen enough results. */
    fun fromLinkState(state: LinkState): FailureKind? = when (state) {
        LinkState.COMMUNICATING -> null
        LinkState.ECU_SILENT -> FailureKind.REPEATED_NO_DATA
        LinkState.ADAPTER_SILENT -> FailureKind.ADAPTER_TIMEOUT
    }

    /** An exception from the adapter port: during set-up it is an interrupted initialisation, afterwards a lost socket. */
    fun fromException(@Suppress("UNUSED_PARAMETER") e: Exception, state: ConnectionState): FailureKind = when (state) {
        ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.INITIALIZING_ADAPTER, ConnectionState.ECU_INITIALIZING -> FailureKind.INITIALIZATION_INTERRUPTED
        else -> FailureKind.BLUETOOTH_DISCONNECTED
    }
}

/** Detects a stuck poll loop: no adapter transaction completed for too long while the link should be live. */
internal class StallWatchdog(private val stallMs: Long) {
    @Volatile private var lastProgressMs = 0L
    @Volatile private var armed = false

    fun arm(nowMs: Long) { lastProgressMs = nowMs; armed = true }
    fun disarm() { armed = false }
    fun progress(nowMs: Long) { lastProgressMs = nowMs }
    fun isStalled(nowMs: Long): Boolean = armed && nowMs - lastProgressMs > stallMs
}

/** What [RecoverySupervisor] needs from the platform. The Android side supplies the real socket; tests supply a fake. */
internal interface RecoveryPorts {
    /** Close the Bluetooth socket and release everything on it. Safe to call when already closed. */
    fun closeSocket()
    /** Open the socket again and run the normal set-up (adapter init, protocol/header restore, ECU handshake). True only when the ECU answered. Throws IOException on failure. */
    fun reconnect(): Boolean
    fun pausePolling(): Boolean
    fun resumePolling()
    fun sleep(ms: Long)
    /** The user chose Disconnect: stop retrying. */
    fun aborted(): Boolean
    fun now(): Long
    fun log(message: String)
}

internal enum class EcuAction { NONE, RECONNECT_BLUETOOTH }

/**
 * Drives recovery on top of [ConnectionMachine]. Two layers: an ECU-only loss (ignition off) keeps the Bluetooth socket and retries the ECU
 * with back-off, then slow standby probes, so readings resume by themselves when the ignition comes back; a Bluetooth loss closes the
 * stale socket and reconnects a bounded number of times. Never two recoveries at once.
 */
internal class RecoverySupervisor(
    private val machine: ConnectionMachine,
    private val ports: RecoveryPorts,
    private val policy: RecoveryPolicy = RecoveryPolicy()
) {
    private val busy = AtomicBoolean(false)
    private var ecuAttempts = 0
    private var adapterAttempts = 0

    val snapshot: ConnectionSnapshot get() = machine.snapshot

    /** True while a Bluetooth recovery runs. */
    fun isRecovering(): Boolean = busy.get()

    /**
     * Rebuilds the Bluetooth link. Returns true when the ECU answers again. A second call while one runs returns false immediately.
     * Polling is paused for the duration and always resumed.
     */
    fun recoverBluetooth(kind: FailureKind, detail: String): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        val reason = (ConnectionPresenter.failureText(kind) + (if (detail.isBlank()) "" else " ($detail)"))
        val paused = ports.pausePolling()
        try {
            if (!machine.recoveryStarted(kind, reason, RecoveryLayer.BLUETOOTH, policy.bluetoothAttempts)) return false
            ports.log("recovery: $reason")
            var last = reason
            for (attempt in 1..policy.bluetoothAttempts) {
                ports.closeSocket()
                val wait = policy.bluetoothBackoff.delayMs(attempt)
                machine.recoveryAttempt(attempt, wait)
                ports.sleep(wait)
                if (ports.aborted()) { ports.closeSocket(); machine.userDisconnected(); return false }
                try {
                    if (ports.reconnect()) {
                        if (ports.aborted()) { ports.closeSocket(); machine.userDisconnected(); return false }
                        machine.recovered(ports.now())
                        ecuAttempts = 0; adapterAttempts = 0
                        ports.log("recovery: Bluetooth link restored on attempt $attempt")
                        return true
                    }
                    last = "the ECU did not answer after reconnecting"
                } catch (e: IOException) {
                    last = e.message ?: "reconnect failed"
                } catch (e: SecurityException) {
                    last = "Bluetooth permission was withdrawn"
                    break
                }
                ports.log("recovery attempt $attempt failed: $last")
                if (ports.aborted()) { ports.closeSocket(); machine.userDisconnected(); return false }
            }
            ports.closeSocket()
            machine.gaveUp(kind, "Could not reconnect after ${policy.bluetoothAttempts} attempts: $last", RecoveryLayer.NONE)
            ports.log("recovery gave up: $last")
            return false
        } finally {
            if (paused) ports.resumePolling()
            busy.set(false)
        }
    }

    /**
     * Called after every poll slice with the link monitor's verdict. Keeps the machine in step with the ECU without touching the socket.
     * Returns [EcuAction.RECONNECT_BLUETOOTH] when the adapter itself stays silent so long that the socket should be rebuilt.
     */
    fun observeEcu(link: LinkState, recoveryAttempted: Boolean, recovered: Boolean, nowMs: Long): EcuAction {
        val s = machine.snapshot
        if (link == LinkState.COMMUNICATING) {
            if (s.layer == RecoveryLayer.ECU && (s.state == ConnectionState.RECOVERING || s.state == ConnectionState.ERROR)) {
                machine.recovered(nowMs)
                ports.log("ECU communication restored")
            }
            ecuAttempts = 0; adapterAttempts = 0
            machine.noteGood(nowMs)
            return EcuAction.NONE
        }
        if (s.state == ConnectionState.READY) {
            val kind = FailureClassifier.fromLinkState(link) ?: return EcuAction.NONE
            machine.recoveryStarted(kind, ConnectionPresenter.failureText(kind), RecoveryLayer.ECU, policy.ecuAttempts)
            ecuAttempts = 0; adapterAttempts = 0
            ports.log("ECU link lost: " + ConnectionPresenter.failureText(kind))
        }
        if (recoveryAttempted && !recovered) {
            ecuAttempts++
            if (link == LinkState.ADAPTER_SILENT && ++adapterAttempts >= policy.adapterEscalateAfter) return EcuAction.RECONNECT_BLUETOOTH
            val now = machine.snapshot
            if (now.state == ConnectionState.RECOVERING) {
                if (ecuAttempts >= policy.ecuAttempts) {
                    machine.gaveUp(FailureKind.ECU_COMMUNICATION_FAILURE, "The ECU is not responding. Turn the ignition on; checking again every ${policy.standbyMs / 1000} s.", RecoveryLayer.ECU, policy.standbyMs)
                    ports.log("ECU recovery: quick attempts used up, standby probing every ${policy.standbyMs / 1000} s")
                } else machine.recoveryAttempt(ecuAttempts, policy.ecuBackoff.delayMs(ecuAttempts + 1))
            }
        }
        return EcuAction.NONE
    }

    /** How long the poll loop should wait before the next recovery step while the ECU is down; 0 means "no recovery pacing". */
    fun ecuPauseMs(): Long {
        val s = machine.snapshot
        if (s.layer != RecoveryLayer.ECU) return 0L
        return when (s.state) {
            ConnectionState.RECOVERING -> policy.ecuBackoff.delayMs(ecuAttempts + 1)
            ConnectionState.ERROR -> policy.standbyMs
            else -> 0L
        }
    }
}
