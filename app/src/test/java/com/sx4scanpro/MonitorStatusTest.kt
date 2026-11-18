package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class MonitorStatusTest {
    private fun item(m: MonitorStatus, name: String) = m.monitors.first { it.name == name }

    @Test fun dieselReplyDecodesLampCountAndMonitors() {
        val m = MonitorStatus.parse("41 01 00 0F A9 08")
        assertEquals(MonitorReadStatus.READ, m.status); assertEquals(false, m.milOn); assertEquals(0, m.dtcCount); assertEquals(true, m.compressionIgnition)
        listOf("Misfire", "Fuel system", "Components", "NMHC catalyst", "Exhaust gas sensor", "EGR / VVT").forEach { assertEquals(it, true, item(m, it).complete) }
        assertEquals(false, item(m, "Boost pressure").complete)
        listOf("NOx / SCR aftertreatment", "PM filter").forEach { assertFalse(it, item(m, it).supported); assertNull(item(m, it).complete) }
    }

    @Test fun continuousMonitorNotCompleteIsReadFromBitsFourToSix() {
        val m = MonitorStatus.parse("41 01 83 1F 00 00")
        assertEquals(true, m.milOn); assertEquals(3, m.dtcCount)
        assertEquals(false, item(m, "Misfire").complete); assertEquals(true, item(m, "Fuel system").complete)
    }

    @Test fun sparkLayoutIsNotDecodedAndKeepsRawBytes() {
        val m = MonitorStatus.parse("41 01 82 07 FF FF")
        assertEquals(MonitorReadStatus.READ, m.status); assertEquals(false, m.compressionIgnition); assertEquals("8207FFFF", m.dataHex)
        assertEquals(3, m.monitors.size)
        val text = MonitorStatusText.lines(m).joinToString("\n")
        assertTrue(text.contains("not decode") && text.contains("8207FFFF"))
    }

    @Test fun failuresNeverProduceLampOrMonitorValues() {
        listOf("7F 01 12" to MonitorReadStatus.UNSUPPORTED, "NO DATA" to MonitorReadStatus.NO_DATA, "garbage" to MonitorReadStatus.MALFORMED,
            "" to MonitorReadStatus.MALFORMED, "41 01 00" to MonitorReadStatus.MALFORMED, "41 01 00 0F A9 08 AA" to MonitorReadStatus.MALFORMED,
            "41 01 00 0F A9 08\r41 01 80 0F A9 08" to MonitorReadStatus.MALFORMED).forEach { (raw, st) ->
            val m = MonitorStatus.parse(raw)
            assertEquals(raw, st, m.status); assertNull(m.milOn); assertNull(m.dtcCount); assertTrue(m.monitors.isEmpty())
            val text = MonitorStatusText.lines(m).joinToString("\n")
            assertFalse(text.contains("OFF") || text.contains("complete")); assertTrue(text.contains("Nothing is assumed"))
        }
        assertEquals(MonitorReadStatus.TRANSPORT_FAILURE, MonitorStatus.failure("x").status)
    }

    @Test fun identicalRepliesFromTwoLinesCountOnce() {
        assertEquals(MonitorReadStatus.READ, MonitorStatus.parse("41 01 00 0F A9 08\r41 01 00 0F A9 08").status)
    }

    @Test fun notAttemptedSaysNotRead() {
        assertTrue(MonitorStatusText.lines(MonitorStatus.notAttempted("PID 01 is not advertised by the ECU")).single().contains("not read"))
        assertTrue(MonitorStatusText.lines(null).single().contains("not read"))
    }

    @Test fun countDifferenceFromServiceThreeIsShownNotHidden() {
        val m = MonitorStatus.parse("41 01 82 0F 00 00")
        assertTrue(MonitorStatusText.lines(m, 0).any { it.contains("differs") })
        assertFalse(MonitorStatusText.lines(m, 2).any { it.contains("differs") })
    }

    @Test fun theRequestIsAllowedByTheReadOnlyGuard() { assertEquals(GuardVerdict.Allowed, ReadOnlyGuard.check("0101")) }

    @Test fun fullReportShowsTheSectionAndStaysOptional() {
        val noMonitor = DtcScanReport(0L, "ISO 14230-4", emptyList())
        assertNull(noMonitor.monitor)
        val base = HealthReport.Input("t", true, "a", emptyList(), null, null, 0.0, emptyList(), dtcScan = noMonitor)
        assertTrue(HealthReport.lines(base).joinToString("\n").contains("Lamp and readiness (Mode 01 PID 01): not read"))
        val withMonitor = base.copy(dtcScan = noMonitor.copy(monitor = MonitorStatus.parse("41 01 00 0F A9 08")))
        assertTrue(HealthReport.lines(withMonitor).joinToString("\n").contains("Lamp (MIL) as reported by the ECU: OFF"))
    }
}
