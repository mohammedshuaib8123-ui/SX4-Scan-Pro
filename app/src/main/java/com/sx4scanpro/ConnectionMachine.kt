package com.sx4scanpro

/** Where the link to the car is in its life. Bluetooth, adapter and ECU are told apart in [ConnectionSnapshot]. */
internal enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, INITIALIZING_ADAPTER, ECU_INITIALIZING, READY, RECOVERING, ERROR }

/** Why the link was lost or never came up. Only [BLUETOOTH_DISCONNECTED], [POLLING_STALLED] and [INITIALIZATION_INTERRUPTED] are about the socket; NO DATA alone never is. */
internal enum class FailureKind {
    BLUETOOTH_DISCONNECTED, ADAPTER_TIMEOUT, ELM_TIMEOUT, REPEATED_NO_DATA, ECU_COMMUNICATION_FAILURE,
    UNEXPECTED_ADAPTER_RESPONSE, POLLING_STALLED, INITIALIZATION_INTERRUPTED
}

/** Which layer is being recovered: the Bluetooth socket (and everything on it) or only the ECU behind a healthy adapter. */
internal enum class RecoveryLayer { NONE, BLUETOOTH, ECU }

internal data class ConnectionSnapshot(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val failure: FailureKind? = null,
    val layer: RecoveryLayer = RecoveryLayer.NONE,
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
    val nextRetryInMs: Long = 0L,
    val reason: String = "",
    val lastGoodMs: Long = 0L,
    val everReady: Boolean = false
) {
    /** The Bluetooth socket is usable. An ECU-only problem leaves this true. */
    val bluetoothUp: Boolean
        get() = when (state) {
            ConnectionState.CONNECTED, ConnectionState.INITIALIZING_ADAPTER, ConnectionState.ECU_INITIALIZING, ConnectionState.READY -> true
            ConnectionState.RECOVERING, ConnectionState.ERROR -> layer == RecoveryLayer.ECU
            else -> false
        }

    /** Live readings may be trusted only now. */
    val ecuAvailable: Boolean get() = state == ConnectionState.READY

    /** A connection attempt or recovery owns the adapter: a second one must not start. */
    val busy: Boolean
        get() = state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED ||
            state == ConnectionState.INITIALIZING_ADAPTER || state == ConnectionState.ECU_INITIALIZING ||
            (state == ConnectionState.RECOVERING && layer == RecoveryLayer.BLUETOOTH)
}

/**
 * The one place that decides what state the connection is in. Transitions are table-driven and deterministic; an illegal one is refused
 * (returns false) and changes nothing. It performs no I/O: callers report what happened and read [snapshot].
 */
internal class ConnectionMachine(private val onChange: (old: ConnectionSnapshot, new: ConnectionSnapshot) -> Unit = { _, _ -> }) {
    @Volatile var snapshot: ConnectionSnapshot = ConnectionSnapshot()
        private set

    companion object {
        private val S = ConnectionState.values()
        /** Every state may also go to DISCONNECTED (the user disconnects at any time). */
        val allowed: Map<ConnectionState, Set<ConnectionState>> = mapOf(
            ConnectionState.DISCONNECTED to setOf(ConnectionState.CONNECTING),
            ConnectionState.CONNECTING to setOf(ConnectionState.CONNECTED, ConnectionState.ERROR),
            ConnectionState.CONNECTED to setOf(ConnectionState.INITIALIZING_ADAPTER, ConnectionState.ERROR),
            ConnectionState.INITIALIZING_ADAPTER to setOf(ConnectionState.ECU_INITIALIZING, ConnectionState.ERROR),
            ConnectionState.ECU_INITIALIZING to setOf(ConnectionState.READY, ConnectionState.ERROR),
            ConnectionState.READY to setOf(ConnectionState.RECOVERING, ConnectionState.ERROR),
            ConnectionState.RECOVERING to setOf(ConnectionState.RECOVERING, ConnectionState.READY, ConnectionState.ERROR),
            ConnectionState.ERROR to setOf(ConnectionState.CONNECTING, ConnectionState.RECOVERING, ConnectionState.READY)
        ).mapValues { (_, v) -> v + ConnectionState.DISCONNECTED }
        fun isAllowed(from: ConnectionState, to: ConnectionState): Boolean = to in allowed.getValue(from)
    }

