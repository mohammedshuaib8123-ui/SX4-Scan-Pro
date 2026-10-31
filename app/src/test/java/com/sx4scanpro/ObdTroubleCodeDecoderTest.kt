package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdTroubleCodeDecoderTest {
    @Test fun decodesStandardPowertrainCode() {
        assertEquals(listOf("P0133"), ObdTroubleCodeDecoder.decode("43 01 33", "03"))
    }

    @Test fun decodesPendingAndPermanentModes() {
        assertEquals(listOf("P0301"), ObdTroubleCodeDecoder.decode("47 03 01", "07"))
        assertEquals(listOf("P0420"), ObdTroubleCodeDecoder.decode("4A 04 20", "0A"))
    }

    @Test fun ignoresAllZeroPadding() {
        assertTrue(ObdTroubleCodeDecoder.decode("43 00 00 00 00", "03").isEmpty())
    }

    @Test fun ignoresNoDataAndWrongPositivePrefix() {
        assertTrue(ObdTroubleCodeDecoder.decode("NODATA", "03").isEmpty())
        assertTrue(ObdTroubleCodeDecoder.decode("47 01 33", "03").isEmpty())
    }

    @Test fun rejectsMalformedOrUnalignedPayload() {
        assertTrue(ObdTroubleCodeDecoder.decode("43 01 3Z", "03").isEmpty())
        assertTrue(ObdTroubleCodeDecoder.decode("43 01", "03").isEmpty())
        assertTrue(ObdTroubleCodeDecoder.decode("43 01 33 0", "03").isEmpty())
    }

    @Test fun rejectsUnknownMode() {
        assertTrue(ObdTroubleCodeDecoder.decode("43 01 33", "09").isEmpty())
    }

    @Test fun doesNotGuessAtHeaderOrIsoTpCountBytes() {
        // A prefixed PCI/count byte is not silently reinterpreted as a DTC.
        assertTrue(ObdTroubleCodeDecoder.decode("03 43 01 33", "03").isEmpty())
    }
}
