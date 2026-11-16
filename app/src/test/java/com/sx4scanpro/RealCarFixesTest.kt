package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

/** Regression tests for the findings of the 2026-11-15 real-car capture (4.7 h, SX4 ZDi, ISO 14230-4 KWP fast, engine ECU 0x11). */
class RealCarFixesTest {

    // ---- recovery order (F2) ----
    @Test fun firstRecoveryStepIsLightReinitBecauseProbesNeverWorkedAfterAnEngineStop() {
        val m = LinkMonitor()
        assertEquals(RecoveryStep.LIGHT_REINIT, m.nextRecovery().step)
        assertEquals(RecoveryStep.PROBE, m.nextRecovery().step)
        assertEquals(RecoveryStep.LIGHT_REINIT, m.nextRecovery().step)
    }

    @Test fun recoveryStillEscalatesToFullReinit() {
        val m = LinkMonitor()
        val steps = (1..8).map { m.nextRecovery().step }
        assertTrue(RecoveryStep.FULL_REINIT in steps)
    }

    @Test fun silenceWordingDoesNotClaimTheIgnitionIsOff() {
        val t = ConnectionPresenter.failureText(FailureKind.REPEATED_NO_DATA)
        assertTrue(t.contains("engine stopped"))
        assertTrue(t.contains("ignition off"))
    }

    // ---- Mode 0A (F5) and empty DTC replies (F8) ----
    @Test fun serviceNotSupportedIsRecognisedFromTheRealReply() {
        assertTrue(DtcModePolicy.isNotSupported("7F0A11", "0A"))
        assertTrue(DtcModePolicy.isNotSupported("7F0A12", "0A"))
        assertTrue(DtcModePolicy.isNotSupported("7F 0A 11\r", "0A"))
    }

    @Test fun responsePendingIsNotARefusal() {
        assertFalse(DtcModePolicy.isNotSupported("7F0A78", "0A"))
        assertFalse(DtcModePolicy.isNotSupported("4A000000000000", "0A"))
        assertFalse(DtcModePolicy.isNotSupported("NODATA", "0A"))
        assertFalse(DtcModePolicy.isNotSupported("7F0311", "0A"))      // another mode's refusal says nothing about 0A
    }

    @Test fun theRealEmptyDtcRepliesDecodeToNoCodesNotP0000() {
        for ((mode, raw) in listOf("03" to "43000000000000", "07" to "47000000000000")) {
            val r = ObdTroubleCodeDecoder.decodeResponse(raw, mode, Framing.KLINE)
            assertEquals(1, r.validFrames); assertTrue(r.codes.isEmpty()); assertTrue(r.unreadable.isEmpty())
        }
    }

    // ---- registry feed (F1) ----
    private fun reg() = ParameterRegistry.withBuiltInDefinitions()
    private fun id(pid: Int) = ParameterRegistry.idOf("01", "%02X".format(pid))

    private fun decoded(pid: Int, raw: String, at: Long): Reading {
        val d = DecoderRegistry.decode(raw, pid) as Decoding.Decoded
        return Reading(d.value, at, raw)
    }

    @Test fun theRealRpmReplyDecodesAndPromotesTheRegistry() {
        val r = reg(); var now = 1_000L
        r.onAdvertised(id(0x0C), Evidence(0, EvidenceKind.BITMAP_ADVERTISED))
        val feed = LiveRegistryFeed({ r }, { now })
        feed.onPolled(0x0C, decoded(0x0C, "410C0CE0", now), ElmKind.DATA)
        assertEquals(ParamState.DECODED, r.state(id(0x0C)))
        assertEquals(824.0, decoded(0x0C, "410C0CE0", 0).value, 0.0)         // 0x0CE0 / 4, from the capture at 13:37:21
        assertTrue(feed.takeDirty()); assertFalse(feed.takeDirty())
    }