    @Synchronized
    private fun move(to: ConnectionState, change: (ConnectionSnapshot) -> ConnectionSnapshot): Boolean {
        val old = snapshot
        if (!isAllowed(old.state, to)) return false
        val new = change(old).copy(state = to)
        snapshot = new
        onChange(old, new)
        return true
    }

    @Synchronized
    private fun update(change: (ConnectionSnapshot) -> ConnectionSnapshot) {
        val old = snapshot
        snapshot = change(old)
        onChange(old, snapshot)
    }

    // ---- first connection ----
    fun connectRequested(): Boolean = move(ConnectionState.CONNECTING) { it.copy(failure = null, layer = RecoveryLayer.NONE, attempt = 0, maxAttempts = 0, nextRetryInMs = 0, reason = "") }
    fun socketOpened(): Boolean = move(ConnectionState.CONNECTED) { it }
    fun adapterInitializing(): Boolean = move(ConnectionState.INITIALIZING_ADAPTER) { it }
    fun ecuInitializing(): Boolean = move(ConnectionState.ECU_INITIALIZING) { it }
    fun ready(nowMs: Long): Boolean = move(ConnectionState.READY) {
        it.copy(failure = null, layer = RecoveryLayer.NONE, attempt = 0, maxAttempts = 0, nextRetryInMs = 0, reason = "", lastGoodMs = nowMs, everReady = true)
    }

    /** The first connection did not come up. No automatic retry: the reason is shown and the user decides. */
    fun connectFailed(kind: FailureKind, reason: String): Boolean = move(ConnectionState.ERROR) { it.copy(failure = kind, layer = RecoveryLayer.NONE, reason = reason) }

    // ---- while connected ----
    /** Live data arrived. Only moves the last-good time. */
    fun noteGood(nowMs: Long) { if (snapshot.state == ConnectionState.READY) update { it.copy(lastGoodMs = nowMs) } }

    fun recoveryStarted(kind: FailureKind, reason: String, layer: RecoveryLayer, maxAttempts: Int): Boolean =
        move(ConnectionState.RECOVERING) { it.copy(failure = kind, layer = layer, attempt = 0, maxAttempts = maxAttempts, nextRetryInMs = 0, reason = reason) }

    fun recoveryAttempt(attempt: Int, nextRetryInMs: Long) {
        if (snapshot.state == ConnectionState.RECOVERING) update { it.copy(attempt = attempt, nextRetryInMs = nextRetryInMs) }
    }

    fun recovered(nowMs: Long): Boolean = move(ConnectionState.READY) {
        it.copy(failure = null, layer = RecoveryLayer.NONE, attempt = 0, maxAttempts = 0, nextRetryInMs = 0, reason = "", lastGoodMs = nowMs)
    }

    /** Recovery stopped trying quickly. For [RecoveryLayer.ECU] the Bluetooth link stays up and slow standby probing continues. */
    fun gaveUp(kind: FailureKind, reason: String, layer: RecoveryLayer, nextRetryInMs: Long = 0L): Boolean =
        move(ConnectionState.ERROR) { it.copy(failure = kind, layer = layer, reason = reason, nextRetryInMs = nextRetryInMs) }

    fun userDisconnected(): Boolean = move(ConnectionState.DISCONNECTED) { it.copy(failure = null, layer = RecoveryLayer.NONE, attempt = 0, maxAttempts = 0, nextRetryInMs = 0, reason = "") }
}

/** Plain-language text for the cockpit chip and the ECU Information connection section. */
internal object ConnectionPresenter {
    fun failureText(kind: FailureKind?): String = when (kind) {
        FailureKind.BLUETOOTH_DISCONNECTED -> "Bluetooth link to the adapter was lost"
        FailureKind.ADAPTER_TIMEOUT -> "The adapter stopped answering"
        FailureKind.ELM_TIMEOUT -> "The adapter did not finish a request in time"
        FailureKind.REPEATED_NO_DATA -> "The ECU stopped answering (ignition off?)"
        FailureKind.ECU_COMMUNICATION_FAILURE -> "The ECU is not responding"
        FailureKind.UNEXPECTED_ADAPTER_RESPONSE -> "The adapter sent an unexpected reply"
        FailureKind.POLLING_STALLED -> "Live polling stalled"
        FailureKind.INITIALIZATION_INTERRUPTED -> "Setting up the adapter was interrupted"
        null -> ""
    }

