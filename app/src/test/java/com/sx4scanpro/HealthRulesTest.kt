package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class HealthRulesTest {
    private val now = 100_000L
    private fun r(name: String, v: Double, unit: String = "", age: Long = 1_000L) = RuleReading(name, v, unit, now - age)
    private fun find(f: List<HealthFinding>, id: String) = f.firstOrNull { it.ruleId == id }

    @Test fun operatingStateBands() {
        assertEquals(OperatingState.UNKNOWN, HealthRules.stateOf(emptyList(), now))
        assertEquals(OperatingState.ENGINE_OFF, HealthRules.stateOf(listOf(r(HealthRules.RPM, 0.0)), now))
        assertEquals(OperatingState.CRANKING, HealthRules.stateOf(listOf(r(HealthRules.RPM, 220.0)), now))
        assertEquals(OperatingState.RUNNING, HealthRules.stateOf(listOf(r(HealthRules.RPM, 780.0)), now))
    }

    @Test fun staleRpmIsUnknownNotZero() {
        assertEquals(OperatingState.UNKNOWN, HealthRules.stateOf(listOf(r(HealthRules.RPM, 0.0, age = 60_000)), now))
        assertEquals(OperatingState.UNKNOWN, HealthRules.stateOf(listOf(r(HealthRules.RPM, Double.NaN)), now))
    }

    @Test fun engineOffMapAgreesWithBaro() {
        val f = HealthRules.evaluate(listOf(r(HealthRules.RPM, 0.0), r(HealthRules.MAP, 99.0, "kPa"), r(HealthRules.BARO, 101.0, "kPa")), now)
        assertEquals(HealthLevel.NORMAL, find(f, "MAP_BARO")!!.level)
    }

    @Test fun engineOffMapDisagreesWithBaro() {
        val f = HealthRules.evaluate(listOf(r(HealthRules.RPM, 0.0), r(HealthRules.MAP, 70.0, "kPa"), r(HealthRules.BARO, 101.0, "kPa")), now)
        val x = find(f, "MAP_BARO")!!
        assertEquals(HealthLevel.ATTENTION, x.level)
        assertTrue(x.possibilities.isNotEmpty()); assertTrue(x.nextCheck.isNotBlank()); assertTrue(x.provenance.contains("app value"))
    }

    @Test fun runningDoesNotJudgeMapBaro() {
        val f = HealthRules.evaluate(listOf(r(HealthRules.RPM, 800.0), r(HealthRules.MAP, 150.0, "kPa"), r(HealthRules.BARO, 100.0, "kPa")), now)
        val x = find(f, "MAP_BARO")!!
        assertEquals(HealthLevel.OBSERVATION, x.level)
        assertTrue(x.summary.contains("50 kPa"))
    }

    @Test fun missingOrStalePairIsInsufficientNeverZero() {
        val f = HealthRules.evaluate(listOf(r(HealthRules.RPM, 0.0), r(HealthRules.MAP, 99.0, "kPa")), now)
        assertEquals(HealthLevel.INSUFFICIENT, find(f, "MAP_BARO")!!.level)
        val g = HealthRules.evaluate(listOf(r(HealthRules.RPM, 0.0), r(HealthRules.MAP, 99.0, "kPa"), r(HealthRules.BARO, 101.0, "kPa", age = 30_000)), now)
        assertEquals(HealthLevel.INSUFFICIENT, find(g, "MAP_BARO")!!.level)
    }

    @Test fun implausibleBaroIsInvestigate() {
        val f = HealthRules.evaluate(listOf(r(HealthRules.RPM, 0.0), r(HealthRules.MAP, 99.0, "kPa"), r(HealthRules.BARO, 255.0, "kPa")), now)
        assertEquals(HealthLevel.INVESTIGATE, find(f, "MAP_BARO")!!.level)
    }

    @Test fun chargingVoltageByState() {
        fun lvl(rpm: Double, v: Double) = find(HealthRules.evaluate(listOf(r(HealthRules.RPM, rpm), r(HealthRules.VOLTAGE, v, "V")), now), "SUPPLY_VOLTAGE")!!.level
        assertEquals(HealthLevel.NORMAL, lvl(800.0, 14.2))
        assertEquals(HealthLevel.ATTENTION, lvl(800.0, 12.4))
        assertEquals(HealthLevel.ATTENTION, lvl(800.0, 15.6))
        assertEquals(HealthLevel.ATTENTION, lvl(0.0, 11.2))
        assertEquals(HealthLevel.OBSERVATION, lvl(0.0, 12.6))
        assertEquals(HealthLevel.OBSERVATION, lvl(250.0, 10.5))
    }

    @Test fun voltageBasisIsGenericNotOem() {
        val x = find(HealthRules.evaluate(listOf(r(HealthRules.RPM, 800.0), r(HealthRules.VOLTAGE, 12.0, "V")), now), "SUPPLY_VOLTAGE")!!
        assertTrue(x.provenance.contains("Not a Suzuki figure"))
    }

    @Test fun temperatureAtDecoderLimitIsInvestigate() {
        val f = HealthRules.evaluate(listOf(r(HealthRules.RPM, 800.0), r(HealthRules.COOLANT, -40.0, "°C")), now)
        assertEquals(HealthLevel.INVESTIGATE, find(f, "RANGE_COOLANT_TEMPERATURE")!!.level)
        assertNull("no warm-up observation from a limit value", find(f, "WARM_UP"))
        val ok = HealthRules.evaluate(listOf(r(HealthRules.RPM, 800.0), r(HealthRules.COOLANT, 85.0, "°C")), now)
        assertNull(find(ok, "RANGE_COOLANT_TEMPERATURE"))
        assertEquals(HealthLevel.OBSERVATION, find(ok, "WARM_UP")!!.level)
    }

    @Test fun railPressureWithoutTargetIsNeverJudged() {
        val x = find(HealthRules.evaluate(listOf(r(HealthRules.RPM, 800.0), r(HealthRules.RAIL, 28_000.0, "kPa")), now), "RAIL_ACTUAL")!!
        assertEquals(HealthLevel.INSUFFICIENT, x.level)
        assertTrue(x.summary.contains("28.0 MPa"))
        assertTrue(x.missing.contains("Target"))
    }

    @Test fun nothingReadMeansNoFindingsAndNoInventedValues() {
        assertTrue(HealthRules.evaluate(emptyList(), now).none { it.level == HealthLevel.NORMAL })
        val lines = HealthRules.reportLines(emptyList(), now)
        assertTrue(lines.any { it.contains("unknown") })
        assertFalse(lines.joinToString("\n").contains(" 0.0 "))
    }

    @Test fun reportStatesNoScoreNoOemLimits() {
        val text = HealthRules.reportLines(listOf(r(HealthRules.RPM, 800.0)), now).joinToString("\n")
        assertTrue(text.contains("no health score")); assertTrue(text.contains("no OEM limits"))
    }

    @Test fun fullReportContainsRulesSection() {
        val i = HealthReport.Input("t", true, "adapter", emptyList(), null, null, 0.0, emptyList(),
            dtcScan = DtcScanReport(0L, "", emptyList()), ruleReadings = listOf(r(HealthRules.RPM, 0.0), r(HealthRules.MAP, 99.0, "kPa"), r(HealthRules.BARO, 101.0, "kPa")), ruleNowMs = now)
        val text = HealthReport.lines(i).joinToString("\n")
        assertTrue(text.contains("LIVE-DATA HEALTH RULES")); assertTrue(text.contains("NORMAL: Manifold pressure against barometric pressure"))
    }
}
