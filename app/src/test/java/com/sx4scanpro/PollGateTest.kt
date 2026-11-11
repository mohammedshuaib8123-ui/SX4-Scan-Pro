package com.sx4scanpro

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollGateTest {
    private fun thread(body: () -> Unit) = Thread(body).also { it.isDaemon = true; it.start() }

    @Test fun pauseWaitsForTheSliceInFlight() {
        val gate = PollGate()
        val inSlice = CountDownLatch(1); val release = CountDownLatch(1)
        thread { assertTrue(gate.tryEnterSlice()); inSlice.countDown(); release.await(); gate.exitSlice() }
        assertTrue(inSlice.await(2, TimeUnit.SECONDS))
        val paused = AtomicBoolean(false)
        val pauser = thread { paused.set(gate.pause(5_000)) }
        Thread.sleep(150)
        assertFalse("pause must still be waiting for the slice", paused.get())
        release.countDown()
        pauser.join(2_000)
        assertTrue(paused.get())
        assertTrue(gate.isPaused())
    }

    @Test fun pauseFromTheSliceThreadItselfDoesNotDeadlock() {
        val gate = PollGate()
        val result = AtomicReference<Boolean?>(null)
        val t = thread {
            assertTrue(gate.tryEnterSlice())
            try { result.set(gate.pause(10_000)) } finally { gate.resume(); gate.exitSlice() }
        }
        t.join(3_000)
        assertFalse("thread must have finished", t.isAlive)
        assertEquals(true, result.get())
        assertFalse(gate.isPaused())
    }

    @Test fun newSlicesAreRefusedWhilePausedAndAllowedAfterResume() {
        val gate = PollGate()
        assertTrue(gate.pause(1_000))
        assertFalse(gate.tryEnterSlice())
        gate.resume()
        assertTrue(gate.tryEnterSlice())
        gate.exitSlice()
    }

    @Test fun resumeIsIdempotentAndNestedPausesAreCounted() {
        val gate = PollGate()
        gate.resume(); gate.resume()
        assertFalse(gate.isPaused())
        assertTrue(gate.pause(1_000)); assertTrue(gate.pause(1_000))
        gate.resume()
        assertTrue(gate.isPaused())
        gate.resume()
        assertFalse(gate.isPaused())
        assertTrue(gate.tryEnterSlice())
    }

    @Test fun aTimedOutPauseHoldsNothing() {
        val gate = PollGate()
        val inSlice = CountDownLatch(1); val release = CountDownLatch(1)
        thread { gate.tryEnterSlice(); inSlice.countDown(); release.await(); gate.exitSlice() }
        assertTrue(inSlice.await(2, TimeUnit.SECONDS))
        assertFalse(gate.pause(100))
        assertFalse(gate.isPaused())
        release.countDown()
        Thread.sleep(100)
        assertTrue(gate.tryEnterSlice())
    }
}
