package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

/** Scripted adapter: a reply and a round-trip time per command, counting commands in flight. */
private class PerfPort : ElmPort {
    val sent = ArrayList<String>()
    val replies = HashMap<String, String>()
    val rtt = HashMap<String, Long>()
    val timeouts = HashSet<String>()
    var inFlight = 0; var maxInFlight = 0
    var clock = 0L
    override fun send(command: String, timeoutMs: Long): ElmResult {
        inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
        sent += command
        val ms = rtt[command] ?: 40L
        clock += ms
        inFlight--
        if (command in timeouts) return ElmResult(command, "", ElmKind.EMPTY, timeoutMs, true)
        val raw = replies[command] ?: "NO DATA"
        return ElmResult(command, raw, ElmReply.classify(raw), ms)
    }
}

private val ALL = setOf(0x0C, 0x0D, 0x05, 0x04, 0x0B, 0x10, 0x0F, 0x11, 0x2C)

private fun fullPoller(port: PerfPort, advertised: Set<Int> = ALL): LivePoller {
    val sch = PollScheduler().also { PollPlan.configure(it, emptySet()) }
    val cap = PidCapabilities().also { it.setAdvertised(advertised) }
    return LivePoller(port, sch, cap)
}

private fun answerAll(p: PerfPort) {
    p.replies["010C"] = "41 0C 1A F8"; p.replies["010D"] = "41 0D 32"; p.replies["0105"] = "41 05 7B"
    p.replies["0104"] = "41 04 80"; p.replies["010B"] = "41 0B 64"; p.replies["0110"] = "41 10 01 90"
    p.replies["010F"] = "41 0F 50"; p.replies["0111"] = "41 11 40"; p.replies["012C"] = "41 2C 00"
}

class PollPriorityTest {
    @Test fun planPrioritiesMatchTheOwnerOrder() {
        assertEquals(0, PollPlan.priorityOf(0x0C)); assertEquals(0, PollPlan.priorityOf(0x0D))
        assertEquals(1, PollPlan.priorityOf(0x05)); assertEquals(1, PollPlan.priorityOf(0x04))
        assertEquals(2, PollPlan.priorityOf(0x0B)); assertEquals(2, PollPlan.priorityOf(0x10))
        assertEquals(3, PollPlan.priorityOf(0x0F)); assertEquals(3, PollPlan.priorityOf(0x42))
    }

    @Test fun configureAppliesPrioritiesAndKeepsUserEnabledExtras() {
        val s = PollScheduler(); PollPlan.configure(s, setOf(0x42, 0x5C))
        assertTrue(0x42 in s.pids() && 0x5C in s.pids())
        assertEquals(3, s.priorityOf(0x5C)); assertEquals(0, s.priorityOf(0x0C))
    }

    @Test fun criticalPidWinsWhenSeveralAreDue() {
        val s = PollScheduler()
        s.set(0x42, 100); s.set(0x10, 100); s.set(0x05, 100); s.set(0x0D, 100)
        listOf(0x42, 0x10, 0x05, 0x0D).forEach { s.setPriority(it, PollPlan.priorityOf(it)) }
        val order = ArrayList<Int>()
        repeat(4) { val n = s.next(0)!!; order += n; s.markPolled(n, 0) }
        assertEquals(listOf(0x0D, 0x05, 0x10, 0x42), order)
    }

    @Test fun optionalSensorsDoNotStarveRpmAndSpeedOverALongRun() {
        val port = PerfPort().also { answerAll(it) }
        val extras = (0x20..0x2B).toSet() + setOf(0x42, 0x5C)
        val sch = PollScheduler().also { PollPlan.configure(it, extras) }
        val cap = PidCapabilities().also { it.setAdvertised(ALL + extras) }
        val p = LivePoller(port, sch, cap)
        var now = 0L
        val rpmAt = ArrayList<Long>()
        while (now < 20_000) {
            val ev = p.step(now)
            if (ev is PollEvent.Polled && ev.pid == 0x0C) rpmAt += now
            now += 60
        }
        val gaps = rpmAt.zipWithNext { a, b -> b - a }
        assertTrue("rpm polled often enough", rpmAt.size > 15)
        assertTrue("rpm gap bounded: ${gaps.maxOrNull()}", gaps.maxOrNull()!! <= 1_500)
    }

