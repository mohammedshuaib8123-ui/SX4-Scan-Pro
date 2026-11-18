package com.sx4scanpro

/**
 * The change tier. [ReadOnlyGuard] stays the default and still refuses every non-read command. This file is the ONLY way a
 * command that changes ECU state can be sent, and today it holds exactly one action: clearing stored fault codes (OBD-II
 * service 04) on the engine ECU. Nothing that writes memory, codes or adapts the ECU, opens a session, asks for security
 * access, resets the ECU or runs a test has a path through here, so those stay blocked for good.
 */
internal enum class ChangeAction(val command: String, val description: String) {
    CLEAR_DTC("04", "Clear the stored fault codes, freeze frame and monitor results on the engine ECU (OBD-II service 04)")
}

/** Permission to send ONE change command, valid for a short time. Issued only by [ClearDtcPolicy] after every precondition and the owner's confirmation. */
internal class ChangeAuthorization internal constructor(val action: ChangeAction, private val issuedAtMs: Long) {
    private var used = false

    /** True exactly once, and only inside [VALID_MS] of issue. A second use, a late use or a clock that went backwards fails. */
    @Synchronized fun consume(nowMs: Long): Boolean {
        if (used || nowMs < issuedAtMs || nowMs - issuedAtMs > VALID_MS) return false
        used = true
        return true
    }

    companion object { const val VALID_MS = 30_000L }
}

internal object ChangeGuard {
    /** Allows a command the read-only guard refused only when it is exactly the authorised action and the authorisation is still unused. */
    fun check(raw: String, auth: ChangeAuthorization?, nowMs: Long): GuardVerdict {
        if (auth == null) return GuardVerdict.Blocked("no change authorisation")
        if (ReadOnlyGuard.normalize(raw) != auth.action.command) return GuardVerdict.Blocked("command is not the authorised action")
        return if (auth.consume(nowMs)) GuardVerdict.Allowed else GuardVerdict.Blocked("authorisation expired or already used")
    }
}

internal data class ClearPreconditions(
    val linkCommunicating: Boolean,
    /** Engine speed asked from the ECU just now, or null when it could not be read. */
    val engineRpm: Double?,
    /** The codes and freeze frame as they were before clearing have been saved. */
    val snapshotSaved: Boolean,
    /** The owner pressed the confirmation for this clear. */
    val ownerConfirmed: Boolean
)

internal sealed class ClearDecision {
    data class Authorized(val auth: ChangeAuthorization) : ClearDecision()
    data class Refused(val reason: String) : ClearDecision()
}

internal object ClearDtcPolicy {
    /** Every check must pass; the first failure is the reason shown. Engine speed must be read and be exactly zero (key on, engine off). */
    fun decide(p: ClearPreconditions, nowMs: Long): ClearDecision = when {
        !p.linkCommunicating -> ClearDecision.Refused("The ECU is not responding, so nothing was cleared.")
        p.engineRpm == null -> ClearDecision.Refused("Engine speed could not be read, so the engine cannot be confirmed off. Nothing was cleared.")
        p.engineRpm > 0.0 -> ClearDecision.Refused("The engine is running. Switch the engine off, leave the key on, and try again.")
        !p.snapshotSaved -> ClearDecision.Refused("The codes could not be saved first, so nothing was cleared.")
        !p.ownerConfirmed -> ClearDecision.Refused("Clearing was not confirmed.")
        else -> ClearDecision.Authorized(ChangeAuthorization(ChangeAction.CLEAR_DTC, nowMs))
    }
}

internal enum class ClearOutcome { CLEARED, REFUSED_BY_APP, REFUSED_BY_ECU, NO_ANSWER }

internal data class ClearResult(
    val outcome: ClearOutcome,
    val raw: String,
    val detail: String,
    val before: DtcScanReport?,
    val after: DtcScanReport?
) {
    /** Plain wording for the screen. The positive reply alone is never presented as proof: the re-read decides what is said about the codes. */
    fun message(): String = when (outcome) {
        ClearOutcome.REFUSED_BY_APP -> detail
        ClearOutcome.REFUSED_BY_ECU -> "The ECU refused to clear the codes. $detail"
        ClearOutcome.NO_ANSWER -> "The ECU gave no usable answer to the clear request. $detail Check the codes again."
        ClearOutcome.CLEARED -> when {
            after == null -> "The ECU accepted the clear request, but the codes could not be read back to confirm."
            after.engineEcuReportsNoCodes -> "Cleared. The ECU accepted the request and a new scan reports no stored, pending or permanent codes."
            after.codes.isNotEmpty() -> "The ECU accepted the request, but ${after.codes.size} code(s) are reported again. A fault that is still present sets its code again."
            else -> "The ECU accepted the request, but the read-back was incomplete, so an empty list is not confirmed."
        }
    }
}

internal object ClearReplyClassifier {
    /** With headers off a positive reply to service 04 is `44`; a refusal is `7F 04 xx`. Anything else is treated as no answer. */
    fun classify(raw: String): Pair<ClearOutcome, String> {
        val n = raw.uppercase().filterNot { it.isWhitespace() }
        return when {
            n.isEmpty() -> ClearOutcome.NO_ANSWER to "Empty reply."
            n.contains("NODATA") -> ClearOutcome.NO_ANSWER to "The adapter reported NO DATA."
            n.startsWith("7F04") -> ClearOutcome.REFUSED_BY_ECU to (if (n.length >= 6) "Reason code ${n.substring(4, 6)}." else "No reason code.")
            n == "44" || (n.startsWith("44") && n.length % 2 == 0) -> ClearOutcome.CLEARED to ""
            else -> ClearOutcome.NO_ANSWER to "Reply not understood: $raw"
        }
    }
}

internal object ClearSnapshot {
    /** What is saved before anything is cleared: every service's state, the codes and the verbatim replies, plus the freeze frame record when it could be read. */
    fun text(nowMs: Long, before: DtcScanReport, freezeFrame: String?): String = buildList {
        add("SX4 Scan Pro - codes saved before clearing")
        add("Saved at epoch ms $nowMs   Protocol: ${before.protocol}")
        add(DtcScanReport.SCOPE)
        before.modes.forEach { m ->
            add(DtcScanText.modeLine(m))
            if (m.codes.isNotEmpty()) add("    codes: " + m.codes.joinToString(" "))
            add("    raw: " + m.raw.replace("\n", " | "))
        }
        add("Freeze frame: " + (freezeFrame ?: "could not be read"))
    }.joinToString("\n")
}
