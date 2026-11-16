package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDiagnosisReportTest {
    private fun scan(vararg m: DtcModeScan) = DtcScanReport(1_700_000_000_000L, "ISO 15765-4 (CAN 11/500)", m.toList())
    private fun mode(mode: String, cat: String, st: DtcModeStatus, codes: List<String> = emptyList(), raw: String = "") =
        DtcModeScan(mode, cat, st, codes, emptyList(), raw)

    private val clean = scan(
        mode("03", "STORED", DtcModeStatus.VALID_EMPTY, raw = "43 00"),
        mode("07", "PENDING", DtcModeStatus.VALID_EMPTY, raw = "47 00"),
        mode("0A", "PERMANENT", DtcModeStatus.VALID_EMPTY, raw = "4A 00")
    )
    private val faulty = scan(
        mode("03", "STORED", DtcModeStatus.CODES_REPORTED, listOf("P0118", "P1234"), "43 02 01 18 12 34"),
        mode("07", "PENDING", DtcModeStatus.CODES_REPORTED, listOf("P0234"), "47 01 02 34"),
        mode("0A", "PERMANENT", DtcModeStatus.NO_DATA, raw = "NO DATA")
    )
    private val ff = FreezeFrameResult(
        1_700_000_000_000L, 0, FreezeStatus.VALUE, setOf(0x02, 0x05, 0x0C), FreezeStatus.VALUE, "P0118", "42 02 00 01 18",
        listOf(
            FreezeItem(0x0C, "Engine RPM", FreezeStatus.VALUE, "2000", "rpm", "42 0C 00 1F 40"),
            FreezeItem(0x05, "Coolant temperature", FreezeStatus.TIMEOUT, detail = "no reply before the timeout")
        ),
        emptyList()
    )

    private fun input(
        dtc: DtcScanReport? = null, freeze: FreezeFrameResult? = null, ecu: EcuProfile? = null,
        readings: List<Triple<String, String, String>> = emptyList(), unavailable: List<String> = emptyList()
    ) = HealthReport.Input("now", true, "Vgate", readings, null, 3.0, 100.0, emptyList(), dtc, freeze, ecu, unavailable)

    private fun text(i: HealthReport.Input) = HealthReport.lines(i).joinToString("\n")

    private fun profile(vinStatus: ScanStatus = ScanStatus.READ) = EcuProfile(
        1_700_000_000_000L, "test", "Vgate", "ELM327 v2.1", "12.4V", "ISO 15765-4 (CAN 11/500)", Framing.CAN, listOf(0x04, 0x05),
        ProfileField(ScanStatus.READ, listOf("02", "04")),
        ProfileField(vinStatus, if (vinStatus == ScanStatus.READ) listOf("TESTVIN0000000001") else emptyList(), "49 02 01"),
        ProfileField(ScanStatus.NO_DATA), ProfileField(ScanStatus.NOT_ADVERTISED), ProfileField(ScanStatus.NOT_ADVERTISED)
    )

    // ---- stored / pending / permanent ----

    @Test fun everyReportedCodeAppearsWithItsCategory() {
        val t = text(input(dtc = faulty))
        assertTrue(t.contains("P0118  [STORED"))
        assertTrue(t.contains("P1234  [STORED"))
        assertTrue(t.contains("P0234  [PENDING"))
    }

    @Test fun catalogueDescriptionAndPossibilitiesAreIncluded() {
        val t = text(input(dtc = faulty))
        assertTrue(t.contains("coolant temperature sensor circuit, high input"))
        assertTrue(t.contains("Possible causes (possibilities, not confirmed findings)"))
        assertTrue(t.contains("Sensible next checks"))
    }

    @Test fun unknownCodeIsNeverHidden() {
        val t = text(input(dtc = faulty))
        assertTrue(t.contains("P1234"))
        assertTrue(t.contains("not available in this app's catalogue"))
    }

    @Test fun sameCodeInTwoServicesIsListedForBoth() {
        val s = scan(
            mode("03", "STORED", DtcModeStatus.CODES_REPORTED, listOf("P0118")),
            mode("07", "PENDING", DtcModeStatus.CODES_REPORTED, listOf("P0118")),
            mode("0A", "PERMANENT", DtcModeStatus.VALID_EMPTY)
        )
        val t = text(input(dtc = s))
        assertTrue(t.contains("P0118  [STORED"))
        assertTrue(t.contains("P0118  [PENDING"))
    }

    @Test fun rawRepliesAreInTheReport() {
        val t = text(input(dtc = faulty))
        assertTrue(t.contains("03: 43 02 01 18 12 34"))
        assertTrue(t.contains("0A: NO DATA"))
    }

    // ---- no false all-clear ----

    @Test fun incompleteScanIsNotReportedAsClean() {
        val t = text(input(dtc = scan(
            mode("03", "STORED", DtcModeStatus.VALID_EMPTY), mode("07", "PENDING", DtcModeStatus.NO_DATA), mode("0A", "PERMANENT", DtcModeStatus.TRANSPORT_FAILURE, raw = "")
        )))
        assertFalse(t.contains("reported no stored, pending or permanent codes"))
        assertTrue(t.contains("NOT confirmed"))
        assertTrue(t.contains("scan was incomplete"))
        assertTrue(t.contains("(none received)"))
    }

    @Test fun completeEmptyScanSaysEngineEcuOnlyNeverWholeVehicle() {
        val t = text(input(dtc = clean))
        assertTrue(t.contains("reported no stored, pending or permanent codes on this scan"))
        assertTrue(t.contains("cannot show that the whole vehicle is free of faults"))
        assertFalse(t.lowercase().contains("vehicle is fault-free"))
    }

    @Test fun codesAreNeverMarkedActive() {
        val t = text(input(dtc = faulty, freeze = ff)).lowercase()
        assertTrue(t.contains("not proof that a fault is present at this instant"))
        assertFalse(t.contains("currently active"))
        assertFalse(t.contains("severity:"))
    }

    @Test fun scopeIsStated() {
        assertTrue(text(input(dtc = clean)).contains("BCM, ABS, airbag, immobilizer and other modules were not queried"))
    }

    // ---- freeze frame ----

    @Test fun freezeFrameValuesAndFailuresAreBothReported() {
        val t = text(input(dtc = faulty, freeze = ff))
        assertTrue(t.contains("Engine RPM: 2000 rpm"))
        assertTrue(t.contains("raw: 42 0C 00 1F 40"))
        assertTrue(t.contains("Coolant temperature: Timed out"))
        assertTrue(t.contains("Fault that set this frame: P0118"))
        assertTrue(t.contains("Freeze-frame evidence for P0118"))
        assertTrue(t.contains("A freeze frame names this code"))
        assertTrue(t.contains("Freeze frame Coolant temperature: Timed out"))
    }

    @Test fun freezeFrameNotReadIsStated() {
        val t = text(input(dtc = clean))
        assertTrue(t.contains("not read in this session"))
        assertTrue(t.contains("Freeze frame was not read"))
    }

    @Test fun freezeFrameWithNoDataExplainsWhy() {
        val none = FreezeFrameResult(0L, 0, FreezeStatus.NO_DATA, null, FreezeStatus.NO_DATA, null, "", emptyList(), emptyList())
        val t = text(input(dtc = clean, freeze = none))
        assertTrue(t.contains("answered NO DATA"))
        assertTrue(t.contains("no values were obtained"))
    }

    // ---- ECU info ----

    @Test fun ecuIdentityIsIncludedAndFailuresListed() {
        val t = text(input(dtc = clean, ecu = profile()))
        assertTrue(t.contains("TESTVIN0000000001"))
        assertTrue(t.contains("ELM327 v2.1"))
        assertTrue(t.contains("ECU information Calibration IDs: ECU answered NO DATA"))
        assertTrue(t.contains("ECU information CVNs: Not offered by this ECU"))
    }

    @Test fun ecuInfoNotReadIsStated() {
        assertTrue(text(input(dtc = clean)).contains("ECU information was not read"))
    }

    // ---- live data ----

    @Test fun liveReadingsAndMissingOnesAreSeparated() {
        val t = text(input(dtc = clean, readings = listOf(Triple("Engine RPM", "800", "rpm")), unavailable = listOf("Turbo boost")))
        assertTrue(t.contains("Engine RPM: 800 rpm"))
        assertTrue(t.contains("No fresh value at report time: Turbo boost"))
        assertTrue(t.contains("Live readings without a fresh value: Turbo boost"))
    }

    @Test fun relatedLiveReadingsAttachToTheRightCode() {
        val readings = listOf(Triple("Coolant temperature", "97", "°C"), Triple("Engine RPM", "800", "rpm"))
        val t = text(input(dtc = faulty, readings = readings))
        assertTrue(t.contains("Related live readings at report time: Coolant temperature 97 °C"))
        assertEquals(1, HealthReport.relatedReadings("P0118", readings).size)
        assertTrue(HealthReport.relatedReadings("P1234", readings).isEmpty())
    }

    // ---- legacy behaviour ----

    @Test fun legacyInputStillWorksAndNoLongerSaysNoneReturned() {
        val l = HealthReport.lines(HealthReport.Input("now", false, "", emptyList(), emptyList(), null, 1.0, emptyList()))
        assertFalse(l.any { it.contains("none returned") })
        assertTrue(l.any { it.contains("completeness of the scan was not recorded") })
        assertEquals("SX4 SCAN PRO - VEHICLE HEALTH REPORT", l.first())
    }

    @Test fun fullReportHasItsOwnTitle() {
        assertEquals("SX4 SCAN PRO - FULL DIAGNOSIS REPORT", HealthReport.lines(input(dtc = clean)).first())
    }

    @Test fun noInventedValuesWhenNothingWasRead() {
        val t = text(input(freeze = null, dtc = null, ecu = null, readings = emptyList()))
        assertTrue(t.contains("none available"))
        assertFalse(t.contains("FULL DIAGNOSIS"))
    }
}
