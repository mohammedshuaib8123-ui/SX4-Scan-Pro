package com.sx4scanpro

/**
 * Holds the single command the clear-codes procedure is allowed to send. Set on the calling thread only,
 * so a poll on another thread cannot reuse it. Closed again before the procedure returns.
 */
internal object WorkshopPermit {
    private val open = ThreadLocal<String?>()

    fun allows(normalized: String): Boolean = open.get() == normalized

    fun <T> open(command: String, block: () -> T): T {
        val previous = open.get()
        open.set(ReadOnlyGuard.normalize(command))
        try {
            return block()
        } finally {
            open.set(previous)
        }
    }
}

internal enum class ClearOutcome { NOT_SENT, ACCEPTED, REJECTED, NO_ANSWER, UNREADABLE, TRANSPORT }

internal data class ClearReport(
    val before: DtcScanReport?,
    val after: DtcScanReport?,
    val outcome: ClearOutcome,
    val detail: String,
    val raw: String
) {
    fun headline(): String = when (outcome) {
        ClearOutcome.NOT_SENT -> detail
        ClearOutcome.ACCEPTED -> "Mode 04 was accepted by the engine ECU. " + (after?.headline() ?: "The codes could not be read again after the clear.")
        ClearOutcome.REJECTED -> "The engine ECU refused Mode 04. Nothing was confirmed cleared. Reply: $raw"
        ClearOutcome.NO_ANSWER -> "The engine ECU did not answer Mode 04. Do not assume the codes were cleared."
        ClearOutcome.UNREADABLE -> "Mode 04 returned a reply that could not be read. Do not assume the codes were cleared. Reply: $raw"
        ClearOutcome.TRANSPORT -> "Clear did not finish: $detail"
    }

    companion object {
        fun notSent(detail: String, before: DtcScanReport? = null) =
            ClearReport(before, null, ClearOutcome.NOT_SENT, detail, "")
    }
}

/** SAE J1979 Mode 04, the standard "clear DTCs and stored values" the engine ECU already speaks. No other service. */
internal object ClearCodes {
    const val COMMAND = "04"

    /** Above this the car is treated as moving and Mode 04 is not sent. */
    const val MAX_SPEED_KMH = 3.0

    fun moving(speedKmh: Double?): Boolean = speedKmh != null && speedKmh > MAX_SPEED_KMH

    fun classify(raw: String): ClearOutcome {
        val kind = ElmReply.classify(raw)
        val body = ElmReply.stripBanners(raw)
        if (body.contains("7F04")) return ClearOutcome.REJECTED
        if (kind == ElmKind.NO_DATA || kind == ElmKind.EMPTY || kind == ElmKind.STOPPED || kind == ElmKind.UNKNOWN_COMMAND ||
            kind == ElmKind.UNABLE_TO_CONNECT || kind == ElmKind.BUS_ERROR || kind == ElmKind.BUS_BUSY
        ) return ClearOutcome.NO_ANSWER
        if (body == "44" || body.startsWith("44")) return ClearOutcome.ACCEPTED
        return ClearOutcome.UNREADABLE
    }
}

/**
 * Working assumption for this chassis only. It is not an ECM identification.
 * Manufacturer routines are named so the gap is visible. Their request bytes are not in a citable source for this
 * calibration, and this app does not invent them.
 */
internal data class WorkshopFunction(val name: String, val group: String, val sent: Boolean, val note: String)

internal object AssumedEcu {
    const val VIN = "MA3FFEB1S00215514"
    const val SUMMARY =
        "Assumed, not confirmed: Magneti Marelli MJD family, Fiat 1.3 Multijet as fitted to the Maruti 1.3 DDiS. " +
            "Chassis $VIN, 1248 cc, ISO 14230-4 fast init at 0x11. The ECM label has not been read."

    val functions: List<WorkshopFunction> = listOf(
        WorkshopFunction("Clear engine OBD codes", "Engine", true, "SAE J1979 Mode 04. The only command this build transmits."),
        WorkshopFunction("Read stored, pending and permanent codes", "Engine", true, "Modes 03, 07 and 0A. Already on the Diagnostics screen."),
        WorkshopFunction("Freeze frame", "Engine", true, "Mode 02. Already on the Diagnostics screen."),
        WorkshopFunction("Live data", "Engine", true, "Mode 01. Already on the cockpit."),
        WorkshopFunction("EGR test", "Engine", false, "Not sent. The routine identifier for this ECM is not confirmed."),
        WorkshopFunction("Glow-plug command", "Engine", false, "Not sent. The routine identifier for this ECM is not confirmed."),
        WorkshopFunction("Boost / VGT test", "Engine", false, "Not sent. The routine identifier for this ECM is not confirmed."),
        WorkshopFunction("Rail-pressure regulator test", "Engine", false, "Not sent. The routine identifier for this ECM is not confirmed."),
        WorkshopFunction("Fan and relay tests", "Engine", false, "Not sent. The routine identifier for this ECM is not confirmed."),
        WorkshopFunction("Injector coding", "Engine", false, "Not sent. Needs the ECM part number and a dealer procedure."),
        WorkshopFunction("Learned-value reset", "Engine", false, "Not sent. Needs the ECM part number and a dealer procedure."),
        WorkshopFunction("ABS, airbag, BCM, EPS, immobilizer", "Other modules", false, "Not sent. Fitment and addresses are not confirmed. No security-access or programming service is included.")
    )
}
