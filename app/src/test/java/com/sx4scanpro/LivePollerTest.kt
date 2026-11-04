package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

private class FakePort(val replies: MutableMap<String, String> = HashMap()) : ElmPort {
    val sent = ArrayList<String>()
    var timeout = false
    override fun send(command: String, timeoutMs: Long): ElmResult {
        sent += command
        if (timeout) return ElmResult(command, "", ElmKind.EMPTY, timeoutMs, true)
        val raw = replies[command] ?: "NO DATA"
        return ElmResult(command, raw, ElmReply.classify(raw), 5)
    }
}

class PollSchedulerTest {
    @Test fun mostOverdueFirstAndNothingBeforeDue() {
        val s = PollScheduler()
        s.set(0x0C, 300); s.set(0x05, 4000)
        assertEquals(0x0C, s.next(0))
        s.markPolled(0x0C, 0); s.markPolled(0x05, 0)
        assertNull(s.next(100))
        assertEquals(0x0C, s.next(300))
        assertEquals(200L, s.waitMs(100))
    }
    @Test fun allowedFilterSkipsParkedPids() {
        val s = PollScheduler(); s.set(1, 100); s.set(2, 100)
        assertEquals(2, s.next(0) { it == 2 })
    }
}

class PidCapabilitiesTest {
    @Test fun parksAfterRepeatedNoDataThenRetries() {
        val c = PidCapabilities(3, 1000); c.setAdvertised(setOf(0x0C))
        assertEquals(PidState.NOT_ADVERTISED, c.stateOf(0x05, 0))
        repeat(3) { c.recordNoData(0x0C, 0) }
        assertEquals(PidState.TEMP_UNAVAILABLE, c.stateOf(0x0C, 500))
        assertEquals(PidState.READY, c.stateOf(0x0C, 1000))
        c.recordSuccess(0x0C)
        assertTrue(c.isPollable(0x0C, 1000))
    }
}

class LivePollerTest {
    private fun poller(port: FakePort): LivePoller {
        val sch = PollScheduler().apply { set(0x0C, 300); set(0x05, 4000) }
        val cap = PidCapabilities().apply { setAdvertised(setOf(0x0C, 0x05)) }
        return LivePoller(port, sch, cap, lightReinit = listOf("ATPC"), fullReinit = listOf("ATZ"))
    }

    @Test fun decodesRealReplyAndStampsTime() {
        val port = FakePort(hashMapOf("010C" to "41 0C 1A F8", "0105" to "41 05 7B"))
        val p = poller(port)
        val ev = p.step(1000) as PollEvent.Polled
        assertEquals(1726.0, ev.reading!!.value, 0.001)
        assertEquals(1000L, p.reading(0x0C)!!.atMs)
    }

    @Test fun noDataKeepsOldReadingWithOldTimestamp() {
        val port = FakePort(hashMapOf("010C" to "41 0C 1A F8"))
        val p = poller(port)
        p.step(1000)
        port.replies["010C"] = "NO DATA"
        p.step(1400)
        assertEquals(1000L, p.reading(0x0C)!!.atMs)
    }

    @Test fun ecuSilenceStartsRecoveryThenResumesWithoutReconnect() {
        val port = FakePort()
        val p = poller(port)
        var t = 0L
        repeat(10) { p.step(t); t += 5000 }
        assertEquals(LinkState.ECU_SILENT, p.monitor.state)
        port.replies["0100"] = "41 00 BE 3E B8 11"
        var recovered = false
        repeat(6) { if (!recovered) { val e = p.step(t); t += 1000; recovered = e is PollEvent.Recovery && e.recovered } }
        assertTrue(recovered)
        assertEquals(LinkState.COMMUNICATING, p.monitor.state)
    }

    @Test fun onlyOneCommandPerStep() {
        val port = FakePort(hashMapOf("010C" to "41 0C 00 00"))
        poller(port).step(0)
        assertEquals(1, port.sent.size)
    }

    @Test fun adapterTimeoutsFlagAdapterSilent() {
        val port = FakePort(); port.timeout = true
        val p = poller(port)
        p.step(0); p.step(300)
        assertEquals(LinkState.ADAPTER_SILENT, p.monitor.state)
    }
}
