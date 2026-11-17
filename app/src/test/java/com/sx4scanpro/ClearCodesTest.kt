package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class ClearCodesTest {
    @Test fun mode04StaysBlockedUntilTheClearProcedureOpensIt() {
        assertTrue(ReadOnlyGuard.check("04") is GuardVerdict.Blocked)
        WorkshopPermit.open("04") {
            assertEquals(GuardVerdict.Allowed, ReadOnlyGuard.check("04"))
            assertTrue(ReadOnlyGuard.check("0400") is GuardVerdict.Blocked)
            assertTrue(ReadOnlyGuard.check("14FFFFFF") is GuardVerdict.Blocked)
            assertTrue(ReadOnlyGuard.check("31") is GuardVerdict.Blocked)
            assertTrue(ReadOnlyGuard.check("08") is GuardVerdict.Blocked)
            assertTrue(ReadOnlyGuard.check("2701") is GuardVerdict.Blocked)
        }
        assertTrue(ReadOnlyGuard.check("04") is GuardVerdict.Blocked)
    }

    @Test fun movingCarIsRefused() {
        assertFalse(ClearCodes.moving(null))
        assertFalse(ClearCodes.moving(0.0))
        assertFalse(ClearCodes.moving(3.0))
        assertTrue(ClearCodes.moving(3.1))
    }

    @Test fun onlyAPositive44IsAccepted() {
        assertEquals(ClearOutcome.ACCEPTED, ClearCodes.classify("44"))
        assertEquals(ClearOutcome.ACCEPTED, ClearCodes.classify("SEARCHING...\r44"))
        assertEquals(ClearOutcome.REJECTED, ClearCodes.classify("7F0412"))
        assertEquals(ClearOutcome.NO_ANSWER, ClearCodes.classify("NO DATA"))
        assertEquals(ClearOutcome.UNREADABLE, ClearCodes.classify("43 00 00"))
    }

    @Test fun assumedCatalogueSendsNothingButTheKnownServices() {
        assertEquals("MA3FFEB1S00215514", AssumedEcu.VIN)
        val sent = AssumedEcu.functions.filter { it.sent }.map { it.name }
        assertEquals(listOf("Clear engine OBD codes", "Read stored, pending and permanent codes", "Freeze frame", "Live data"), sent)
        assertTrue(AssumedEcu.functions.filter { !it.sent }.all { it.note.startsWith("Not sent") })
        assertTrue(AssumedEcu.SUMMARY.contains("not confirmed"))
    }
}
