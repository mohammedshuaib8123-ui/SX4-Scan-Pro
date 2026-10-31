package com.sx4scanpro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStartDetectorTest {
    @Test fun requiresTwoFreshPositiveSamples() {
        val detector = EngineStartDetector()
        assertFalse(detector.update(850, true))
        assertTrue(detector.update(860, true))
    }

    @Test fun staleOrMissingSamplesCannotTrigger() {
        val detector = EngineStartDetector()
        assertFalse(detector.update(900, false))
        assertFalse(detector.update(null, true))
        assertFalse(detector.update(900, true))
        assertTrue(detector.update(900, true))
    }

    @Test fun announcesOnlyOnceUntilFiveZeroSamplesConfirmStop() {
        val detector = EngineStartDetector()
        assertFalse(detector.update(800, true))
        assertTrue(detector.update(800, true))
        repeat(8) { assertFalse(detector.update(820, true)) }
        repeat(4) { assertFalse(detector.update(0, true)) }
        assertFalse(detector.update(0, true))
        assertFalse(detector.update(800, true))
        assertTrue(detector.update(800, true))
    }

    @Test fun invalidNegativeRpmDoesNotChangeState() {
        val detector = EngineStartDetector()
        assertFalse(detector.update(-1, true))
        assertFalse(detector.update(700, true))
        assertTrue(detector.update(700, true))
    }
}
