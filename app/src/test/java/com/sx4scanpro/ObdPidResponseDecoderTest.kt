package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdPidResponseDecoderTest {
    @Test fun decodesCommonSingleAndDoubleBytePids() {
        assertEquals("2000", ObdPidResponseDecoder.decode("010C", "410C1F40"))
        assertEquals("100", ObdPidResponseDecoder.decode("010D", "410D64"))
        assertEquals("60", ObdPidResponseDecoder.decode("0105", "410564"))
        assertEquals("12.34", ObdPidResponseDecoder.decode("0110", "411004D2"))
        assertEquals("100.0", ObdPidResponseDecoder.decode("0104", "4104FF"))
        assertEquals("101", ObdPidResponseDecoder.decode("0133", "413365"))
    }

    @Test fun rejectsWrongPidAndWrongService() {
        assertNull(ObdPidResponseDecoder.decode("010C", "410D1F40"))
        assertNull(ObdPidResponseDecoder.decode("010C", "430C1F40"))
    }

    @Test fun rejectsShortLongAndMalformedPayloads() {
        assertNull(ObdPidResponseDecoder.decode("010C", "410C1F"))
        assertNull(ObdPidResponseDecoder.decode("010C", "410C1F40AA"))
        assertNull(ObdPidResponseDecoder.decode("010D", "410DGG"))
    }

    @Test fun rejectsUnsupportedCommands() {
        assertNull(ObdPidResponseDecoder.decode("020C", "420C1F40"))
        assertNull(ObdPidResponseDecoder.decode("01ZZ", "41ZZ00"))
    }
}
