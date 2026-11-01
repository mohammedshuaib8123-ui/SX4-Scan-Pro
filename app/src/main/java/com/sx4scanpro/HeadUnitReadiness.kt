package com.sx4scanpro

/** A USB device Android currently lists. Seeing one is not proof that Android Auto will work. */
internal data class UsbSeen(val name: String, val vendorId: Int, val productId: Int) {
    val label: String get() = name + " • VID %04X / PID %04X".format(vendorId, productId)
}

internal enum class CheckState { OK, ACTION }

internal data class ReadinessCheck(val title: String, val state: CheckState, val detail: String)

/**
 * What can be verified on this tablet before starting Android Auto through the separate
 * receiver app. It checks preconditions only; it cannot, and does not claim to, confirm that
 * projection, touch or audio work: those need a real phone, cable and car.
 */
internal data class HeadUnitReadiness(
    val receiverInstalled: Boolean,
    val usbHostFeature: Boolean,
    val usbDevices: List<UsbSeen>
) {
    val checks: List<ReadinessCheck>
        get() = listOf(
            ReadinessCheck(
                "Receiver app",
                if (receiverInstalled) CheckState.OK else CheckState.ACTION,
                if (receiverInstalled) "Open Headunit is installed."
                else "Open Headunit is not installed. Install it, then tap RECHECK."
            ),
            ReadinessCheck(
                "USB host mode",
                if (usbHostFeature) CheckState.OK else CheckState.ACTION,
                if (usbHostFeature) "This tablet reports USB host support."
                else "Android does not report USB host support on this tablet, so wired projection may not work."
            ),
            ReadinessCheck(
                "Phone on USB",
                if (usbDevices.isNotEmpty()) CheckState.OK else CheckState.ACTION,
                if (usbDevices.isNotEmpty()) usbDevices.joinToString("; ") { it.label }
                else "No USB device seen. Unlock the phone, enable Android Auto and connect a data-capable cable (OTG adapter if needed)."
            )
        )

    /** All preconditions hold. Not a promise that projection will work. */
    val readyToStart: Boolean get() = checks.all { it.state == CheckState.OK }

    val nextStep: String
        get() = when {
            !receiverInstalled -> "Install the Open Headunit receiver, then tap RECHECK."
            !usbHostFeature -> "This tablet does not report USB host support. Wired projection may not work."
            usbDevices.isEmpty() -> "Connect the phone to the tablet with a data cable."
            else -> "Preconditions met. Open the receiver and accept the USB and Android Auto prompts. Projection itself is not verified until you see it working."
        }
}
