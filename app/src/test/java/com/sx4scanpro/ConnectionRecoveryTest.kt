package com.sx4scanpro

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

private class FakePorts : RecoveryPorts {
    var clock = 1_000L
    val events = mutableListOf<String>()
    val sleeps = mutableListOf<Long>()
    var socketOpen = true
    var sockets = 1
    var paused = false
    var abortAfterSleeps = Int.MAX_VALUE
    /** One entry per reconnect call: true = ECU answers, false = ECU silent, null = IOException. */
    var script: MutableList<Boolean?> = mutableListOf()
    var onReconnect: () -> Unit = {}
    override fun closeSocket() { if (socketOpen) sockets--; socketOpen = false; events += "close" }
    override fun reconnect(): Boolean {
        onReconnect()
        events += "reconnect"
        val r = if (script.isEmpty()) null else script.removeAt(0)
        if (r == null) throw IOException("rfcomm failed")
        socketOpen = true; sockets++
        return r
    }
    override fun pausePolling(): Boolean { paused = true; events += "pause"; return true }
    override fun resumePolling() { paused = false; events += "resume" }
    override fun sleep(ms: Long) { sleeps += ms; clock += ms }
    override fun aborted(): Boolean = sleeps.size >= abortAfterSleeps
    override fun now(): Long = clock
    override fun log(message: String) { events += "log:$message" }
}

private fun readyMachine(): ConnectionMachine = ConnectionMachine().also {
    it.connectRequested(); it.socketOpened(); it.adapterInitializing(); it.ecuInitializing(); it.ready(1_000L)
}

class ConnectionMachineTest {
    @Test fun happyPathWalksEveryStage() {
        val m = ConnectionMachine()
        assertEquals(ConnectionState.DISCONNECTED, m.snapshot.state)
        assertTrue(m.connectRequested()); assertEquals(ConnectionState.CONNECTING, m.snapshot.state)
        assertTrue(m.socketOpened()); assertEquals(ConnectionState.CONNECTED, m.snapshot.state)
        assertTrue(m.adapterInitializing()); assertEquals(ConnectionState.INITIALIZING_ADAPTER, m.snapshot.state)
        assertTrue(m.ecuInitializing()); assertEquals(ConnectionState.ECU_INITIALIZING, m.snapshot.state)
        assertTrue(m.ready(5L)); assertEquals(ConnectionState.READY, m.snapshot.state)
        assertTrue(m.snapshot.ecuAvailable); assertTrue(m.snapshot.bluetoothUp)
        assertEquals(5L, m.snapshot.lastGoodMs)
    }

    @Test fun everyTransitionMatchesTheTable() {
        for (from in ConnectionState.values()) for (to in ConnectionState.values()) {
            val m = ConnectionMachine()
            walkTo(m, from)
            assertEquals(from, m.snapshot.state)
            val expected = ConnectionMachine.isAllowed(from, to)
            val moved = when (to) {
                ConnectionState.DISCONNECTED -> m.userDisconnected()
                ConnectionState.CONNECTING -> m.connectRequested()
                ConnectionState.CONNECTED -> m.socketOpened()
                ConnectionState.INITIALIZING_ADAPTER -> m.adapterInitializing()
                ConnectionState.ECU_INITIALIZING -> m.ecuInitializing()
                ConnectionState.READY -> m.ready(1L)
                ConnectionState.RECOVERING -> m.recoveryStarted(FailureKind.ELM_TIMEOUT, "x", RecoveryLayer.ECU, 3)
                ConnectionState.ERROR -> m.connectFailed(FailureKind.INITIALIZATION_INTERRUPTED, "x")
            }
            assertEquals("$from -> $to", expected, moved)
            assertEquals("$from -> $to state", if (expected) to else from, m.snapshot.state)
        }
    }

