package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class OwnerEcuLabelTest {
    private fun profile() = EcuProfile(0L, "t", "Vgate", "ELM327", "12.4V", "ISO 14230-4 (KWP fast)", Framing.KLINE, listOf(0x0C),
        ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED)

    @Test fun labelIsRecordedExactlyAsPrintedAndSaysItIsNotFromTheEcu() {
        val text = OwnerEcuLabel.lines().joinToString("\n")
        assertTrue(text.contains("MJD 6S3.S1"))
        assertTrue(text.contains("33920M55KD0"))
        assertTrue(text.contains("33920-55KD"))
        assertTrue(text.contains("Z13DT MT P74 BSIV"))
        assertTrue(text.contains("not reported by the ECU"))
        assertTrue(text.contains("Hardware code (unconfirmed)"))
    }

    @Test fun pdfLinesShowTheLabelSeparatelyFromScannedValues() {
        val text = EcuReport.lines(profile(), null).joinToString("\n")
        assertTrue(text.indexOf("VIN:") < text.indexOf(OwnerEcuLabel.HEADING))
        assertTrue(text.contains("33920M55KD0"))
    }

    @Test fun labelIsNotPartOfTheSharedIdentityLines() {
        assertFalse(EcuReport.identityLines(profile()).joinToString("\n").contains("33920M55KD0"))
    }
}