    @Test fun noDataNeverMakesAParameterUnsupportedAndALaterSuccessRestoresIt() {
        val r = reg(); var now = 1_000L
        r.onAdvertised(id(0x0C), Evidence(0, EvidenceKind.BITMAP_ADVERTISED))
        val feed = LiveRegistryFeed({ r }, { now })
        feed.onPolled(0x0C, decoded(0x0C, "410C0CE0", now), ElmKind.DATA)
        now += 5_000; feed.onPolled(0x0C, null, ElmKind.NO_DATA)
        assertEquals(ParamState.DECODED, r.state(id(0x0C)))
        assertEquals(EvidenceKind.NO_DATA, r.record(id(0x0C))!!.evidence.last().kind)
        now += 1_000; feed.onPolled(0x0C, decoded(0x0C, "410C0C80", now), ElmKind.DATA)      // first success after the dropout is recorded at once
        assertEquals(EvidenceKind.DECODED_OK, r.record(id(0x0C))!!.evidence.last().kind)
    }

    @Test fun evidenceIsThrottledSoAFastLoopCannotFloodTheHistory() {
        val r = reg(); var now = 1_000L
        val feed = LiveRegistryFeed({ r }, { now })
        feed.onPolled(0x0D, decoded(0x0D, "410D00", now), ElmKind.DATA)
        val n0 = r.record(id(0x0D))!!.evidence.size
        repeat(500) { now += 280; feed.onPolled(0x0D, decoded(0x0D, "410D00", now), ElmKind.DATA) }   // about 140 s of polling
        val grew = r.record(id(0x0D))!!.evidence.size - n0
        assertTrue("evidence grew by $grew", grew <= 4)
        repeat(500) { now += 280; feed.onPolled(0x0D, null, ElmKind.NO_DATA) }
        assertTrue(r.record(id(0x0D))!!.evidence.size <= ParameterRegistry.MAX_EVIDENCE)
    }

    @Test fun adapterLevelFailuresAndUnknownPidsAreIgnored() {
        val r = reg(); val feed = LiveRegistryFeed({ r }, { 5L })
        feed.onPolled(0x0C, null, ElmKind.BUS_BUSY)
        feed.onPolled(0x0C, null, ElmKind.UNKNOWN_COMMAND)
        assertEquals(ParamState.UNTESTED, r.state(id(0x0C))); assertTrue(r.record(id(0x0C))!!.evidence.isEmpty())
        feed.onPolled(0x7E, null, ElmKind.NO_DATA)       // not in the registry: no crash, nothing created
        assertNull(r.def(id(0x7E)))
    }

    @Test fun malformedReplyIsDecodeEvidenceOnly() {
        val r = reg(); val feed = LiveRegistryFeed({ r }, { 9L })
        r.onAdvertised(id(0x05), Evidence(0, EvidenceKind.BITMAP_ADVERTISED))
        feed.onPolled(0x05, null, ElmKind.DATA)
        assertEquals(ParamState.ADVERTISED, r.state(id(0x05)))
        assertEquals(EvidenceKind.DECODE_FAILED, r.record(id(0x05))!!.evidence.last().kind)
    }

    @Test fun saveGateWritesSoonAfterAChangeButNotPerPoll() {
        val g = RegistrySaveGate(30_000L)
        assertFalse(g.shouldSave(100_000L))
        g.markDirty(); assertTrue(g.shouldSave(100_000L)); g.saved(100_000L)
        g.markDirty(); assertFalse(g.shouldSave(110_000L)); assertTrue(g.hasPending()); assertTrue(g.shouldSave(130_000L))
    }

