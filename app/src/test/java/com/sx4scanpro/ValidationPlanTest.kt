package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class ValidationPlanTest {
    @Test fun idsAreUniqueAndEveryItemHasInstructions() {
        assertEquals(ValidationPlan.items.size, ValidationPlan.items.map { it.id }.toSet().size)
        assertTrue(ValidationPlan.items.all { it.title.isNotBlank() && it.how.isNotBlank() })
    }

    @Test fun nothingIsPassedUntilTheOwnerRecordsIt() {
        assertTrue(ValidationPlan.items.all { ValidationPlan.statusOf(emptyList(), it.id) == ValidationStatus.UNTESTED })
        val text = ValidationPlan.reportLines(emptyList()).joinToString("\n")
        assertTrue(text.contains("0 passed, 0 failed, ${ValidationPlan.items.size} not yet tested"))
        assertFalse(text.contains("PASS (not independently verified)\n"))
        assertTrue(text.contains("not independently verified")); assertTrue(text.contains("NOT yet included in the ZIP (pending)"))
    }

    @Test fun recordReplacesEarlierResultAndIgnoresUnknownIds() {
        var r = ValidationPlan.record(emptyList(), "DTC_SCAN", ValidationStatus.FAILED, 1_000L, "slow")
        r = ValidationPlan.record(r, "DTC_SCAN", ValidationStatus.PASSED, 2_000L)
        assertEquals(1, r.size); assertEquals(ValidationStatus.PASSED, ValidationPlan.statusOf(r, "DTC_SCAN"))
        assertEquals(r, ValidationPlan.record(r, "NOT_A_CHECK", ValidationStatus.PASSED, 3_000L))
    }

    @Test fun codecRoundTripsAndSkipsBadLines() {
        val r = ValidationPlan.record(ValidationPlan.record(emptyList(), "LIVE_DATA", ValidationStatus.PASSED, 5L, "ok | fine"), "TRIP_STOP", ValidationStatus.FAILED, 6L)
        val back = ValidationCodec.decode(ValidationCodec.encode(r))
        assertEquals(2, back.size); assertEquals(ValidationStatus.FAILED, ValidationPlan.statusOf(back, "TRIP_STOP"))
        val dirty = ValidationCodec.encode(r) + "\ngarbage\nLIVE_DATA|MAYBE|1|x\nGONE|PASSED|1|x\nLIVE_DATA|PASSED|notatime|x"
        assertEquals(2, ValidationCodec.decode(dirty).size)
        assertTrue(ValidationCodec.decode(null).isEmpty())
    }

    @Test fun reportListsOwnerResultsOnly() {
        val r = ValidationPlan.record(emptyList(), "FREEZE_FRAME", ValidationStatus.PASSED, 1_700_000_000_000L, "real values")
        val text = ValidationPlan.reportLines(r).joinToString("\n")
        assertTrue(text.contains("1 passed, 0 failed")); assertTrue(text.contains("Freeze frame shows real values: Owner-entered PASS (not independently verified) (2023")); assertTrue(text.contains("real values"))
        assertTrue(text.contains("Fault-code scan shows every code: Not yet tested on the car"))
    }

    @Test fun phaseMarkerLineIsGreppableInTheSessionLog() {
        assertEquals("validation phase: IDLE (Idle)", ValidationPlan.logLine(ValidationPhase.IDLE))
    }

    @Test fun fullReportIncludesValidationSectionOnlyWhenGiven() {
        val base = HealthReport.Input("t", true, "a", emptyList(), null, null, 0.0, emptyList(), dtcScan = DtcScanReport(0L, "", emptyList()))
        assertFalse(HealthReport.lines(base).joinToString("\n").contains("REAL-CAR VALIDATION"))
        assertTrue(HealthReport.lines(base.copy(validation = ValidationPlan.reportLines(emptyList()))).joinToString("\n").contains("REAL-CAR VALIDATION"))
    }
}