    private fun walkTo(m: ConnectionMachine, s: ConnectionState) {
        when (s) {
            ConnectionState.DISCONNECTED -> {}
            ConnectionState.CONNECTING -> m.connectRequested()
            ConnectionState.CONNECTED -> { m.connectRequested(); m.socketOpened() }
            ConnectionState.INITIALIZING_ADAPTER -> { walkTo(m, ConnectionState.CONNECTED); m.adapterInitializing() }
            ConnectionState.ECU_INITIALIZING -> { walkTo(m, ConnectionState.INITIALIZING_ADAPTER); m.ecuInitializing() }
            ConnectionState.READY -> { walkTo(m, ConnectionState.ECU_INITIALIZING); m.ready(1L) }
            ConnectionState.RECOVERING -> { walkTo(m, ConnectionState.READY); m.recoveryStarted(FailureKind.ELM_TIMEOUT, "x", RecoveryLayer.ECU, 3) }
            ConnectionState.ERROR -> { m.connectRequested(); m.connectFailed(FailureKind.INITIALIZATION_INTERRUPTED, "x") }
        }
    }

    @Test fun illegalTransitionChangesNothing() {
        val m = ConnectionMachine()
        assertFalse(m.ready(1L))
        assertEquals(ConnectionState.DISCONNECTED, m.snapshot.state)
        assertFalse(m.recoveryStarted(FailureKind.ELM_TIMEOUT, "x", RecoveryLayer.ECU, 3))
    }

    @Test fun failedConnectKeepsReasonAndAllowsRetry() {
        val m = ConnectionMachine()
        m.connectRequested(); m.socketOpened(); m.adapterInitializing()
        assertTrue(m.connectFailed(FailureKind.INITIALIZATION_INTERRUPTED, "ATZ timed out"))
        assertEquals("ATZ timed out", m.snapshot.reason)
        assertFalse(m.snapshot.bluetoothUp)
        assertTrue(m.connectRequested())
        assertEquals("", m.snapshot.reason)
    }

    @Test fun layersAreDistinct() {
        val m = readyMachine()
        m.recoveryStarted(FailureKind.REPEATED_NO_DATA, "x", RecoveryLayer.ECU, 10)
        assertTrue(m.snapshot.bluetoothUp); assertFalse(m.snapshot.ecuAvailable); assertFalse(m.snapshot.busy)
        m.recovered(2L)
        m.recoveryStarted(FailureKind.BLUETOOTH_DISCONNECTED, "x", RecoveryLayer.BLUETOOTH, 5)
        assertFalse(m.snapshot.bluetoothUp); assertTrue(m.snapshot.busy)
    }

    @Test fun listenerSeesEveryChange() {
        val seen = mutableListOf<ConnectionState>()
        val m = ConnectionMachine { _, n -> seen += n.state }
        m.connectRequested(); m.socketOpened()
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED), seen)
    }

    @Test fun presenterNeverCallsEcuLossABluetoothLoss() {
        val m = readyMachine()
        m.recoveryStarted(FailureKind.REPEATED_NO_DATA, ConnectionPresenter.failureText(FailureKind.REPEATED_NO_DATA), RecoveryLayer.ECU, 10)
        assertEquals("Connected", ConnectionPresenter.bluetooth(m.snapshot))
        assertEquals("Not responding, retrying", ConnectionPresenter.ecu(m.snapshot))
        assertEquals("ECU NOT RESPONDING", ConnectionPresenter.chip(m.snapshot))
        m.recovered(1L)
        m.recoveryStarted(FailureKind.BLUETOOTH_DISCONNECTED, "x", RecoveryLayer.BLUETOOTH, 5)
        assertEquals("Lost, reconnecting", ConnectionPresenter.bluetooth(m.snapshot))
        assertEquals("RECONNECTING…", ConnectionPresenter.chip(m.snapshot))
    }

    @Test fun presenterRowsShowLastCommunicationAndReason() {
        val m = readyMachine()
        m.recoveryStarted(FailureKind.ELM_TIMEOUT, "slow", RecoveryLayer.ECU, 10)
        val rows = ConnectionPresenter.rows(m.snapshot, 4_000L).toMap()
        assertEquals("3 s ago", rows["Last communication"])
        assertEquals("slow", rows["Reason"])
        assertEquals("none yet", ConnectionPresenter.lastCommunication(ConnectionSnapshot(), 9L))
    }
}

class FailureClassifierTest {
    private fun r(kind: ElmKind, timedOut: Boolean = false) = ElmResult("010D", "", kind, 10, timedOut)