    // ---- poll plan (F6) ----
    @Test fun everyPlannedPidCanStayFreshAtItsRealisticUpdateRate() {
        // Real update intervals from the capture's PERF lines: coolant about 4-5 s, slow group about 17 s. A gauge must not flash "--" between reads.
        val plan = PollPlan.core.associate { it.pid to it.intervalMs } + (PollPlan.BARO_PID to PollPlan.BARO_INTERVAL_MS) +
            (0x23 to PollPlan.extraIntervalMs(0x23)) + (0x42 to PollPlan.extraIntervalMs(0x42)) + (0x49 to PollPlan.extraIntervalMs(0x49))
        for ((pid, iv) in plan) {
            val realistic = if (pid == 0x0C || pid == 0x0D) 1_300L else maxOf(iv, 5_000L).coerceAtMost(20_000L)
            assertTrue("PID %02X blank window ${PollPlan.blankMs(pid)} ms vs update $realistic ms".format(pid), PollPlan.blankMs(pid) >= realistic)
        }
        assertTrue(PollPlan.BARO_MAX_AGE_MS > 2 * PollPlan.BARO_INTERVAL_MS)
    }

    @Test fun totalRequestedRateIsCloseToWhatTheKLineCanCarry() {
        // About 270-290 ms per request measured on the car, i.e. 3.5 requests/s at most.
        val core = PollPlan.core.filter { it.pid != 0x11 }.sumOf { 1000.0 / it.intervalMs }          // throttle (0x11) is not advertised by this ECU
        val rest = 1000.0 / PollPlan.BARO_INTERVAL_MS + 1000.0 / PollPlan.RAIL_INTERVAL_MS + 1000.0 / PollPlan.extraIntervalMs(0x42)
        val total = core + rest
        assertTrue("requested $total req/s", total in 2.5..4.2)
    }

    @Test fun railPressureHasItsOwnFastPaceAndPriority() {
        val s = PollScheduler(); PollPlan.configure(s, setOf(0x23, 0x42))
        assertEquals(3_000L, s.intervalOf(0x23)); assertEquals(2, s.priorityOf(0x23))
        assertEquals(15_000L, s.intervalOf(0x42))
    }

    @Test fun alertRulesAcceptTheSlowCoolantRead() {
        val rules = AlertLimits(coolantMaxC = 100, rpmMax = 4000, boostMaxKpa = 150).rules().associateBy { it.id }
        assertTrue(rules.getValue("coolant-limit").maxAgeMs >= 15_000L)
        assertTrue(rules.getValue("rpm-limit").maxAgeMs <= 6_000L)
        // a coolant read 12 s old (one slow cycle) must still be able to confirm a warning
        val e = WarningEngine(listOf(rules.getValue("coolant-limit")))
        var t = 100_000L; var fired = false
        repeat(4) { e.evaluate(mapOf("coolant" to SensorReading(110.0, t - 12_000, true, true)), t).also { if (it.isNotEmpty()) fired = true }; t += 13_000 }
        assertTrue(fired)
    }
}

class EcuScanPolicyTest {
    private fun profile(at: Long) = EcuProfile(
        capturedAtMs = at, appVersion = "t", adapterName = "", adapterId = "", adapterVoltage = "", protocol = "", framing = Framing.KLINE,
        mode01Supported = emptyList(), mode09Supported = ProfileField(ScanStatus.NOT_ATTEMPTED), vin = ProfileField(ScanStatus.NOT_ATTEMPTED),
        calIds = ProfileField(ScanStatus.NOT_ATTEMPTED), cvns = ProfileField(ScanStatus.NOT_ATTEMPTED), ecuName = ProfileField(ScanStatus.NOT_ATTEMPTED), notes = emptyList()
    )

    @Test fun scansWhenNothingIsSavedOrTheProfileIsOld() {
        val now = 10L * 24 * 3_600_000
        assertTrue(EcuScanPolicy.dueAtConnect(null, now))
        assertTrue(EcuScanPolicy.dueAtConnect(profile(now - 25 * 3_600_000), now))
        assertFalse(EcuScanPolicy.dueAtConnect(profile(now - 3_600_000), now))
    }

    @Test fun aProfileFromTheFutureMeansTheClockMovedSoItIsRescanned() {
        assertTrue(EcuScanPolicy.dueAtConnect(profile(2_000_000L), 1_000_000L))
    }
}
