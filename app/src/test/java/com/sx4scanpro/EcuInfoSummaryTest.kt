package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuInfoSummaryTest {
    private fun profile(
        vin: ProfileField = ProfileField.NOT_ATTEMPTED, cal: ProfileField = ProfileField.NOT_ATTEMPTED,
        cvn: ProfileField = ProfileField.NOT_ATTEMPTED, name: ProfileField = ProfileField.NOT_ATTEMPTED, at: Long = 1_000L
    ) = EcuProfile(at, "t", "Vgate", "ELM327", "12.4V", "ISO 15765-4 (CAN 11/500)", Framing.CAN, listOf(0x0C), ProfileField.NOT_ATTEMPTED, vin, cal, cvn, name)

    private fun read(v: String) = ProfileField(ScanStatus.READ, listOf(v), "49 02 01")

    @Test fun allFourReadSaysSo() {
        val s = EcuReport.scanSummary(profile(read("VIN1"), read("CAL1"), read("CVN1"), read("ECU1")))
        assertEquals("Scan finished: 4 of 4 identity items read from the ECU.", s)
    }

    @Test fun nothingReadIsNotPresentedAsASuccessfulScan() {
        val s = EcuReport.scanSummary(profile(ProfileField(ScanStatus.NO_DATA), ProfileField(ScanStatus.NO_DATA)))
        assertTrue(s.contains("0 of 4 identity items read"))
        assertTrue(s.contains("VIN: ECU answered NO DATA"))
        assertTrue(s.contains("calibration IDs: ECU answered NO DATA"))
    }

    @Test fun everyFailureStatusGetsItsOwnReason() {
        val s = EcuReport.scanSummary(profile(
            ProfileField(ScanStatus.NO_DATA), ProfileField(ScanStatus.MALFORMED), ProfileField(ScanStatus.NOT_ADVERTISED),
            ProfileField(ScanStatus.ADAPTER_ERROR, note = "timed out")
        ))
        assertTrue(s.contains("0 of 4"))
        assertTrue(s.contains("calibration IDs: Reply could not be read"))
        assertTrue(s.contains("CVNs: Not offered by this ECU"))
        assertTrue(s.contains("ECU name: Adapter or bus error (timed out)"))
    }

    @Test fun partialSuccessListsOnlyWhatIsMissing() {
        val s = EcuReport.scanSummary(profile(read("VIN1"), ProfileField(ScanStatus.NO_DATA), read("CVN1"), ProfileField(ScanStatus.NOT_ADVERTISED)))
        assertTrue(s.contains("2 of 4"))
        assertFalse(s.contains("VIN:"))
        assertTrue(s.contains("calibration IDs"))
        assertTrue(s.contains("ECU name"))
    }

    @Test fun readStatusWithNoItemsCountsAsMissing() {
        val s = EcuReport.scanSummary(profile(ProfileField(ScanStatus.READ, emptyList())))
        assertTrue(s.contains("0 of 4"))
    }

    @Test fun profileFromBeforeThisRunIsFlaggedAsEarlier() {
        assertTrue(EcuReport.isFromEarlierSession(profile(at = 1_000L), 5_000L))
        assertFalse(EcuReport.isFromEarlierSession(profile(at = 6_000L), 5_000L))
    }

    @Test fun identityLinesExposeEveryReadValueWithItsRaw() {
        val l = EcuReport.identityLines(profile(read("VIN1"), read("CAL1"), read("CVN1"), read("ECU1")))
        val text = l.joinToString("\n")
        listOf("VIN: VIN1", "Calibration IDs: CAL1", "CVNs: CVN1", "ECU name: ECU1", "raw: 49 02 01", "Mode 01 PIDs advertised (1): 0C").forEach { assertTrue(it, text.contains(it)) }
    }
}
