package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidSupportTest {
    @Test fun decodesBothSupportedPidBitmapBlocks() {
        val result = ObdPidSupport.decode("4100FFFFFFFF4120FFFFFFFF")
        assertEquals((1..64).toSet(), result)
    }

    @Test fun ignoresMalformedOrMissingBitmap() {
        assertTrue(ObdPidSupport.decode("NODATA").isEmpty())
        assertTrue(ObdPidSupport.decode("4100ZZZZZZZZ").isEmpty())
    }

    @Test fun doesNotReadSecondBlockUnlessPid32IsAdvertised() {
        val result = ObdPidSupport.decode("4100800000004120FFFFFFFF")
        assertEquals(setOf(1), result)
    }
}
