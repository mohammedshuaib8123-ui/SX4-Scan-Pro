package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeUnitsTest {
    @Test fun speedConvertsToMph() {
        assertEquals(62.0, GaugeUnits.speed(100.0, SpeedUnit.MPH), 0.2)
        assertEquals("62", GaugeUnits.speedText(100.0, SpeedUnit.MPH))
        assertEquals("100", GaugeUnits.speedText(100.0, SpeedUnit.KMH))
    }

    @Test fun boostConverts() {
        assertEquals("100", GaugeUnits.boostText(100.0, BoostUnit.KPA))
        assertEquals("1.0", GaugeUnits.boostText(100.0, BoostUnit.BAR))
        assertEquals("14.5", GaugeUnits.boostText(100.0, BoostUnit.PSI))
    }

    @Test fun unknownSavedNamesFallBackToDefaults() {
        assertEquals(SpeedUnit.KMH, GaugeUnits.speedUnitOf(null))
        assertEquals(SpeedUnit.MPH, GaugeUnits.speedUnitOf("MPH"))
        assertEquals(BoostUnit.KPA, GaugeUnits.boostUnitOf("garbage"))
        assertEquals(BoostUnit.PSI, GaugeUnits.boostUnitOf("PSI"))
    }

    @Test fun scalesFollowUnit() {
        assertEquals(120f, GaugeUnits.speedScale(SpeedUnit.MPH).max, 0f)
        assertEquals(30f, GaugeUnits.boostScale(BoostUnit.PSI).max, 0f)
    }

    @Test fun deviceGMagnitude() {
        assertEquals(0.0, DeviceG.magnitudeG(0f, 0f, 0f), 1e-9)
        assertEquals(1.0, DeviceG.magnitudeG(9.80665f, 0f, 0f), 1e-4)
        assertEquals(1.0, DeviceG.magnitudeG(0f, 6.934f, 6.934f), 1e-3)
    }
}
