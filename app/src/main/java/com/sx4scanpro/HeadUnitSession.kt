package com.sx4scanpro

/** What Open Headunit says its projection session is doing (its SESSION_STATE broadcast). */
internal enum class SessionPhase { UNKNOWN, CONNECTING, CONNECTED, PROJECTING, DISCONNECTED, FAILED }

internal data class HeadUnitSession(
    val phase: SessionPhase = SessionPhase.UNKNOWN,
    val transport: String = "",
    val reason: String = "",
    val uptimeMs: Long = 0L
) {
    companion object { val None = HeadUnitSession() }
}

/**
 * The automation interface of Open Headunit (see its contract/README.md). The package and the
 * action prefix differ on purpose; using the wrong one silently does nothing, so both live here.
 */
internal object HeadUnitProtocol {
    const val PACKAGE = "com.andrerinas.headunitrevived"
    const val PREFIX = "com.andrerinas.openheadunit"
    const val RECEIVER_CLASS = "com.andrerinas.openheadunit.automation.AutomationReceiver"
    const val SESSION_STATE_ACTION = "com.andrerinas.headunitrevived.SESSION_STATE"

    const val CONNECT = "ACTION_CONNECT"
    const val DISCONNECT = "ACTION_DISCONNECT"
    const val RAISE_PROJECTION = "ACTION_RAISE_PROJECTION"
    const val SET_NIGHT_MODE = "ACTION_SET_NIGHT_MODE"

    fun action(name: String): String = "$PREFIX.$name"

    fun parse(state: String?, transport: String?, reason: String?, uptimeMs: Long): HeadUnitSession {
        val phase = when (state.orEmpty().trim().lowercase()) {
            "connecting" -> SessionPhase.CONNECTING
            "connected" -> SessionPhase.CONNECTED
            "projecting" -> SessionPhase.PROJECTING
            "disconnected" -> SessionPhase.DISCONNECTED
            "failed" -> SessionPhase.FAILED
            else -> SessionPhase.UNKNOWN
        }
        return HeadUnitSession(phase, transport.orEmpty().trim().lowercase(), reason.orEmpty().trim().lowercase(), uptimeMs.coerceAtLeast(0L))
    }

    private fun transportText(t: String): String = when (t) {
        "usb" -> "USB"
        "wifi" -> "Wi-Fi"
        "self" -> "this device"
        else -> ""
    }

    /** Reasons are an open set: an unfamiliar one is reported as such, never mapped to a guess. */
    fun reasonText(reason: String): String = when (reason) {
        "" -> ""
        "user_exit" -> "it was ended on request"
        "link_lost" -> "the link was lost"
        "phone_left" -> "the phone went away"
        "handshake_failed" -> "the Android Auto handshake failed"
        "peer_silent" -> "the phone stopped answering"
        "connect_failed" -> "the connection could not be made"
        else -> "another reason (\"$reason\")"
    }

    fun describe(session: HeadUnitSession): String {
        val via = transportText(session.transport).let { if (it.isEmpty()) "" else " over $it" }
        val why = reasonText(session.reason).let { if (it.isEmpty()) "" else ": $it" }
        return when (session.phase) {
            SessionPhase.UNKNOWN -> "No status from Open Headunit yet."
            SessionPhase.CONNECTING -> "Connecting to the phone$via…"
            SessionPhase.CONNECTED -> "Connected to the phone$via. Waiting for Android Auto to start projecting."
            SessionPhase.PROJECTING -> "Android Auto is projecting$via."
            SessionPhase.DISCONNECTED -> "Not connected$why."
            SessionPhase.FAILED -> "Connection failed$why."
        }
    }

    fun nightModeState(mode: ThemeMode): String = when (mode) {
        ThemeMode.AUTO -> "auto"
        ThemeMode.DAY -> "day"
        ThemeMode.NIGHT -> "night"
    }

    /**
     * The phone's address when the tablet is on the phone's hotspot: the Wi-Fi gateway.
     * [gateway] is the little-endian int from the system's DHCP info; 0 means no gateway.
     */
    fun gatewayIp(gateway: Int): String? =
        if (gateway == 0) null
        else "${gateway and 0xFF}.${(gateway shr 8) and 0xFF}.${(gateway shr 16) and 0xFF}.${(gateway shr 24) and 0xFF}"
}