    @Test fun lonelyNoDataIsNotAFailure() = assertNull(FailureClassifier.fromResult(r(ElmKind.NO_DATA), false))
    @Test fun timeoutsAreClassifiedByCommand() {
        assertEquals(FailureKind.ADAPTER_TIMEOUT, FailureClassifier.fromResult(r(ElmKind.EMPTY, true), true))
        assertEquals(FailureKind.ELM_TIMEOUT, FailureClassifier.fromResult(r(ElmKind.EMPTY, true), false))
    }
    @Test fun busErrorsAreEcuFailuresNotBluetooth() {
        for (k in listOf(ElmKind.UNABLE_TO_CONNECT, ElmKind.BUS_ERROR, ElmKind.CAN_ERROR, ElmKind.BUS_BUSY))
            assertEquals(FailureKind.ECU_COMMUNICATION_FAILURE, FailureClassifier.fromResult(r(k), false))
    }
    @Test fun garbageIsUnexpected() = assertEquals(FailureKind.UNEXPECTED_ADAPTER_RESPONSE, FailureClassifier.fromResult(r(ElmKind.UNKNOWN_COMMAND), false))
    @Test fun goodResultsAreNotFailures() {
        assertNull(FailureClassifier.fromResult(r(ElmKind.DATA), false)); assertNull(FailureClassifier.fromResult(r(ElmKind.OK), true))
    }
    @Test fun linkStateMapping() {
        assertNull(FailureClassifier.fromLinkState(LinkState.COMMUNICATING))
        assertEquals(FailureKind.REPEATED_NO_DATA, FailureClassifier.fromLinkState(LinkState.ECU_SILENT))
        assertEquals(FailureKind.ADAPTER_TIMEOUT, FailureClassifier.fromLinkState(LinkState.ADAPTER_SILENT))
    }
    @Test fun exceptionsDependOnStage() {
        val e = IOException("x")
        assertEquals(FailureKind.INITIALIZATION_INTERRUPTED, FailureClassifier.fromException(e, ConnectionState.INITIALIZING_ADAPTER))
        assertEquals(FailureKind.BLUETOOTH_DISCONNECTED, FailureClassifier.fromException(e, ConnectionState.READY))
    }
    @Test fun stallWatchdog() {
        val w = StallWatchdog(20_000L)
        assertFalse(w.isStalled(999_999L))
        w.arm(1_000L)
        assertFalse(w.isStalled(20_000L)); assertTrue(w.isStalled(21_001L))
        w.progress(21_000L); assertFalse(w.isStalled(30_000L))
        w.disarm(); assertFalse(w.isStalled(999_999L))
    }
    @Test fun backoffDoublesAndCaps() {
        val b = Backoff(2_000L, 30_000L)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map { b.delayMs(it) })
    }
}

class RecoverySupervisorTest {
    private fun sup(p: FakePorts, m: ConnectionMachine = readyMachine(), pol: RecoveryPolicy = RecoveryPolicy()) = RecoverySupervisor(m, p, pol)

