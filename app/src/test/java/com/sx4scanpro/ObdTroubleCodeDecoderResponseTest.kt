package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdTroubleCodeDecoderResponseTest {
    @Test fun singleKLineFrameWithPadding() {
        val r = ObdTroubleCodeDecoder.decodeResponse("43 01 18 02 34 00 00", "03")
        assertEquals(listOf("P0118", "P0234"), r.codes)
        assertEquals(1, r.validFrames)
        assertTrue(r.unreadable.isEmpty())
    }

    @Test fun moreThanThreeCodesArriveAsSeveralFrames() {
        // K-line carries 3 codes per frame; the old whitespace-stripped parser dropped all of this.
        val raw = "43 01 18 02 34 06 71\r43 01 05 00 00 00 00\r"
        val r = ObdTroubleCodeDecoder.decodeResponse(raw, "03")
        assertEquals(listOf("P0118", "P0234", "P0671", "P0105"), r.codes)
        assertEquals(2, r.validFrames)
        assertTrue(r.unreadable.isEmpty())
    }

    @Test fun ignoresSearchingAndBusInitNoise() {
        val raw = "SEARCHING...\rBUS INIT: ...OK\r43 01 33 00 00 00 00\r"
        val r = ObdTroubleCodeDecoder.decodeResponse(raw, "03")
        assertEquals(listOf("P0133"), r.codes)
        assertTrue(r.unreadable.isEmpty())
    }

    @Test fun busInitErrorIsNotSwallowed() {
        val r = ObdTroubleCodeDecoder.decodeResponse("BUS INIT: ...ERROR\r", "03")
        assertTrue(r.codes.isEmpty())
        assertEquals(listOf("BUSINIT:...ERROR"), r.unreadable)
    }

    @Test fun cleanEcuAnswerIsDistinguishedFromNoAnswer() {
        val clean = ObdTroubleCodeDecoder.decodeResponse("43 00 00 00 00 00 00", "03")
        assertEquals(1, clean.validFrames)
        assertFalse(clean.noData)
        val silent = ObdTroubleCodeDecoder.decodeResponse("NO DATA\r", "03")
        assertEquals(0, silent.validFrames)
        assertTrue(silent.noData)
    }

    @Test fun negativeResponseMeansUnsupported() {
        val r = ObdTroubleCodeDecoder.decodeResponse("7F 0A 11", "0A")
        assertTrue(r.unsupported)
        assertTrue(r.unreadable.isEmpty())
    }

    @Test fun responsePendingIsNotTreatedAsAnAnswer() {
        val r = ObdTroubleCodeDecoder.decodeResponse("7F 03 78", "03")
        assertFalse(r.unsupported)
        assertEquals(listOf("7F0378"), r.unreadable)
    }

    @Test fun canStyleLinesAreReportedNotGuessed() {
        val r = ObdTroubleCodeDecoder.decodeResponse("006\r0: 43 02 01 33\r1: 02 34 00\r", "03")
        assertTrue(r.codes.isEmpty())
        assertEquals(3, r.unreadable.size)
    }

    @Test fun oneBadLineDoesNotHideGoodLines() {
        val r = ObdTroubleCodeDecoder.decodeResponse("43 01 33 00 00 00 00\r43 01 3Z\r", "03")
        assertEquals(listOf("P0133"), r.codes)
        assertEquals(listOf("43013Z"), r.unreadable)
    }

    @Test fun sameCodeOnTwoLinesIsListedOnce() {
        val r = ObdTroubleCodeDecoder.decodeResponse("43 01 33 00 00 00 00\r43 01 33 00 00 00 00", "03")
        assertEquals(listOf("P0133"), r.codes)
    }

    @Test fun nonPowertrainLettersAndDigits() {
        assertEquals(listOf("C0100"), ObdTroubleCodeDecoder.decodeResponse("43 41 00", "03").codes)
        assertEquals(listOf("B1234"), ObdTroubleCodeDecoder.decodeResponse("43 92 34", "03").codes)
        assertEquals(listOf("U0121"), ObdTroubleCodeDecoder.decodeResponse("43 C1 21", "03").codes)
        assertEquals(listOf("P2192"), ObdTroubleCodeDecoder.decodeResponse("43 21 92", "03").codes)
    }

    @Test fun unknownModeIsUnreadable() {
        val r = ObdTroubleCodeDecoder.decodeResponse("43 01 33", "09")
        assertTrue(r.codes.isEmpty())
        assertEquals(1, r.unreadable.size)
    }
}
