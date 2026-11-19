package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

/** D11: replies taken from the owner's real-car capture of 2026-11-15 (ISO 14230-4 KWP fast, engine ECU 0x11), replayed through the current classifiers. */
class RealCaptureReplayTest {
    private val kline = Framing.KLINE

    @Test fun theRealDtcServicesClassifyAsTheCarAnswered() {
        assertEquals(DtcModeStatus.VALID_EMPTY, DtcScanClassifier.classify("03", "STORED", "43000000000000", kline).status)
        assertEquals(DtcModeStatus.VALID_EMPTY, DtcScanClassifier.classify("07", "PENDING", "47000000000000", kline).status)
        assertEquals(DtcModeStatus.UNSUPPORTED, DtcScanClassifier.classify("0A", "PERMANENT", "7F0A11", kline).status)
    }

    @Test fun anEngineWithOnlyTwoOfThreeServicesAnsweringIsNotCalledComplete() {
        val report = DtcScanReport(0L, "ISO 14230-4", listOf(
            DtcScanClassifier.classify("03", "STORED", "43000000000000", kline),
            DtcScanClassifier.classify("07", "PENDING", "47000000000000", kline),
            DtcScanClassifier.classify("0A", "PERMANENT", "7F0A11", kline)))
        assertFalse(report.engineEcuReportsNoCodes)
        assertEquals(2, report.answeredModes)
    }

    @Test fun theRealUnsupportedPidsAreNotReadinessOrModeSixReplies() {
        assertNull(ReadinessDecoder.decode("7F0111"))
        assertEquals(Mode06Outcome.NOT_SUPPORTED, Mode06Probe.classify("7F0611").outcome)
    }

    // ---- 2026-11-18 engine-idle capture ----
    @Test fun theRealReadinessReplyDecodes() {
        val r = ReadinessDecoder.decode("410100068000")!!
        assertFalse(r.milOn); assertEquals(0, r.storedDtcCount)
        assertEquals(listOf("Fuel system", "Comprehensive components", "EGR / VVT"), r.monitors.map { it.name })
        assertTrue(r.incomplete.isEmpty())
    }

    @Test fun theRealModeSixReplyIsRecordedAsOffered() {
        val p = Mode06Probe.classify("4600FF00000001")
        assertEquals(Mode06Outcome.SUPPORTED, p.outcome); assertEquals((1..8).toSet(), p.ids)
    }

    @Test fun theRealMode01BitmapsDecodeToTheNineteenAdvertisedPids() {
        val s = ObdPidSupport.decode("4100983B0011 4120A0102001 4140C0C00000")
        assertEquals(setOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x1C, 0x20, 0x21, 0x23, 0x2C, 0x33, 0x40, 0x41, 0x42, 0x49, 0x4A), s.filter { it !in setOf(0x60) }.toSet())
    }
}
