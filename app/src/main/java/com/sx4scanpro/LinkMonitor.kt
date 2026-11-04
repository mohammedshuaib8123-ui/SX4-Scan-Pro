package com.sx4scanpro

internal enum class LinkState { COMMUNICATING, ECU_SILENT, ADAPTER_SILENT }
internal enum class RecoveryStep { PROBE, LIGHT_REINIT, FULL_REINIT }
internal data class RecoveryAction(val step: RecoveryStep, val waitMs: Long)

/**
 * Decides, from consecutive adapter results, whether the ECU is answering. It never opens or closes the
 * Bluetooth socket: recovery happens on the existing connection (probe, light re-init, full re-init).
 */
internal class LinkMonitor(private val silentLimit: Int = 4, private val timeoutLimit: Int = 2) {
    var state: LinkState = LinkState.COMMUNICATING
        private set
    private var silent = 0
    private var timeouts = 0
    private var attempt = 0
    var lastGoodMs: Long = 0L
        private set

    fun onGood(now: Long) { silent = 0; timeouts = 0; attempt = 0; lastGoodMs = now; state = LinkState.COMMUNICATING }

    fun onResult(kind: ElmKind, timedOut: Boolean) {
        if (timedOut) {
            timeouts++
            if (timeouts >= timeoutLimit) state = LinkState.ADAPTER_SILENT
            return
        }
        timeouts = 0
        if (ElmReply.isEcuSilent(kind) || kind == ElmKind.EMPTY) {
            silent++
            if (silent >= silentLimit && state == LinkState.COMMUNICATING) state = LinkState.ECU_SILENT
        }
    }

    /** Next recovery action with back-off; the waits are short so readings resume quickly after ignition on. */
    fun nextRecovery(): RecoveryAction {
        val a = attempt++
        val step = when {
            a < 2 -> RecoveryStep.PROBE
            a < 4 -> RecoveryStep.LIGHT_REINIT
            else -> if (a % 3 == 1) RecoveryStep.FULL_REINIT else RecoveryStep.PROBE
        }
        return RecoveryAction(step, minOf(1000L + 1000L * a, 5000L))
    }
}
