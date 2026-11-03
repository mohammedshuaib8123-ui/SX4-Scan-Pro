package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnitionTimelineTest {
    @Test fun laserThenSplitThenUnfold() {
        assertEquals(0f, IgnitionTimeline.laser(0f), 0f)
        assertEquals(1f, IgnitionTimeline.laser(0.6f), 1e-5f)
        assertEquals(0f, IgnitionTimeline.split(0.6f), 1e-5f)
        assertEquals(1f, IgnitionTimeline.split(1.2f), 1e-5f)
        assertEquals(0f, IgnitionTimeline.unfold(1.2f), 1e-5f)
        assertEquals(1f, IgnitionTimeline.unfold(2.5f), 1e-5f)
    }

    @Test fun sweepGoesToFullScaleThenSettlesOnLive() {
        assertEquals(0f, IgnitionTimeline.sweep(2.5f, 0.4f), 1e-5f)
        assertEquals(1f, IgnitionTimeline.sweep(3.5f, 0.4f), 1e-5f)
        assertEquals(0.4f, IgnitionTimeline.sweep(4.5f, 0.4f), 1e-5f)
        assertEquals(0.4f, IgnitionTimeline.sweep(6f, 0.4f), 1e-5f)
    }

    @Test fun offlineSettlesAtZeroNeverInventsAReading() {
        assertEquals(0f, IgnitionTimeline.sweep(5f, 0f), 1e-5f)
        assertEquals(1f, IgnitionTimeline.sweep(3.5f, 0f), 1e-5f)
    }

    @Test fun liveOutOfRangeIsClamped() {
        assertEquals(1f, IgnitionTimeline.sweep(5f, 7f), 1e-5f)
        assertEquals(0f, IgnitionTimeline.sweep(5f, -3f), 1e-5f)
    }

    @Test fun typingReveals() {
        assertEquals(0, IgnitionTimeline.typed(1.0f, 10, 1.2f, 2.2f))
        assertEquals(5, IgnitionTimeline.typed(1.7f, 10, 1.2f, 2.2f))
        assertEquals(10, IgnitionTimeline.typed(3f, 10, 1.2f, 2.2f))
    }

    @Test fun calibrationWindow() {
        assertFalse(IgnitionTimeline.calibrating(2.0f))
        assertTrue(IgnitionTimeline.calibrating(3.0f))
        assertFalse(IgnitionTimeline.calibrating(5.0f))
        assertTrue(IgnitionTimeline.finished(4.5f))
        assertFalse(IgnitionTimeline.finished(4.4f))
    }
}
