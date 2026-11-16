package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class DataCheckTest {
    private val RPM = DataCheckMonitor.RPM; private val SPEED = DataCheckMonitor.SPEED; private val COOLANT = DataCheckMonitor.COOLANT
    private val LOAD = DataCheckMonitor.LOAD; private val MAP = DataCheckMonitor.MAP; private val IAT = DataCheckMonitor.IAT
    private val MAF = DataCheckMonitor.MAF; private val RAIL = DataCheckMonitor.RAIL; private val VOLT = DataCheckMonitor.VOLT; private val BARO = DataCheckMonitor.BARO

    private class Clock { var t = 1_000_000L }

    /** Warm idle as seen on the real car: 800 rpm, stationary, rail 28.8 MPa, 14.2 V. */
    private fun idle(rail: Double = 28_800.0, rpm: Double = 800.0, extra: Map<Int, Double> = emptyMap()) = mapOf(
        RPM to rpm, SPEED to 0.0, COOLANT to 88.0, LOAD to 20.0, MAP to 94.0, BARO to 93.0, IAT to 30.0, MAF to 3.5, RAIL to rail, VOLT to 14.2
    ) + extra

    /** Steady cruise: ~2000 rpm, 80 km/h, air flow giving an efficiency of about 0.75 like the capture. */
    private fun cruise(map: Double = 120.0, load: Double = 50.0, rail: Double = 70_000.0, maf: Double? = null): Map<Int, Double> {
        val dens = 1.184 * (map / 101.3) * (293.15 / 303.15)
        val m = maf ?: (0.75 * (2000.0 / 120.0) * 1.248 * dens)
        return mapOf(RPM to 2000.0, SPEED to 80.0, COOLANT to 90.0, LOAD to load, MAP to map, BARO to 93.0, IAT to 30.0, MAF to m, RAIL to rail, VOLT to 14.2)
    }

    private fun run(m: DataCheckMonitor, c: Clock, seconds: Int, values: (Int) -> Map<Int, Double>) {
        for (i in 0 until seconds) { c.t += 1000; m.update(c.t, values(i)) }
    }

    private fun ids(m: DataCheckMonitor) = m.findings().map { it.id }

    @Test fun healthyDriveRaisesNothingAndLearnsTheReferences() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 400) { i -> idle(rail = 28_800.0 + (i % 5) * 300 - 600) }
        run(m, c, 400) { i -> cruise(rail = 70_000.0 + (i % 7) * 500) }
        assertEquals(emptyList<String>(), ids(m))
        val rows = m.baselineRows()
        assertTrue(rows[0].have >= Baselines.MIN_READY); assertEquals(28.8, rows[0].reference!!, 0.5)
        assertTrue(rows[2].have >= Baselines.MIN_READY); assertEquals(0.75, rows[2].reference!!, 0.02)
    }

    @Test fun railPressureCollapseIsAnAlertAndClearsAfterItRecovers() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 40) { idle() }
        run(m, c, 8) { idle(rail = 9_000.0) }
        assertTrue("rail-collapse" in ids(m)); assertEquals(CheckLevel.ALERT, m.findings().first { it.id == "rail-collapse" }.level)
        run(m, c, 10) { idle() }
        assertTrue("still shown until the hysteresis passes", "rail-collapse" in ids(m))
        run(m, c, 10) { idle() }
        assertFalse("rail-collapse" in ids(m))
    }

    @Test fun railPressureAtIdleThatDiffersFromTheLearnedNormalWarns() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 300) { idle() }
        run(m, c, 25) { idle(rail = 19_000.0) }
        val f = m.findings().firstOrNull { it.id == "rail-idle" }
        assertNotNull(f); assertTrue(f!!.detail.contains("28.8"))
    }

    @Test fun smallIdleRailVariationIsNotAWarning() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 300) { idle() }
        run(m, c, 60) { i -> idle(rail = 28_800.0 + if (i % 2 == 0) 1_500.0 else -1_500.0) }
        assertFalse("rail-idle" in ids(m))
    }

    @Test fun nothingIsLearnedWhileAWarningIsActive() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 300) { idle() }
        val before = m.baselineRows()[0].have
        run(m, c, 60) { idle(rail = 9_000.0) }         // collapse is active, so reference must not move
        assertEquals(before, m.baselineRows()[0].have)
    }

    @Test fun noIdleComparisonUntilEnoughSamplesAreLearned() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 60) { idle() }                       // about 20 samples: not ready
        run(m, c, 30) { idle(rail = 19_000.0) }
        assertFalse("rail-idle" in ids(m))
    }

    @Test fun sensorsReadingOutsideWhatIsPossibleAreFlagged() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 8) { idle(extra = mapOf(COOLANT to 215.0)) }
        assertEquals(CheckLevel.ALERT, m.findings().first { it.id == "coolant-sensor" }.level)
        val c2 = Clock(); val m2 = DataCheckMonitor()
        run(m2, c2, 12) { idle(extra = mapOf(IAT to -40.0)) }
        assertTrue("iat-sensor" in ids(m2))
    }

    @Test fun manifoldPressureThatDisagreesWithBaroWhenStoppedIsFlagged() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 25) { mapOf(RPM to 0.0, MAP to 125.0, BARO to 93.0, VOLT to 12.9) }
        assertTrue("map-baro" in ids(m))
        val c2 = Clock(); val m2 = DataCheckMonitor()
        run(m2, c2, 25) { mapOf(RPM to 0.0, MAP to 91.0, BARO to 93.0, VOLT to 12.9) }      // real engine-off readings: MAP 91, baro 93
        assertFalse("map-baro" in ids(m2))
    }

    @Test fun lowBoostAtHighLoadWarnsButRealBoostDoesNot() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 40) { cruise(map = 100.0, load = 90.0) }
        assertTrue("boost-low" in ids(m))
        val c2 = Clock(); val m2 = DataCheckMonitor()
        run(m2, c2, 40) { cruise(map = 220.0, load = 90.0) }      // real capture: up to 232 kPa
        assertFalse("boost-low" in ids(m2))
    }

    @Test fun airFlowThatCannotMatchEngineSpeedAndBoostWarns() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 40) { cruise(maf = 5.0) }
        val f = m.findings().firstOrNull { it.id == "maf-ve" }
        assertNotNull(f); assertTrue(f!!.detail.contains("outside the possible range"))
    }

    @Test fun airFlowDriftFromTheLearnedNormalWarnsWithTheUsualValue() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 300) { cruise() }                                   // learns about 0.75
        val dens = 1.184 * (120.0 / 101.3) * (293.15 / 303.15)
        run(m, c, 40) { cruise(maf = 0.50 * (2000.0 / 120.0) * 1.248 * dens) }      // 0.50 is inside the absolute band but 33% below normal
        val f = m.findings().firstOrNull { it.id == "maf-ve" }
        assertNotNull(f); assertTrue(f!!.detail.contains("usually shows 75%"))
    }

    @Test fun chargingVoltageProblemsAreFlaggedOnlyWhileTheEngineRuns() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 60) { idle(extra = mapOf(VOLT to 12.4)) }
        assertTrue("volt-low" in ids(m))
        val c2 = Clock(); val m2 = DataCheckMonitor()
        run(m2, c2, 40) { idle(extra = mapOf(VOLT to 11.5)) }
        assertEquals(CheckLevel.ALERT, m2.findings().first { it.id == "volt-critical" }.level)
        val c3 = Clock(); val m3 = DataCheckMonitor()
        run(m3, c3, 40) { idle(extra = mapOf(VOLT to 15.6)) }
        assertTrue("volt-high" in ids(m3))
        val c4 = Clock(); val m4 = DataCheckMonitor()
        run(m4, c4, 60) { mapOf(RPM to 0.0, VOLT to 12.4) }          // key on, engine off: battery voltage is normal here
        assertEquals(emptyList<String>(), ids(m4))
    }

    @Test fun crankingVoltageDipIsNotAFault() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 3) { mapOf(RPM to 250.0, VOLT to 10.4) }
        run(m, c, 19) { idle(extra = mapOf(VOLT to 12.6)) }
        assertEquals(emptyList<String>(), ids(m))
    }

    @Test fun aSensorThatNeverChangesWhileEngineSpeedMovesIsFlagged() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 130) { i -> cruise().toMutableMap().also { it[RPM] = 800.0 + (i % 40) * 60.0; it[MAP] = 120.0 } }
        assertTrue("stuck-${MAP}" in ids(m))
        val c2 = Clock(); val m2 = DataCheckMonitor()
        run(m2, c2, 130) { i -> cruise().toMutableMap().also { it[RPM] = 800.0 + (i % 40) * 60.0; it[MAP] = 100.0 + (i % 40) } }
        assertFalse("stuck-${MAP}" in ids(m2))
    }

    @Test fun engineThatNeverWarmsUpWhileDrivingIsFlagged() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 11 * 60) { cruise().toMutableMap().also { it[COOLANT] = 58.0 } }
        assertTrue("thermostat" in ids(m))
        val c2 = Clock(); val m2 = DataCheckMonitor()
        run(m2, c2, 11 * 60) { i -> cruise().toMutableMap().also { it[COOLANT] = if (i < 300) 50.0 + i * 0.15 else 95.0 } }
        assertFalse("thermostat" in ids(m2))
    }

    @Test fun tallyShowsMinAvgMaxAndRailInMegapascal() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 10) { i -> idle(rail = 28_000.0 + i * 100) }
        val rail = m.tally().first { it.label == "Fuel rail pressure" }
        assertEquals("MPa", rail.unit); assertEquals(28.0, rail.min, 0.001); assertEquals(28.9, rail.max, 0.001); assertEquals(28.45, rail.avg, 0.001)
        assertEquals(10, rail.seconds)
    }

    @Test fun tripSummaryCountsIdleMovingAndHardWork() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 30) { idle() }
        run(m, c, 30) { cruise(load = 90.0).toMutableMap().also { it[RPM] = 3200.0 } }
        val s = m.summary()
        assertTrue(s.runSeconds in 55..61); assertTrue(s.idleSeconds in 28..30); assertTrue(s.movingSeconds in 28..30)
        assertTrue(s.highRpmSeconds in 28..30); assertTrue(s.heavyLoadSeconds in 28..30); assertEquals(50, s.pct(s.idleSeconds))
    }

    @Test fun learnedReferencesSurviveAJsonRoundTripAndResetClearsThem() {
        val c = Clock(); val m1 = DataCheckMonitor()
        run(m1, c, 300) { idle() }
        val json = m1.baselinesJsonIfDirty()!!
        assertNull(m1.baselinesJsonIfDirty())
        val m2 = DataCheckMonitor(Baselines.fromJson(json))
        assertEquals(m1.baselineRows()[0].have, m2.baselineRows()[0].have)
        run(m2, c, 50) { idle(rail = 19_000.0) }
        assertTrue("rail-idle" in ids(m2))
        m2.resetLearning()
        assertEquals(0, m2.baselineRows()[0].have)
    }

    @Test fun brokenBaselineFileIsIgnored() {
        assertEquals(0, Baselines.fromJson("not json").count("x"))
        assertEquals(0, Baselines.fromJson(null).count("x"))
        assertEquals(1, Baselines.fromJson("{\"x\":[1.5,\"bad\",null]}").count("x"))
    }

    @Test fun startTripClearsCountersAndFindingsButKeepsTheReferences() {
        val c = Clock(); val m = DataCheckMonitor()
        run(m, c, 300) { idle() }
        run(m, c, 8) { idle(rail = 9_000.0) }
        assertTrue(m.findings().isNotEmpty())
        val have = m.baselineRows()[0].have
        m.startTrip()
        assertTrue(m.findings().isEmpty()); assertTrue(m.tally().isEmpty()); assertEquals(have, m.baselineRows()[0].have)
    }

    // ---- fuel log ----
    private fun fill(odo: Double, l: Double, full: Boolean = true) = FillUp(odo, l, 0L, full)

    @Test fun fuelSegmentsAreTankToTankAndTheFirstFillOnlyStartsTheCount() {
        val segs = FuelLog.segments(listOf(fill(10_000.0, 30.0), fill(10_500.0, 30.0), fill(11_000.0, 28.0)))
        assertEquals(2, segs.size); assertEquals(500.0, segs[0].km, 0.0); assertEquals(16.67, segs[0].kmPerL, 0.01); assertEquals(6.0, segs[0].l100, 0.001)
    }

    @Test fun partialFillsAreAddedToTheNextFullOne() {
        val segs = FuelLog.segments(listOf(fill(1000.0, 40.0), fill(1200.0, 10.0, full = false), fill(1500.0, 20.0)))
        assertEquals(1, segs.size); assertEquals(30.0, segs[0].litres, 0.0); assertEquals(500.0, segs[0].km, 0.0)
    }

    @Test fun tooShortSegmentsAreIgnoredBecauseTheyAreNoise() {
        assertTrue(FuelLog.segments(listOf(fill(1000.0, 40.0), fill(1050.0, 3.0))).isEmpty())
    }

    @Test fun aDropInFuelEconomyAgainstTheUsualWarns() {
        val fills = listOf(fill(0.0, 30.0), fill(500.0, 30.0), fill(1000.0, 30.0), fill(1500.0, 31.0), fill(1900.0, 33.0))
        val f = FuelLog.trend(FuelLog.segments(fills), 5L)
        assertNotNull(f); assertEquals(CheckLevel.WARN, f!!.level); assertTrue(f.detail.contains("usual 16.7 km/L"))
    }

    @Test fun normalVariationAndTooLittleHistoryGiveNoFuelWarning() {
        assertNull(FuelLog.trend(FuelLog.segments(listOf(fill(0.0, 30.0), fill(500.0, 30.0), fill(1000.0, 32.0), fill(1500.0, 31.0))), 5L))
        assertNull(FuelLog.trend(FuelLog.segments(listOf(fill(0.0, 30.0), fill(500.0, 30.0), fill(1000.0, 60.0))), 5L))   // only two tanks: no baseline yet
    }

    @Test fun veryLargeDropIsAnAlert() {
        val fills = listOf(fill(0.0, 30.0), fill(500.0, 30.0), fill(1000.0, 30.0), fill(1500.0, 30.0), fill(1900.0, 50.0))
        assertEquals(CheckLevel.ALERT, FuelLog.trend(FuelLog.segments(fills), 5L)!!.level)
    }

    @Test fun fuelEntriesAreValidatedAndSerialised() {
        val l = listOf(fill(1000.0, 30.0))
        assertNotNull(FuelLog.validate(l, null, 30.0)); assertNotNull(FuelLog.validate(l, 900.0, 30.0)); assertNotNull(FuelLog.validate(l, 1500.0, 0.0))
        assertNotNull(FuelLog.validate(l, 1500.0, 500.0)); assertNull(FuelLog.validate(l, 1500.0, 30.0)); assertNull(FuelLog.validate(emptyList(), 100.0, 20.0))
        assertEquals(l, FuelLog.parse(FuelLog.serialize(l)))
        assertTrue(FuelLog.parse("garbage;1,2").isEmpty())
    }
}
