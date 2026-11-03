package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingFeaturesTest {
    @Test fun tripDistanceFromSpeed() {
        val t = TripStats()
        // 60 km/h for 60 s = 1 km
        for (s in 0..60 step 5) t.add(s * 1000L, 60.0, null)
        assertEquals(1.0, t.distanceKm, 1e-6)
        assertEquals(60.0, t.averageMovingKmh()!!, 1e-6)
        assertEquals(60.0, t.maxSpeedKmh, 0.0)
    }

    @Test fun gapsAreNotFilled() {
        val t = TripStats()
        t.add(0, 60.0, null)
        t.add(60_000, 60.0, null) // 60 s gap: adapter dropped, nothing counted
        assertEquals(0.0, t.distanceKm, 0.0)
    }

    @Test fun missingSpeedAddsNothing() {
        val t = TripStats()
        t.add(0, null, null); t.add(5000, null, null)
        assertEquals(0.0, t.distanceKm, 0.0)
        assertNull(t.averageMovingKmh())
    }

    @Test fun fuelOnlyWhenEcuReportsIt() {
        val a = TripStats()
        for (s in 0..120 step 5) a.add(s * 1000L, 60.0, null)
        assertNull(a.litresPer100Km())
        val b = TripStats()
        for (s in 0..120 step 5) b.add(s * 1000L, 60.0, 6.0) // 6 L/h at 60 km/h = 10 L/100km
        assertEquals(10.0, b.litresPer100Km()!!, 1e-6)
    }

    @Test fun clockFormat() { assertEquals("1:02:03", TripStats.clock(3_723_000)) }

    @Test fun csvEscapesAndLeavesBlanks() {
        assertEquals("time,rpm,\"a,b\"", TripCsv.header(listOf("rpm", "a,b")))
        assertEquals("t,2000,,\"x\"\"y\"", TripCsv.row("t", listOf("2000", null, "x\"y")))
    }

    @Test fun alertsOffByDefaultAndHaveHysteresis() {
        assertTrue(AlertLimits().rules().isEmpty())
        val rules = AlertLimits(coolantMaxC = 105, boostMaxKpa = 150).rules()
        assertEquals(listOf("coolant-limit", "boost-limit"), rules.map { it.id })
        assertTrue(rules.all { it.clearBelow < it.triggerAtOrAbove })
        assertNull(AlertLimits.parse(500, AlertLimits.COOLANT))
    }

    @Test fun nightDimming() {
        assertEquals(0f, NightMode.dimAlpha(ThemeMode.DAY, 0f), 0f)
        assertEquals(NightMode.MAX_DIM, NightMode.dimAlpha(ThemeMode.NIGHT, 1000f), 0f)
        assertEquals(0f, NightMode.dimAlpha(ThemeMode.AUTO, null), 0f)
        assertEquals(0f, NightMode.dimAlpha(ThemeMode.AUTO, 500f), 0f)
        assertEquals(NightMode.MAX_DIM, NightMode.dimAlpha(ThemeMode.AUTO, 0f), 1e-6f)
        assertEquals(ThemeMode.AUTO, NightMode.modeOf("junk"))
    }

    @Test fun phoneLink() {
        assertEquals("192.168.43.1", HeadUnitLink.normalizeIp(" 192.168.43.1 "))
        assertNull(HeadUnitLink.normalizeIp("192.168.43"))
        assertNull(HeadUnitLink.normalizeIp("256.1.1.1"))
        assertNull(HeadUnitLink.normalizeIp("a.b.c.d"))
        assertEquals("headunit://connect?ip=10.0.0.5", HeadUnitLink.connectUri("10.0.0.5"))
        assertNull(HeadUnitLink.connectUri(""))
    }
}