    @Test fun starvedOptionalPidIsEventuallyServed() {
        val s = PollScheduler()
        s.set(0x0C, 10); s.setPriority(0x0C, 0)
        s.set(0x42, 100); s.setPriority(0x42, 3)
        var served = false; var now = 0L
        while (now < 2_000 && !served) {
            val n = s.next(now)
            if (n != null) { s.markPolled(n, now); if (n == 0x42) served = true }
            now += 1
        }
        assertTrue(served)
    }

    @Test fun userDisabledSensorsAreNeverPolled() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        var now = 0L
        repeat(200) { p.step(now); now += 100 }
        assertTrue(port.sent.none { it == "0142" || it == "015C" })
    }
}

class AdaptivePollingTest {
    @Test fun repeatedPidFailureBacksOffThenRecovers() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        val base = p.scheduler.intervalOf(0x10)!!
        var now = 0L
        // keep the rest of the ECU answering; MAF returns garbage
        port.replies["0110"] = "41 10"
        repeat(80) { p.step(now); now += 100 }
        assertTrue("backed off: ${p.scheduler.effectiveIntervalOf(0x10)}", p.scheduler.effectiveIntervalOf(0x10)!! > base)
        assertTrue(p.scheduler.effectiveIntervalOf(0x10)!! <= base * 8)
        assertEquals(p.scheduler.intervalOf(0x0C), p.scheduler.effectiveIntervalOf(0x0C))
        port.replies["0110"] = "41 10 01 90"
        repeat(900) { p.step(now); now += 100 }   // the parked PID is retried after its 60 s pause, answers, and is back on plan
        assertEquals(base, p.scheduler.effectiveIntervalOf(0x10))
    }

    @Test fun backoffIsNeverFasterThanThePlan() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        repeat(100) { p.step(it * 100L) }
        for (pid in p.scheduler.pids()) assertTrue(p.scheduler.effectiveIntervalOf(pid)!! >= p.scheduler.intervalOf(pid)!!)
    }

    @Test fun slowAdapterStretchesOptionalPidsButNotCriticalOnes() {
        val port = PerfPort().also { answerAll(it) }
        ALL.forEach { port.rtt["01%02X".format(it)] = 700L }
        val p = fullPoller(port)
        var now = 0L
        repeat(120) { p.step(now); now += 750 }
        assertEquals(p.scheduler.intervalOf(0x0C), p.scheduler.effectiveIntervalOf(0x0C))
        assertEquals(p.scheduler.intervalOf(0x0D), p.scheduler.effectiveIntervalOf(0x0D))
        assertEquals(p.scheduler.intervalOf(0x05), p.scheduler.effectiveIntervalOf(0x05))
        assertTrue(p.scheduler.effectiveIntervalOf(0x0B)!! > p.scheduler.intervalOf(0x0B)!!)
        assertTrue(p.scheduler.effectiveIntervalOf(0x0B)!! <= 2 * p.scheduler.intervalOf(0x0B)!!)
    }

    @Test fun fastAdapterKeepsThePlannedIntervals() {
        val port = PerfPort().also { answerAll(it) }
        ALL.forEach { port.rtt["01%02X".format(it)] = 15L }
        val p = fullPoller(port)
        repeat(150) { p.step(it * 100L) }
        for (pid in p.scheduler.pids()) assertEquals(p.scheduler.intervalOf(pid), p.scheduler.effectiveIntervalOf(pid))
    }

    @Test fun timeoutsBackOffOnlyThatPid() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        port.timeouts += "0111"
        var now = 0L
        repeat(60) { p.step(now); now += 100 }
        assertTrue(p.scheduler.effectiveIntervalOf(0x11)!! >= p.scheduler.intervalOf(0x11)!!)
        assertEquals(p.scheduler.intervalOf(0x0C), p.scheduler.effectiveIntervalOf(0x0C))
    }

    @Test fun garbagePidIsParkedAndNotRequestedEveryInterval() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        port.replies["0111"] = "41 11"
        var now = 0L
        repeat(300) { p.step(now); now += 100 }   // 30 s
        val asked = port.sent.count { it == "0111" }
        assertTrue("0111 requested $asked times", asked <= 10)
    }
}

