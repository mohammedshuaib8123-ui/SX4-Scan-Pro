package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeSettingsTest {
    @Test fun defaults() {
        val s = WelcomeSettings()
        assertEquals(4800L, s.durationMs(false))
        assertFalse(s.sound)
        assertTrue(s.everyConnection)
    }

    @Test fun durationClamped() {
        assertEquals(2000L, WelcomeSettings(durationSec = 0.1f).durationMs(false))
        assertEquals(15000L, WelcomeSettings(durationSec = 99f).durationMs(false))
        assertEquals(4800L, WelcomeSettings(durationSec = Float.NaN).durationMs(false))
    }

    @Test fun reducedMotionCaps() {
        assertEquals(1200L, WelcomeSettings(durationSec = 10f).durationMs(true))
    }

    @Test fun playRule() {
        assertTrue(WelcomeSettings(everyConnection = true).shouldPlay(true))
        assertTrue(WelcomeSettings(everyConnection = false).shouldPlay(false))
        assertFalse(WelcomeSettings(everyConnection = false).shouldPlay(true))
    }
}
