package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExtrasTest {
    @Test fun dtcStructure() {
        val x = DtcExplainer.explain("P0301")!!
        assertTrue(x.system.startsWith("Powertrain"))
        assertTrue(x.scope.startsWith("Standardised"))
        assertEquals("Subsystem: Ignition system or misfire", x.subsystem)
        assertTrue(DtcExplainer.explain("P1234")!!.scope.startsWith("Manufacturer"))
        assertNull(DtcExplainer.explain("C0035")!!.subsystem)
        assertTrue(DtcExplainer.explain("u0100")!!.system.startsWith("Network"))
    }

    @Test fun dtcRejectsGarbage() {
        assertNull(DtcExplainer.explain("X0301"))
        assertNull(DtcExplainer.explain("P03"))
        assertNull(DtcExplainer.explain("P03G1"))
    }

    @Test fun freezeCommandAndDecode() {
        assertEquals("020C00", FreezeFrame.command(0x0C))
        val rpm = PidCatalog.forPid(0x0C)!!
        val e = FreezeFrame.decode(rpm, "420C001F40")!!
        assertEquals("2000", e.value.substringBefore('.'))
        assertNull(FreezeFrame.decode(rpm, "NO DATA"))
        assertNull(FreezeFrame.decode(rpm, "410C1F40"))
        assertNull(FreezeFrame.decode(rpm, "420C001F"))
    }

    @Test fun maintenanceStatus() {
        assertEquals(Maintenance.State.NOT_SET, Maintenance.status(null, 500.0, 0.0).state)
        assertEquals(Maintenance.State.OK, Maintenance.status(10000, 1000.0, 0.0).state)
        assertEquals(Maintenance.State.DUE_SOON, Maintenance.status(10000, 9100.0, 0.0).state)
        assertEquals(Maintenance.State.OVERDUE, Maintenance.status(10000, 10500.0, 0.0).state)
        assertEquals(Maintenance.State.OK, Maintenance.status(10000, 10500.0, 10400.0).state)
        assertEquals("Overdue by 500 km", Maintenance.describe(Maintenance.status(10000, 10500.0, 0.0)))
    }

    @Test fun reportSaysWhatIsMissing() {
        val l = HealthReport.lines(HealthReport.Input("now", false, "", emptyList(), null, null, 12.5, emptyList()))
        assertTrue(l.contains("  not scanned in this session"))
        assertTrue(l.contains("  none available"))
        assertTrue(l.any { it.contains("12.5 km") })
        assertNotNull(l.firstOrNull { it.startsWith("OBD link: not connected") })
    }
}

class DriveModeTest {
    @Test fun shiftLightThresholds() {
        assertEquals(0, ShiftLights.lit(2100.0))
        assertEquals(3, ShiftLights.lit(2900.0))
        assertEquals(8, ShiftLights.lit(4100.0))
        assertEquals(0, ShiftLights.lit(null))
        assertEquals(0, ShiftLights.lit(Double.NaN))
        assertTrue(ShiftLights.flashing(4300.0))
        assertTrue(!ShiftLights.flashing(4099.0))
    }

    @Test fun economy() {
        assertEquals(20.0, Economy.kmPerLitre(60.0, 3.0)!!, 1e-9)
        assertNull(Economy.kmPerLitre(null, 3.0))
        assertNull(Economy.kmPerLitre(60.0, null))
        assertNull(Economy.kmPerLitre(2.0, 3.0))
        assertNull(Economy.kmPerLitre(60.0, 0.0))
    }

    @Test fun modeParse() {
        assertEquals(DriveMode.ECO, DriveMode.parse("ECO"))
        assertEquals(DriveMode.SPORT, DriveMode.parse("nonsense"))
        assertEquals(DriveMode.SPORT, DriveMode.parse(null))
    }
}
