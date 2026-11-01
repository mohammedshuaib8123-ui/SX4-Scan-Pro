package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidSupportTest {
    @Test fun decodesThreeSupportedPidBitmapBlocks() {
        val result = ObdPidSupport.decode("4100FFFFFFFF4120FFFFFFFF4140FFFFFFFF")
        assertEquals((1..96).toSet(), result)
    }

    @Test fun ignoresMalformedOrMissingBitmap() {
        assertTrue(ObdPidSupport.decode("NODATA").isEmpty())
        assertTrue(ObdPidSupport.decode("4100ZZZZZZZZ").isEmpty())
    }

    @Test fun doesNotReadSecondBlockUnlessPid32IsAdvertised() {
        val result = ObdPidSupport.decode("4100800000004120FFFFFFFF")
        assertEquals(setOf(1), result)
    }

    @Test fun doesNotReadThirdBlockUnlessPid64IsAdvertised() {
        val result = ObdPidSupport.decode("4100FFFFFFFF4120000000004140FFFFFFFF")
        assertEquals((1..32).toSet(), result)
    }

    @Test fun decodesThirdBlockWhenPid64IsAdvertised() {
        val result = ObdPidSupport.decode("4100FFFFFFFF4120000000014140FFFFFFFF")
        assertEquals((1..32).toSet() + 64 + (65..96).toSet(), result)
    }
}