class UnsupportedAndDuplicateTest {
    @Test fun notAdvertisedPidsAreNeverRequested() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port, advertised = setOf(0x0C, 0x0D, 0x05))
        repeat(200) { p.step(it * 100L) }
        assertTrue(port.sent.all { it in setOf("010C", "010D", "0105") })
    }

    @Test fun repeatedNoDataParksPidWhileOthersAnswer() {
        val port = PerfPort().also { answerAll(it) }
        port.replies.remove("0111")
        val p = fullPoller(port)
        repeat(300) { p.step(it * 100L) }
        assertTrue("requested ${port.sent.count { it == "0111" }}", port.sent.count { it == "0111" } < 10)
        assertTrue(port.sent.count { it == "010C" } > 30)
    }

    @Test fun unknownCommandReplyIsParkedLong() {
        val port = PerfPort().also { answerAll(it) }
        port.replies["0111"] = "?"
        val p = fullPoller(port)
        repeat(300) { p.step(it * 100L) }
        assertTrue(port.sent.count { it == "0111" } <= 3)
    }

    @Test fun neverTwoCommandsInFlightAndNoPidTwiceInOneStep() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        repeat(300) { p.step(it * 50L) }
        assertEquals(1, port.maxInFlight)
        // the same PID is never asked back to back while others are due
        val s = port.sent
        val backToBack = (1 until s.size).count { s[it] == s[it - 1] && s[it] != "010C" }
        assertEquals(0, backToBack)
    }

    @Test fun reentrantStepSendsNothing() {
        lateinit var p: LivePoller
        var inner: PollEvent? = null
        val port = object : ElmPort {
            override fun send(command: String, timeoutMs: Long): ElmResult {
                inner = p.step(1_000)
                return ElmResult(command, "41 0C 1A F8", ElmKind.DATA, 5)
            }
        }
        val sch = PollScheduler().also { it.set(0x0C, 100); it.set(0x0D, 100) }
        p = LivePoller(port, sch, PidCapabilities().also { it.setAdvertised(setOf(0x0C, 0x0D)) })
        p.step(0)
        assertTrue(inner is PollEvent.Idle)
    }
}

class FreshnessAndRecoveryTest {
    @Test fun readingGoesStaleThenNeverBecomesZero() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        p.step(0)
        val r = p.reading(0x0C)!!
        val live = GaugeAssembler.build({ p.reading(it) }, ALL, emptySet(), 1_000).first { it.label == "Engine RPM" }
        assertTrue(live.available)
        val stale = GaugeAssembler.build({ p.reading(it) }, ALL, emptySet(), r.atMs + 60_000).first { it.label == "Engine RPM" }
        assertFalse(stale.available); assertEquals("--", stale.value)
    }

    @Test fun failedReadKeepsOldTimestampAndMakesNoZero() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        p.step(0)
        port.replies["010C"] = "NO DATA"
        p.step(700)
        val r = p.reading(0x0C)!!
        assertEquals(0L, r.atMs); assertTrue(r.value > 0.0)
    }

    @Test fun silentEcuStopsPidRequestsAndRecoveryClearsBackoff() {
        val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        var now = 0L
        repeat(10) { p.step(now); now += 100 }
        // ignition off: nothing answers
        port.replies.clear()
        var recoveries = 0
        var guard = 0
        while (p.monitor.state == LinkState.COMMUNICATING && guard++ < 200) { p.step(now); now += 100 }
        assertNotEquals(LinkState.COMMUNICATING, p.monitor.state)
        val before = port.sent.size
        repeat(3) { val ev = p.step(now); now += 1000; if (ev is PollEvent.Recovery) recoveries++ }
        assertEquals(3, recoveries)
        assertTrue(port.sent.drop(before).none { it in setOf("010D", "0105", "0104") })   // only probe/reinit traffic
        // ignition on
        answerAll(port); port.replies["0100"] = "41 00 BE 3F A8 13"
        var ok = false; guard = 0
        while (!ok && guard++ < 50) { val ev = p.step(now); now += 1000; ok = ev is PollEvent.Recovery && ev.recovered }
        assertTrue(ok)
        assertEquals(LinkState.COMMUNICATING, p.monitor.state)
        for (pid in p.scheduler.pids()) assertEquals(p.scheduler.intervalOf(pid), p.scheduler.effectiveIntervalOf(pid))
        repeat(20) { p.step(now); now += 100 }
        assertTrue(p.reading(0x0C)!!.atMs >= now - 2_000)
    }

    @Test fun pollingPausedByGateSendsNothing() {
        val gate = PollGate(); val port = PerfPort().also { answerAll(it) }
        val p = fullPoller(port)
        assertTrue(gate.pause(500))
        var sent = 0
        repeat(10) { if (gate.tryEnterSlice()) { try { p.step(it * 100L); sent++ } finally { gate.exitSlice() } } }
        assertEquals(0, sent); assertTrue(port.sent.isEmpty())
        gate.resume()
        assertTrue(gate.tryEnterSlice()); gate.exitSlice()
    }
}

