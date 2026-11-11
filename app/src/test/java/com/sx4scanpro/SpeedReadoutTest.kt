package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedReadoutTest {
    private fun at(available: Boolean, value: String, captured: Long, now: Long) = SpeedReadout.from(available, value, captured, now)

    @Test fun validSpeedShowsThreeDigitsAndDimmedLeadingZeros() {
        val r = SpeedReadout.of(42.0)
        assertEquals("042", r.digits); assertEquals("0", r.dimPrefix); assertTrue(r.valid)
        assertEquals("007", SpeedReadout.of(7.0).digits); assertEquals("00", SpeedReadout.of(7.0).dimPrefix)
        assertEquals("120", SpeedReadout.of(120.0).digits); assertEquals("", SpeedReadout.of(120.0).dimPrefix)
    }

    @Test fun anActualZeroFromTheEcuIsZeroNotADash() {
        val r = at(true, "0", 1000, 1500)
        assertTrue(r.valid); assertEquals(0, r.kmh); assertEquals("000", r.digits); assertEquals("00", r.dimPrefix); assertEquals(0, r.litSegments())
    }

    @Test fun noSpeedIsADashWithAnEmptyBarNeverZero() {
        for (r in listOf(SpeedReadout.of(null), at(false, "42", 1000, 1000), at(true, "--", 1000, 1000), at(true, "", 1000, 1000), at(true, "abc", 1000, 1000))) {
            assertFalse(r.valid); assertNull(r.kmh); assertEquals("—", r.digits); assertEquals("", r.dimPrefix); assertEquals(0, r.litSegments())
        }
    }

    @Test fun staleSpeedIsADashAtTheSharedLimit() {
        assertTrue(at(true, "42", 1000, 1000 + STALE_MS).valid)          // exactly at the limit is still fresh
        assertFalse(at(true, "42", 1000, 1000 + STALE_MS + 1).valid)
        assertEquals("—", at(true, "42", 1000, 60_000).digits)
        assertEquals(5_000L, STALE_MS)                                    // same limit the cluster uses for "stale"
    }

    @Test fun impossibleValuesAreRejected() {
        for (v in listOf(-1.0, 256.0, 1000.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertFalse("$v", SpeedReadout.of(v).valid)
        assertEquals("255", SpeedReadout.of(255.0).digits)
        assertEquals("000", SpeedReadout.of(0.0).digits)
    }

    @Test fun barMatchesTheDisplayedSpeed() {
        assertEquals(0, SpeedReadout.of(0.0).litSegments())
        assertEquals(4, SpeedReadout.of(42.0).litSegments())             // 42 * 20 / 180
        assertEquals(10, SpeedReadout.of(90.0).litSegments())
        assertEquals(20, SpeedReadout.of(180.0).litSegments())
        assertEquals(20, SpeedReadout.of(255.0).litSegments())           // faster than the bar: full bar, exact number
        var prev = 0
        for (k in 0..255) { val n = SpeedReadout(k).litSegments(); assertTrue(n in prev..20); prev = n }
    }

    // ---- through the existing polling / gauge pipeline ----
    private val adv = setOf(0x0D)
    private fun speedGauge(reading: Reading?, advertised: Set<Int>, now: Long): LiveGauge =
        GaugeAssembler.build({ if (it == 0x0D) reading else null }, advertised, emptySet(), now).first { it.label == "Vehicle speed" }
    private fun readoutOf(g: LiveGauge, now: Long) = SpeedReadout.from(g.available, g.value, g.capturedAtMs, now)

    @Test fun pipelineFreshReadingShowsTheEcuSpeed() {
        val g = speedGauge(Reading(42.0, 1000), adv, 1500)
        assertEquals(42, readoutOf(g, 1500).kmh)
    }

    @Test fun pipelineStaleOrUnadvertisedReadingBecomesADash() {
        val old = speedGauge(Reading(42.0, 1000), adv, 1000 + 4001)       // assembler drops it after max(4 s, 3 x interval)
        assertFalse(old.available); assertFalse(readoutOf(old, 5001).valid)
        val unadvertised = speedGauge(Reading(42.0, 1000), emptySet(), 1500)
        assertFalse(readoutOf(unadvertised, 1500).valid)
        assertFalse(readoutOf(speedGauge(null, adv, 1500), 1500).valid)
    }

    @Test fun pipelineZeroSpeedIsAValidZero() {
        val g = speedGauge(Reading(0.0, 1000), adv, 1200)
        assertEquals("000", readoutOf(g, 1200).digits)
    }

    @Test fun nothingIsExtrapolatedBetweenUpdates() {
        val g = speedGauge(Reading(42.0, 1000), adv, 3000)                // two seconds later, no new ECU reading
        assertEquals(42, readoutOf(g, 3000).kmh)                          // still the last real value, not 42 + drift
    }
}
