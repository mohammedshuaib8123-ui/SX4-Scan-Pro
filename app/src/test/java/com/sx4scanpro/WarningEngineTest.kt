package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningEngineTest {
    private fun reading(value: Double, at: Long = 1_000L, supported: Boolean = true, valid: Boolean = true) =
        SensorReading(value, at, supported, valid)

    private fun rule(samples: Int = 3, cooldown: Long = 60_000L) =
        WarningRule("coolant-high", "coolant", WarningSeverity.CRITICAL,
            triggerAtOrAbove = 110.0, clearBelow = 105.0,
            consecutiveSamples = samples, cooldownMs = cooldown)

    @Test fun noRulesMeansNoWarningsByDefault() {
        val engine = WarningEngine()
        assertEquals(0, engine.configuredRuleCount())
        assertTrue(engine.evaluate(mapOf("coolant" to reading(130.0)), 1_000L).isEmpty())
    }

    @Test fun requiresConsecutiveFreshSamples() {
        val engine = WarningEngine(listOf(rule()))
        val data = mapOf("coolant" to reading(112.0))
        assertTrue(engine.evaluate(data, 1_000L).isEmpty())
        assertTrue(engine.evaluate(data, 1_100L).isEmpty())
        assertEquals("coolant-high", engine.evaluate(data, 1_200L).single().ruleId)
    }

    @Test fun staleUnsupportedAndInvalidSamplesCannotTrigger() {
        val engine = WarningEngine(listOf(rule(samples = 1)))
        assertTrue(engine.evaluate(mapOf("coolant" to reading(120.0, at = 0L)), 5_000L).isEmpty())
        assertTrue(engine.evaluate(mapOf("coolant" to reading(120.0, supported = false)), 1_000L).isEmpty())
        assertTrue(engine.evaluate(mapOf("coolant" to reading(120.0, valid = false)), 1_000L).isEmpty())
    }

    @Test fun activeWarningNeedsHysteresisToClear() {
        val engine = WarningEngine(listOf(rule(samples = 1, cooldown = 0L)))
        assertEquals(1, engine.evaluate(mapOf("coolant" to reading(111.0)), 1_000L).size)
        assertTrue(engine.evaluate(mapOf("coolant" to reading(107.0, at = 1_100L)), 1_100L).isEmpty())
        assertTrue(engine.evaluate(mapOf("coolant" to reading(104.0, at = 1_200L)), 1_200L).isEmpty())
        assertEquals(1, engine.evaluate(mapOf("coolant" to reading(111.0, at = 1_300L)), 1_300L).size)
    }

    @Test fun repeatedHighReadingsDoNotRepeatWhileWarningIsActive() {
        val engine = WarningEngine(listOf(rule(samples = 1)))
        assertEquals(1, engine.evaluate(mapOf("coolant" to reading(111.0)), 1_000L).size)
        assertTrue(engine.evaluate(mapOf("coolant" to reading(112.0, at = 1_100L)), 1_100L).isEmpty())
    }

    @Test fun cooldownPreventsImmediateRetriggerAfterClear() {
        val engine = WarningEngine(listOf(rule(samples = 1, cooldown = 10_000L)))
        assertEquals(1, engine.evaluate(mapOf("coolant" to reading(111.0)), 1_000L).size)
        engine.evaluate(mapOf("coolant" to reading(100.0, at = 1_100L)), 1_100L)
        assertTrue(engine.evaluate(mapOf("coolant" to reading(111.0, at = 2_000L)), 2_000L).isEmpty())
        assertEquals(1, engine.evaluate(mapOf("coolant" to reading(111.0, at = 11_000L)), 11_000L).size)
    }
}