    fun chip(s: ConnectionSnapshot): String = when (s.state) {
        ConnectionState.DISCONNECTED -> "ECU DISCONNECTED"
        ConnectionState.CONNECTING -> "CONNECTING…"
        ConnectionState.CONNECTED, ConnectionState.INITIALIZING_ADAPTER -> "SETTING UP ADAPTER…"
        ConnectionState.ECU_INITIALIZING -> "WAKING ECU…"
        ConnectionState.READY -> "ECU LINK ACTIVE"
        ConnectionState.RECOVERING -> if (s.layer == RecoveryLayer.BLUETOOTH) "RECONNECTING…" else "ECU NOT RESPONDING"
        ConnectionState.ERROR -> if (s.layer == RecoveryLayer.ECU) "WAITING FOR ECU" else "LINK ERROR"
    }

    fun bluetooth(s: ConnectionSnapshot): String = when {
        s.state == ConnectionState.CONNECTING -> "Connecting"
        s.state == ConnectionState.RECOVERING && s.layer == RecoveryLayer.BLUETOOTH -> "Lost, reconnecting"
        s.state == ConnectionState.ERROR && !s.bluetoothUp -> "Not connected"
        s.bluetoothUp -> "Connected"
        else -> "Not connected"
    }

    fun adapter(s: ConnectionSnapshot): String = when (s.state) {
        ConnectionState.CONNECTED, ConnectionState.INITIALIZING_ADAPTER -> "Initialising"
        ConnectionState.ECU_INITIALIZING, ConnectionState.READY -> "Ready"
        ConnectionState.RECOVERING, ConnectionState.ERROR -> if (s.bluetoothUp && s.failure != FailureKind.ADAPTER_TIMEOUT && s.failure != FailureKind.ELM_TIMEOUT) "Ready" else "Not ready"
        else -> "Not ready"
    }

    fun ecu(s: ConnectionSnapshot): String = when (s.state) {
        ConnectionState.READY -> "Responding"
        ConnectionState.ECU_INITIALIZING -> "Starting"
        ConnectionState.RECOVERING -> if (s.layer == RecoveryLayer.ECU) "Not responding, retrying" else "Unknown while reconnecting"
        ConnectionState.ERROR -> if (s.layer == RecoveryLayer.ECU) "Not responding, waiting for ignition" else "Not available"
        else -> "Not available"
    }

    fun recovery(s: ConnectionSnapshot): String = when {
        s.state == ConnectionState.RECOVERING ->
            "Attempt " + s.attempt + " of " + s.maxAttempts + (if (s.nextRetryInMs > 0) ", next in " + (s.nextRetryInMs + 999) / 1000 + " s" else "")
        s.state == ConnectionState.ERROR && s.layer == RecoveryLayer.ECU -> "Checking again every " + (s.nextRetryInMs / 1000) + " s"
        s.state == ConnectionState.ERROR -> "Stopped: " + s.reason.ifBlank { failureText(s.failure) }
        else -> "None"
    }

    fun lastCommunication(s: ConnectionSnapshot, nowMs: Long): String =
        if (s.lastGoodMs <= 0L) "none yet" else ((nowMs - s.lastGoodMs).coerceAtLeast(0L) / 1000).toString() + " s ago"

    /** Label/value rows for the ECU Information "connection" section. */
    fun rows(s: ConnectionSnapshot, nowMs: Long): List<Pair<String, String>> = buildList {
        add("Bluetooth" to bluetooth(s))
        add("Adapter" to adapter(s))
        add("ECU" to ecu(s))
        add("Recovery" to recovery(s))
        add("Last communication" to lastCommunication(s, nowMs))
        if (s.failure != null) add("Reason" to (s.reason.ifBlank { failureText(s.failure) }))
    }
}
