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
}
