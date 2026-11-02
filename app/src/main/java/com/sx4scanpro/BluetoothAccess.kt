package com.sx4scanpro

/** A Bluetooth device as the picker shows it. */
internal data class BtDevice(val name: String, val address: String, val bonded: Boolean)

internal enum class AccessStatus {
    /** Everything needed is allowed. */
    GRANTED,

    /** Something is missing and Android will still show the permission dialog. */
    NEEDS_REQUEST,

    /** Denied until the user changes it in the app's system settings. */
    BLOCKED
}

/**
 * Which runtime permissions Bluetooth needs on which Android version, and what state they are in.
 * Names are plain strings so this stays testable without Android.
 */
internal object BluetoothAccess {
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

    /** Listing paired devices and opening a socket. Android 11 and older need no runtime permission. */
    fun forConnecting(sdk: Int): List<String> = if (sdk >= 31) listOf(CONNECT) else emptyList()

    /** Finding and pairing new adapters. Android 12+ groups these as "Nearby devices"; older versions need location. */
    fun forScanning(sdk: Int): List<String> = if (sdk >= 31) listOf(CONNECT, SCAN) else listOf(FINE_LOCATION)

    /**
     * [askedBefore] is whether this app has already shown the dialog. Without it, "no rationale to show"
     * would also describe the very first request, and a fresh install would look permanently blocked.
     */
    fun status(
        required: List<String>,
        isGranted: (String) -> Boolean,
        askedBefore: Boolean,
        canShowRationale: (String) -> Boolean
    ): AccessStatus {
        val missing = required.filterNot(isGranted)
        if (missing.isEmpty()) return AccessStatus.GRANTED
        return if (askedBefore && missing.none(canShowRationale)) AccessStatus.BLOCKED else AccessStatus.NEEDS_REQUEST
    }

    fun message(status: AccessStatus): String = when (status) {
        AccessStatus.GRANTED -> "Bluetooth access allowed."
        AccessStatus.NEEDS_REQUEST ->
            "SX4 Scan Pro needs the Nearby devices permission to find and talk to your OBD adapter."
        AccessStatus.BLOCKED ->
            "Bluetooth permission was denied. Open the app's settings, choose Permissions, and allow Nearby devices."
    }
}

internal sealed class AdapterChoice {
    data class Use(val device: BtDevice) : AdapterChoice()
    data class Unavailable(val message: String) : AdapterChoice()
}

/** Decides which paired device to talk to. The user's explicit choice always wins over name guessing. */
internal object AdapterSelector {
    fun choose(bonded: List<BtDevice>, savedAddress: String?): AdapterChoice {
        if (savedAddress != null) {
            bonded.firstOrNull { it.address.equals(savedAddress, ignoreCase = true) }
                ?.let { return AdapterChoice.Use(it) }
        }
        val known = bonded.filter { EcuNegotiation.looksLikeObdAdapter(it.name) }
        val names = bonded.joinToString(", ") { it.name }.ifBlank { "none" }
        return when {
            known.size == 1 -> AdapterChoice.Use(known.single())
            known.size > 1 -> AdapterChoice.Unavailable(
                "Several OBD adapters are paired (" + known.joinToString(", ") { it.name } +
                    "). Choose one in Settings, OBD ADAPTER."
            )
            bonded.isEmpty() -> AdapterChoice.Unavailable(
                "No Bluetooth devices are paired yet. Open Settings, OBD ADAPTER, and scan for your adapter."
            )
            else -> AdapterChoice.Unavailable(
                "None of your paired devices ($names) looks like an OBD adapter. " +
                    "Choose yours in Settings, OBD ADAPTER, or scan to pair it."
            )
        }
    }
}
