package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderPollingTest {
    private class Port(val replies: Map<String, String>) : ElmPort {
        val sent = ArrayList<String>()
        override fun send(command: String, timeoutMs: Long): ElmResult {
            sent += command
            val raw = replies[command] ?: "NO DATA"
            return ElmResult(command, raw, ElmReply.classify(raw), 5)
        }
    }

    private fun poller(port: Port): LivePoller {
        val caps = PidCapabilities(3, 1000); caps.setAdvertised(setOf(0x0D))
        val sched = PollScheduler(); sched.set(0x0D, 1000L)
        return LivePoller(port, sched, caps)
    }

    @Test fun theReadingKeepsTheVerbatimEcuReply() {
        val port = Port(mapOf("010D" to "SEARCHING...\r410D2A\r"))
        val ev = poller(port).step(0) as PollEvent.Polled
        val r = ev.reading!!
        assertEquals(42.0, r.value, 0.0)
        assertEquals("SEARCHING...\r410D2A\r", r.raw)
        assertEquals(listOf("010D"), port.sent)                            // one request, no duplicate
    }

    @Test fun aZeroSpeedReplyIsAReadingNotAMissingValue() {
        val ev = poller(Port(mapOf("010D" to "410D00"))).step(0) as PollEvent.Polled
        assertEquals(0.0, ev.reading!!.value, 0.0)
    }

    @Test fun aReplyForAnotherPidOrAMalformedReplyIsNotAReading() {
        for (raw in listOf("410C1F40", "410D", "410D2A2A", "410DZZ", "7F0112")) {
            val ev = poller(Port(mapOf("010D" to raw))).step(0) as PollEvent.Polled
            assertNull(raw, ev.reading)
        }
    }

    @Test fun noDataLeavesThePreviousReadingWithItsOldTimestamp() {
        val port = Port(mapOf("010D" to "410D2A"))
        val p = poller(port)
        p.step(0)
        val port2 = Port(emptyMap())
        val p2 = poller(port2)
        assertNull((p2.step(0) as PollEvent.Polled).reading)
        assertNotNull(p.reading(0x0D)); assertEquals(0L, p.reading(0x0D)!!.atMs)
    }
}
