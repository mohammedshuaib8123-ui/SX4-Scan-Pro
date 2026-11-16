package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcScanTest {
    private fun c(mode: String, raw: String, framing: Framing = Framing.KLINE) =
        DtcScanClassifier.classify(mode, "X", raw, framing)

    private fun report(vararg m: DtcModeScan) = DtcScanReport(0L, "ISO 14230-4", m.toList())
    private fun m(mode: String, cat: String, status: DtcModeStatus, codes: List<String> = emptyList()) =
        DtcModeScan(mode, cat, status, codes, emptyList(), "")

    // ---- one service ----

    @Test fun singleCodeFrame() {
        val r = c("03", "43 01 18 00 00 00 00")
        assertEquals(DtcModeStatus.CODES_REPORTED, r.status)
        assertEquals(listOf("P0118"), r.codes)
    }

    @Test fun multipleCodesAcrossKLineFrames() {
        val r = c("03", "43 01 18 02 34 01 20\r43 01 21 00 00 00 00")
        assertEquals(DtcModeStatus.CODES_REPORTED, r.status)
        assertEquals(listOf("P0118", "P0234", "P0120", "P0121"), r.codes)
    }

    @Test fun validReplyWithNoCodesIsValidEmpty() {
        val r = c("07", "47 00 00 00 00 00 00")
        assertEquals(DtcModeStatus.VALID_EMPTY, r.status)
        assertTrue(r.codes.isEmpty())
        assertTrue(r.status.answered)
    }

    @Test fun noDataIsNotAnEmptyScan() {
        val r = c("0A", "NO DATA")
        assertEquals(DtcModeStatus.NO_DATA, r.status)
        assertFalse(r.status.answered)
    }

    @Test fun negativeResponseIsUnsupportedNotMalformed() {
        assertEquals(DtcModeStatus.UNSUPPORTED, c("07", "7F 07 11").status)
        assertEquals(DtcModeStatus.UNSUPPORTED, c("0A", "7F 0A 11").status)
    }

    @Test fun responsePendingIsNotAnAnswer() {
        val r = c("03", "7F 03 78")
        assertEquals(DtcModeStatus.MALFORMED, r.status)
    }

    @Test fun garbageAndEmptyAreMalformed() {
        assertEquals(DtcModeStatus.MALFORMED, c("03", "AB CD EF").status)
        val e = c("03", "")
        assertEquals(DtcModeStatus.MALFORMED, e.status)
        assertEquals("empty reply", e.detail)
        assertFalse(e.status.answered)
    }

    @Test fun partlyReadableKeepsCodesButIsFlagged() {
        val r = c("03", "43 01 18 00 00 00 00\rZZ 12")
        assertEquals(DtcModeStatus.PARTIAL, r.status)
        assertEquals(listOf("P0118"), r.codes)
        assertEquals(1, r.unreadable.size)
        assertTrue(r.detail.contains("1 reply line"))
    }

    @Test fun rawReplyIsPreservedVerbatim() {
        val raw = "43 01 18 00 00 00 00"
        assertEquals(raw, c("03", raw).raw)
    }

    @Test fun transportFailureNeverLooksLikeAnAnswer() {
        val f = DtcScanClassifier.failure("03", "STORED", "timed out")
        assertEquals(DtcModeStatus.TRANSPORT_FAILURE, f.status)
        assertFalse(f.status.answered)
        assertTrue(f.codes.isEmpty())
    }

    // ---- CAN ----

    @Test fun canSingleFrameWithCount() {
        val r = c("03", "43 02 01 18 02 34", Framing.CAN)
        assertEquals(DtcModeStatus.CODES_REPORTED, r.status)
        assertEquals(listOf("P0118", "P0234"), r.codes)
    }

    @Test fun canZeroCountIsValidEmpty() {
        assertEquals(DtcModeStatus.VALID_EMPTY, c("03", "43 00", Framing.CAN).status)
    }

    @Test fun canMultiFrameFourCodes() {
        val r = c("03", "00A\r0: 43 04 01 18 02 34\r1: 01 20 01 21", Framing.CAN)
        assertEquals(DtcModeStatus.CODES_REPORTED, r.status)
        assertEquals(listOf("P0118", "P0234", "P0120", "P0121"), r.codes)
    }

    @Test fun canTruncatedMultiFrameIsMalformedNotEmpty() {
        val r = c("03", "00A\r0: 43 04 01 18 02 34", Framing.CAN)
        assertEquals(DtcModeStatus.MALFORMED, r.status)
        assertTrue(r.codes.isEmpty())
    }

    @Test fun zeroPaddingDoesNotCreateCodes() {
        val r = c("03", "43 01 18 00 00 00 00")
        assertEquals(1, r.codes.size)
    }

    // ---- whole scan ----

    @Test fun allThreeValidEmptyMeansEngineEcuReportsNoCodes() {
        val s = report(
            m("03", "STORED", DtcModeStatus.VALID_EMPTY), m("07", "PENDING", DtcModeStatus.VALID_EMPTY), m("0A", "PERMANENT", DtcModeStatus.VALID_EMPTY)
        )
        assertTrue(s.engineEcuReportsNoCodes)
        assertTrue(s.headline().contains("Other modules were not scanned"))
        assertFalse(s.headline().lowercase().contains("fault-free"))
        assertFalse(s.headline().lowercase().contains("no faults"))
    }

    @Test fun onlyOneServiceAnsweringIsNotAnAllClear() {
        val s = report(
            m("03", "STORED", DtcModeStatus.VALID_EMPTY), m("07", "PENDING", DtcModeStatus.NO_DATA), m("0A", "PERMANENT", DtcModeStatus.TRANSPORT_FAILURE)
        )
        assertFalse(s.engineEcuReportsNoCodes)
        assertFalse(s.isComplete)
        assertTrue(s.hasAnyAnswer)
        assertTrue(s.headline().contains("NOT confirmed"))
    }

    @Test fun noAnswerAtAllSaysScanTellsNothing() {
        val s = report(
            m("03", "STORED", DtcModeStatus.NO_DATA), m("07", "PENDING", DtcModeStatus.MALFORMED), m("0A", "PERMANENT", DtcModeStatus.UNSUPPORTED)
        )
        assertFalse(s.hasAnyAnswer)
        assertFalse(s.engineEcuReportsNoCodes)
        assertTrue(s.headline().contains("tells you nothing"))
    }

    @Test fun codesAreAssociatedWithTheirService() {
        val s = report(
            m("03", "STORED", DtcModeStatus.CODES_REPORTED, listOf("P0118", "P0118", "P0234")),
            m("07", "PENDING", DtcModeStatus.CODES_REPORTED, listOf("P0118")),
            m("0A", "PERMANENT", DtcModeStatus.VALID_EMPTY)
        )
        assertEquals(
            listOf(ReportedDtc("STORED", "P0118"), ReportedDtc("STORED", "P0234"), ReportedDtc("PENDING", "P0118")),
            s.codes
        )
        assertTrue(s.isComplete)
        assertFalse(s.engineEcuReportsNoCodes)
    }

    @Test fun partialServiceMakesScanIncompleteButKeepsCodes() {
        val s = report(
            m("03", "STORED", DtcModeStatus.PARTIAL, listOf("P0118")), m("07", "PENDING", DtcModeStatus.VALID_EMPTY), m("0A", "PERMANENT", DtcModeStatus.VALID_EMPTY)
        )
        assertFalse(s.isComplete)
        assertEquals(1, s.codes.size)
        assertTrue(s.headline().contains("incomplete"))
    }

    @Test fun statusTextNamesEveryState() {
        DtcModeStatus.values().forEach { assertTrue(DtcScanText.status(it).isNotBlank()) }
        assertTrue(DtcScanText.status(DtcModeStatus.NO_DATA).contains("no information"))
    }

    @Test fun modeLineShowsCountAndDetail() {
        val line = DtcScanText.modeLine(DtcModeScan("03", "STORED", DtcModeStatus.PARTIAL, listOf("P0118"), listOf("ZZ"), "", "1 reply line(s) could not be read"))
        assertTrue(line.contains("Mode 03 (STORED)"))
        assertTrue(line.contains("(1)"))
        assertTrue(line.contains("could not be read"))
    }
}