    @Test fun bluetoothLossRecoversAndResumesPolling() {
        val p = FakePorts().apply { script = mutableListOf(true) }
        val m = readyMachine(); val s = sup(p, m)
        assertTrue(s.recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, "read failed"))
        assertEquals(ConnectionState.READY, m.snapshot.state)
        assertEquals(1, p.sockets); assertFalse(p.paused)
        val ev = p.events.filter { !it.startsWith("log:") }
        assertEquals(listOf("pause", "close", "reconnect", "resume"), ev)
    }

    @Test fun retriesWithExponentialBackoffThenRecovers() {
        val p = FakePorts().apply { script = mutableListOf(null, null, true) }
        assertTrue(sup(p).recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, ""))
        assertEquals(listOf(2_000L, 4_000L, 8_000L), p.sleeps)
        assertEquals(1, p.sockets)
    }

    @Test fun retryExhaustionStopsBoundedAndCleansUp() {
        val p = FakePorts()
        val m = readyMachine()
        assertFalse(sup(p, m).recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, ""))
        assertEquals(5, p.events.count { it == "reconnect" })
        assertEquals(ConnectionState.ERROR, m.snapshot.state)
        assertTrue(m.snapshot.reason.contains("5 attempts"))
        assertEquals(0, p.sockets); assertFalse(p.socketOpen); assertFalse(p.paused)
        assertTrue(p.sleeps.all { it <= 30_000L })
    }

    @Test fun staleSocketIsClosedBeforeEveryReconnect() {
        val p = FakePorts().apply { script = mutableListOf(null, true) }
        sup(p).recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, "")
        val ev = p.events.filter { it == "close" || it == "reconnect" }
        assertEquals(listOf("close", "reconnect", "close", "reconnect"), ev)
        assertEquals(1, p.sockets)
    }

    @Test fun duplicateRecoveryIsRefusedWhileOneRuns() {
        val p = FakePorts().apply { script = mutableListOf(true) }
        val s = sup(p)
        var second: Boolean? = null
        p.onReconnect = { second = s.recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, "again"); assertTrue(s.isRecovering()) }
        assertTrue(s.recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, ""))
        assertEquals(false, second)
        assertEquals(1, p.events.count { it == "reconnect" }); assertEquals(1, p.events.count { it == "pause" })
        assertFalse(s.isRecovering())
    }

    @Test fun userDisconnectAbortsRecovery() {
        val p = FakePorts().apply { abortAfterSleeps = 1 }
        val m = readyMachine()
        assertFalse(sup(p, m).recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, ""))
        assertEquals(ConnectionState.DISCONNECTED, m.snapshot.state)
        assertEquals(0, p.events.count { it == "reconnect" }); assertFalse(p.paused)
    }

    @Test fun abortDuringReconnectClosesTheNewSocket() {
        val p = FakePorts().apply { script = mutableListOf(true); onReconnect = { abortAfterSleeps = 0 } }
        val m = readyMachine()
        assertFalse(sup(p, m).recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, ""))
        assertEquals(ConnectionState.DISCONNECTED, m.snapshot.state)
        assertEquals(0, p.sockets); assertFalse(p.paused)
    }

    @Test fun reconnectWithSilentEcuCountsAsFailedAttempt() {
        val p = FakePorts().apply { script = mutableListOf(false, true) }
        assertTrue(sup(p).recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, ""))
        assertEquals(2, p.events.count { it == "reconnect" })
    }

    @Test fun permissionLossStopsImmediately() {
        var calls = 0
        val p = FakePorts().apply { onReconnect = { calls++; throw SecurityException("no BLUETOOTH_CONNECT") } }
        val m = readyMachine()
        assertFalse(sup(p, m).recoverBluetooth(FailureKind.BLUETOOTH_DISCONNECTED, ""))
        assertEquals(1, calls)
        assertEquals(ConnectionState.ERROR, m.snapshot.state)
        assertTrue(m.snapshot.reason.contains("permission"))
    }

    // ---- ECU-only loss ----
    @Test fun ecuOnlyLossNeverTouchesBluetooth() {
        val p = FakePorts(); val m = readyMachine(); val s = sup(p, m)
        s.observeEcu(LinkState.ECU_SILENT, false, false, 2_000L)
        assertEquals(ConnectionState.RECOVERING, m.snapshot.state)
        assertEquals(RecoveryLayer.ECU, m.snapshot.layer)
        assertEquals(FailureKind.REPEATED_NO_DATA, m.snapshot.failure)
        assertTrue(m.snapshot.bluetoothUp); assertFalse(m.snapshot.ecuAvailable)
        for (i in 1..30) s.observeEcu(LinkState.ECU_SILENT, true, false, 2_000L + i)
        assertTrue(p.events.none { it == "close" || it == "reconnect" })
    }

    @Test fun ecuQuickAttemptsAreBoundedThenSlowStandby() {
        val p = FakePorts(); val m = readyMachine(); val s = sup(p, m)
        s.observeEcu(LinkState.ECU_SILENT, false, false, 2_000L)
        val pauses = mutableListOf<Long>()
        repeat(10) { s.observeEcu(LinkState.ECU_SILENT, true, false, 3_000L); pauses += s.ecuPauseMs() }
        assertEquals(ConnectionState.ERROR, m.snapshot.state)
        assertEquals(RecoveryLayer.ECU, m.snapshot.layer)
        assertTrue(m.snapshot.bluetoothUp)
        assertEquals(30_000L, s.ecuPauseMs())
        assertTrue(pauses.dropLast(1).all { it in 1_000L..10_000L })
        assertEquals(pauses.dropLast(1).sorted(), pauses.dropLast(1))
        assertEquals("WAITING FOR ECU", ConnectionPresenter.chip(m.snapshot))
    }

    @Test fun ignitionOffOnSimulation() {
        val p = FakePorts(); val m = readyMachine(); val s = sup(p, m)
        // ignition OFF: probes fail through the quick budget into standby
        s.observeEcu(LinkState.ECU_SILENT, false, false, 2_000L)
        repeat(12) { s.observeEcu(LinkState.ECU_SILENT, true, false, 3_000L) }
        assertEquals(ConnectionState.ERROR, m.snapshot.state)
        assertEquals("Connected", ConnectionPresenter.bluetooth(m.snapshot))
        // ignition ON: a standby probe succeeds
        assertEquals(EcuAction.NONE, s.observeEcu(LinkState.COMMUNICATING, true, true, 90_000L))
        assertEquals(ConnectionState.READY, m.snapshot.state)
        assertTrue(m.snapshot.ecuAvailable)
        assertEquals(90_000L, m.snapshot.lastGoodMs)
        assertEquals(0L, s.ecuPauseMs())
        assertTrue(p.events.none { it == "close" || it == "reconnect" || it == "pause" })
    }

    @Test fun ecuRecoveryDuringQuickPhaseResumesWithoutReconnect() {
        val p = FakePorts(); val m = readyMachine(); val s = sup(p, m)
        s.observeEcu(LinkState.ECU_SILENT, false, false, 2_000L)
        s.observeEcu(LinkState.ECU_SILENT, true, false, 3_000L)
        s.observeEcu(LinkState.COMMUNICATING, true, true, 4_000L)
        assertEquals(ConnectionState.READY, m.snapshot.state)
        s.observeEcu(LinkState.ECU_SILENT, false, false, 5_000L) // a new loss starts a fresh budget
        assertEquals(ConnectionState.RECOVERING, m.snapshot.state); assertEquals(0, m.snapshot.attempt)
    }

    @Test fun silentAdapterEscalatesToBluetoothReconnect() {
        val p = FakePorts(); val m = readyMachine(); val s = sup(p, m)
        s.observeEcu(LinkState.ADAPTER_SILENT, false, false, 2_000L)
        assertEquals(EcuAction.NONE, s.observeEcu(LinkState.ADAPTER_SILENT, true, false, 3_000L))
        assertEquals(EcuAction.NONE, s.observeEcu(LinkState.ADAPTER_SILENT, true, false, 4_000L))
        assertEquals(EcuAction.RECONNECT_BLUETOOTH, s.observeEcu(LinkState.ADAPTER_SILENT, true, false, 5_000L))
    }

    @Test fun ecuSilentNeverEscalatesToBluetooth() {
        val s = sup(FakePorts())
        s.observeEcu(LinkState.ECU_SILENT, false, false, 2_000L)
        repeat(100) { assertEquals(EcuAction.NONE, s.observeEcu(LinkState.ECU_SILENT, true, false, 3_000L)) }
    }

    @Test fun ecuAfterBluetoothRecoveryStartsFresh() {
        val p = FakePorts().apply { script = mutableListOf(true) }
        val m = readyMachine(); val s = sup(p, m)
        s.observeEcu(LinkState.ADAPTER_SILENT, false, false, 2_000L)
        repeat(3) { s.observeEcu(LinkState.ADAPTER_SILENT, true, false, 3_000L) }
        assertTrue(s.recoverBluetooth(FailureKind.ADAPTER_TIMEOUT, "adapter silent"))
        assertEquals(ConnectionState.READY, m.snapshot.state)
        assertEquals(0L, s.ecuPauseMs())
        assertEquals(1, p.sockets)
    }

    @Test fun noStaleReadingFlagWhileNotReady() {
        val m = readyMachine(); val s = sup(FakePorts(), m)
        s.observeEcu(LinkState.ECU_SILENT, false, false, 2_000L)
        assertFalse(m.snapshot.ecuAvailable)
        s.observeEcu(LinkState.COMMUNICATING, true, true, 3_000L)
        assertTrue(m.snapshot.ecuAvailable)
    }
}
