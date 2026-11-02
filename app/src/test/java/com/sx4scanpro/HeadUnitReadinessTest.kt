package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitReadinessTest {
    private val phone = UsbSeen("Pixel", 0x18D1, 0x4EE1)

    @Test fun readyOnlyWhenEveryPreconditionHolds() {
        assertTrue(HeadUnitReadiness(true, true, listOf(phone)).readyToStart)
        assertFalse(HeadUnitReadiness(false, true, listOf(phone)).readyToStart)
        assertFalse(HeadUnitReadiness(true, false, listOf(phone)).readyToStart)
        assertFalse(HeadUnitReadiness(true, true, emptyList()).readyToStart)
    }

    @Test fun nextStepFollowsTheFirstMissingPrecondition() {
        assertTrue(HeadUnitReadiness(false, false, emptyList()).nextStep.contains("Install"))
        assertTrue(HeadUnitReadiness(true, false, emptyList()).nextStep.contains("USB host"))
        assertTrue(HeadUnitReadiness(true, true, emptyList()).nextStep.contains("data cable"))
    }

    @Test fun readyMessageDoesNotPromiseProjection() {
        val step = HeadUnitReadiness(true, true, listOf(phone)).nextStep
        assertTrue(step, step.contains("not verified"))
    }

    @Test fun deviceLabelShowsVendorAndProductInHex() {
        assertEquals("Pixel • VID 18D1 / PID 4EE1", phone.label)
        assertTrue(HeadUnitReadiness(true, true, listOf(phone)).checks.last().detail.contains("18D1"))
    }

    @Test fun threeChecksInStableOrder() {
        assertEquals(listOf("Receiver app", "USB host mode", "Phone on USB"), HeadUnitReadiness(true, true, emptyList()).checks.map { it.title })
    }
}