class PollMetricsTest {
    @Test fun averageMedianAndP95() {
        val m = PollMetrics()
        listOf(10L, 20L, 30L, 40L, 100L).forEachIndexed { i, d -> m.record(0x0C, PollOutcome.OK, d, i * 100L) }
        val f = m.forPid(0x0C)!!
        assertEquals(40.0, f.avgMs, 0.001); assertEquals(30.0, f.medianMs, 0.001); assertEquals(100.0, f.p95Ms, 0.001)
        assertEquals(5, f.requests); assertEquals(100.0, f.avgUpdateMs, 0.001)
    }

    @Test fun evenCountMedianAveragesMiddlePair() {
        val m = PollMetrics()
        listOf(10L, 20L, 30L, 40L).forEachIndexed { i, d -> m.record(1, PollOutcome.OK, d, i * 10L) }
        assertEquals(25.0, m.forPid(1)!!.medianMs, 0.001)
    }

    @Test fun p95OfOneHundredSamplesIsTheNinetyFifth() {
        val m = PollMetrics(window = 100)
        (1..100).forEach { m.record(5, PollOutcome.OK, it.toLong(), it * 10L) }
        assertEquals(95.0, m.forPid(5)!!.p95Ms, 0.001)
    }

    @Test fun countersAndConsecutiveFailures() {
        val m = PollMetrics()
        m.record(1, PollOutcome.TIMEOUT, 2500, 0); m.record(1, PollOutcome.NO_DATA, 30, 1); m.record(1, PollOutcome.MALFORMED, 30, 2)
        var f = m.forPid(1)!!
        assertEquals(1, f.timeouts); assertEquals(1, f.noData); assertEquals(1, f.malformed); assertEquals(3, f.consecutiveFailures)
        m.record(1, PollOutcome.OK, 30, 3)
        assertEquals(0, m.consecutiveFailures(1))
        assertEquals(1, m.forPid(1)!!.timeouts)
    }

    @Test fun windowIsBounded() {
        val m = PollMetrics(window = 8)
        (1..1000).forEach { m.record(1, PollOutcome.OK, 100000L - it, it * 5L) }
        // only the last 8 durations are kept: 99001..99008 reversed order values around 99000
        assertTrue(m.forPid(1)!!.medianMs < 99_010 && m.forPid(1)!!.medianMs > 98_990)
        assertEquals(1000, m.forPid(1)!!.requests)
    }

    @Test fun updateIntervalsAndCycleDuration() {
        val m = PollMetrics()
        val active = setOf(1, 2)
        listOf(0L, 100L, 200L, 300L, 400L).forEachIndexed { i, t -> m.cycleTick(if (i % 2 == 0) 1 else 2, active, t) }
        assertTrue(m.cycleCount() >= 1)
        assertEquals(100.0, m.cycleAvgMs(), 0.001)
        val q = PollMetrics()
        listOf(0L, 500L, 1000L).forEach { q.record(0x0D, PollOutcome.OK, 20, it) }
        assertEquals(500.0, q.forPid(0x0D)!!.avgUpdateMs, 0.001)
    }

    @Test fun emptyMetricsAreZeroNotInvented() {
        val m = PollMetrics()
        assertNull(m.forPid(1)); assertEquals(0.0, m.cycleAvgMs(), 0.0); assertTrue(m.summary().size == 1)
    }

    @Test fun pollerFeedsMetricsFromRealDurations() {
        val port = PerfPort().also { answerAll(it) }
        port.rtt["010C"] = 120L
        val p = fullPoller(port)
        repeat(50) { p.step(it * 100L) }
        val f = p.metrics.forPid(0x0C)!!
        assertEquals(120.0, f.medianMs, 0.001); assertTrue(f.requests > 3)
        assertTrue(p.metrics.summary().any { it.startsWith("perf PID 0C") })
    }
}
