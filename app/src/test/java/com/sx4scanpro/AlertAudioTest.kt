package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertAudioTest {
    @Test fun ruleIdsMapToGauges() {
        assertEquals("coolant", AlertTargets.sensorOf("coolant-limit"))
        assertEquals("boost", AlertTargets.sensorOf("boost-limit"))
        assertNull(AlertTargets.sensorOf("mystery-limit"))
        assertNull(AlertTargets.sensorOf("coolant"))
        assertEquals(setOf("rpm"), AlertTargets.sensorsFor(setOf("rpm-limit", "junk")))
    }

    @Test fun speechNamesTheProblem() {
        assertEquals("Problem in vehicle. coolant temperature above your set limit.", AlertTargets.speech(setOf("coolant")))
        assertEquals("Problem in vehicle. boost pressure and coolant temperature above your set limit.", AlertTargets.speech(setOf("coolant", "boost")))
        assertTrue(AlertTargets.speech(emptySet()).startsWith("Problem in vehicle"))
    }

    @Test fun bannerListsGauges() {
        assertEquals("PROBLEM IN VEHICLE: BOOST + COOLANT", AlertTargets.bannerText(setOf("coolant", "boost")))
        assertEquals("PROBLEM IN VEHICLE", AlertTargets.bannerText(emptySet()))
    }

    @Test fun engineReportsActiveRulesUntilCleared() {
        val rule = WarningRule("coolant-limit", "coolant", WarningSeverity.CAUTION, 100.0, 95.0, consecutiveSamples = 1)
        val engine = WarningEngine(listOf(rule))
        assertTrue(engine.activeRuleIds().isEmpty())
        fun reading(v: Double, t: Long) = mapOf("coolant" to SensorReading(v, t, supported = true, valid = true))
        engine.evaluate(reading(101.0, 1000L), 1000L)
        assertEquals(setOf("coolant-limit"), engine.activeRuleIds())
        engine.evaluate(reading(98.0, 2000L), 2000L)
        assertEquals(setOf("coolant-limit"), engine.activeRuleIds())
        engine.evaluate(reading(90.0, 3000L), 3000L)
        assertTrue(engine.activeRuleIds().isEmpty())
    }
}
