package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class EcuReportTest {
    private fun profile(vin: ProfileField) = EcuProfile(0L, "t", "Vgate", "ELM327", "12.4V", "ISO 15765-4 (CAN 11/500)", Framing.CAN, listOf(0x0C),
        ProfileField.NOT_ATTEMPTED, vin, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED)

    @Test fun missingValuesSayWhyAndNothingIsInvented() {
        val text = EcuReport.lines(profile(ProfileField(ScanStatus.NO_DATA)), null).joinToString("\n")
        assertTrue(text.contains("VIN: ECU answered NO DATA"))
        assertTrue(text.contains("Calibration IDs: Not requested"))
    }

    @Test fun readValueIsShown() {
        val text = EcuReport.lines(profile(ProfileField(ScanStatus.READ, listOf("MA3EYB81S00123456"), "49 02 01")), null).joinToString("\n")
        assertTrue(text.contains("VIN: MA3EYB81S00123456"))
        assertTrue(text.contains("raw: 49 02 01"))
    }

    @Test fun linkWording() {
        assertEquals("Not connected", EcuReport.linkText(LinkState.COMMUNICATING, false))
        assertEquals("ECU not responding", EcuReport.linkText(LinkState.ECU_SILENT, true))
    }

    @Test fun diffWording() {
        val a = profile(ProfileField(ScanStatus.READ, listOf("A")))
        val b = profile(ProfileField(ScanStatus.READ, listOf("B")))
        assertTrue(EcuReport.diffText(ProfileDiff.compare(a, b)).single().contains("VIN changed: A -> B"))
        assertEquals(listOf("No difference from the previous scan."), EcuReport.diffText(ProfileDiff.compare(a, a)))
    }
}
