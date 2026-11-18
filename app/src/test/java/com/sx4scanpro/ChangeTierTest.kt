package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class ChangeTierTest {
    private val ok = ClearPreconditions(linkCommunicating = true, engineRpm = 0.0, snapshotSaved = true, ownerConfirmed = true)
    private fun auth(now: Long = 1_000L) = (ClearDtcPolicy.decide(ok, now) as ClearDecision.Authorized).auth
    private fun refused(p: ClearPreconditions) = (ClearDtcPolicy.decide(p, 1_000L) as ClearDecision.Refused).reason

    @Test fun readOnlyGuardStillBlocksClearByItself() {
        assertTrue(ReadOnlyGuard.check("04") is GuardVerdict.Blocked)
        assertTrue(ReadOnlyGuard.check("14FF00") is GuardVerdict.Blocked)
    }

    @Test fun allPreconditionsAndTheConfirmationAreRequired() {
        assertTrue(ClearDtcPolicy.decide(ok, 1_000L) is ClearDecision.Authorized)
        assertTrue(refused(ok.copy(linkCommunicating = false)).contains("not responding"))
        assertTrue(refused(ok.copy(engineRpm = null)).contains("could not be read"))
        assertTrue(refused(ok.copy(engineRpm = 750.0)).contains("running"))
        assertTrue(refused(ok.copy(snapshotSaved = false)).contains("saved first"))
        assertTrue(refused(ok.copy(ownerConfirmed = false)).contains("not confirmed"))
    }

    @Test fun authorisationAllowsExactlyOneClearAndNothingElse() {
        val a = auth()
        assertEquals(GuardVerdict.Allowed, ChangeGuard.check("04", a, 2_000L))
        assertTrue("second use must fail", ChangeGuard.check("04", a, 2_100L) is GuardVerdict.Blocked)
    }

    @Test fun authorisationExpires() {
        assertTrue(ChangeGuard.check("04", auth(1_000L), 1_000L + ChangeAuthorization.VALID_MS + 1) is GuardVerdict.Blocked)
        assertTrue("clock going backwards", ChangeGuard.check("04", auth(5_000L), 4_000L) is GuardVerdict.Blocked)
        assertEquals(GuardVerdict.Allowed, ChangeGuard.check("04", auth(1_000L), 1_000L + ChangeAuthorization.VALID_MS))
    }

    @Test fun noAuthorisationMeansBlocked() = assertTrue(ChangeGuard.check("04", null, 1L) is GuardVerdict.Blocked)

    @Test fun everyDangerousCommandStaysBlockedEvenWithAValidClearAuthorisation() {
        listOf("1001", "1003", "1101", "14FF00", "2701", "2702", "2E1234AA", "2F1234", "3101", "34", "3600", "37", "3B", "3D", "85", "28", "2C", "2A", "3E00", "0401", "ATPP FF SV 00", "ATZ")
            .forEach { c -> assertTrue("should be blocked: $c", ChangeGuard.check(c, auth(), 1_500L) is GuardVerdict.Blocked) }
    }

    @Test fun replyClassification() {
        assertEquals(ClearOutcome.CLEARED, ClearReplyClassifier.classify("44").first)
        assertEquals(ClearOutcome.CLEARED, ClearReplyClassifier.classify(" 44 ").first)
        val no = ClearReplyClassifier.classify("7F 04 22")
        assertEquals(ClearOutcome.REFUSED_BY_ECU, no.first); assertTrue(no.second.contains("22"))
        assertEquals(ClearOutcome.NO_ANSWER, ClearReplyClassifier.classify("NO DATA").first)
        assertEquals(ClearOutcome.NO_ANSWER, ClearReplyClassifier.classify("").first)
        assertEquals(ClearOutcome.NO_ANSWER, ClearReplyClassifier.classify("41 00 BE").first)
    }

    @Test fun clearedIsOnlyClaimedWhenTheReReadSaysSo() {
        val empty = DtcScanReport(0L, "ISO 14230-4", DtcScanReport.SERVICES.map { (m, c) -> DtcModeScan(m, c, DtcModeStatus.VALID_EMPTY, emptyList(), emptyList(), "43 00") })
        val again = DtcScanReport(0L, "ISO 14230-4", listOf(DtcModeScan("03", "STORED", DtcModeStatus.CODES_REPORTED, listOf("P0100"), emptyList(), "43 01 01 00")))
        assertTrue(ClearResult(ClearOutcome.CLEARED, "44", "", null, empty).message().startsWith("Cleared."))
        assertTrue(ClearResult(ClearOutcome.CLEARED, "44", "", null, again).message().contains("reported again"))
        assertTrue(ClearResult(ClearOutcome.CLEARED, "44", "", null, null).message().contains("could not be read back"))
    }

    @Test fun snapshotKeepsCodesAndVerbatimReplies() {
        val r = DtcScanReport(0L, "ISO 14230-4", listOf(DtcModeScan("03", "STORED", DtcModeStatus.CODES_REPORTED, listOf("P0100"), emptyList(), "43 01 01 00")))
        val t = ClearSnapshot.text(5L, r, null)
        assertTrue(t.contains("P0100")); assertTrue(t.contains("43 01 01 00")); assertTrue(t.contains("could not be read"))
    }
}
