package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class LiveGaugesTest {
    private val adv = setOf(0x0C, 0x0D, 0x05, 0x0B, 0x33, 0x23)

    @Test fun keepsDashboardOrderAndInsertsBoost() {
        val g = GaugeAssembler.build({ null }, adv, emptySet(), 10_000)
        assertEquals(listOf("Engine RPM", "Vehicle speed", "Coolant temperature", "Intake air temperature", "Manifold pressure", "Turbo boost", "MAF airflow", "Calculated engine load", "Throttle position", "EGR commanded"), g.take(10).map { it.label })
        assertTrue(g.none { it.available })
    }

    @Test fun staleReadingIsNeverShownAsLive() {
        val r = mapOf(0x0C to Reading(1726.0, 1000))
        val live = GaugeAssembler.build({ r[it] }, adv, emptySet(), 1500).first { it.label == "Engine RPM" }
        assertTrue(live.available); assertEquals("1726", live.value)
        val stale = GaugeAssembler.build({ r[it] }, adv, emptySet(), 1000 + 8000 + 1).first { it.label == "Engine RPM" }
        assertFalse(stale.available); assertEquals("--", stale.value)
    }

    // From the car log: manifold pressure arrives about every 4 s and intake temperature about every 21 s.
    @Test fun slowSensorsKeepTheirValueBetweenRealUpdates() {
        val r = mapOf(0x0B to Reading(180.0, 1000), 0x33 to Reading(100.0, 1000), 0x0F to Reading(41.0, 1000))
        val ex = setOf(0x0F)
        val a = GaugeAssembler.build({ r[it] }, adv + 0x0F, ex, 1000 + 5000)
        assertTrue(a.first { it.label == "Turbo boost" }.available)          // 5 s old: still shown, not NO SIGNAL
        assertEquals("80.0", a.first { it.label == "Turbo boost" }.value)
        assertEquals(6_000L, a.first { it.label == "Turbo boost" }.staleAfterMs)  // and labelled STALE after 6 s
        assertTrue(a.first { it.label == "Intake air temperature" }.available)
        val late = GaugeAssembler.build({ r[it] }, adv + 0x0F, ex, 1000 + 25_000)
        assertTrue(late.first { it.label == "Intake air temperature" }.available)   // 25 s old: inside its 60 s window
        assertFalse(late.first { it.label == "Turbo boost" }.available)             // MAP 25 s old: never shown as live
    }

    @Test fun boostIsMapMinusCachedBaro() {
        val r = mapOf(0x0B to Reading(180.0, 50_000), 0x33 to Reading(100.0, 5_000))
        val b = GaugeAssembler.build({ r[it] }, adv, emptySet(), 50_500).first { it.label == "Turbo boost" }
        assertEquals("80.0", b.value)
        val noBaro = GaugeAssembler.build({ r[it] }, adv, emptySet(), 100_000).first { it.label == "Turbo boost" }
        assertFalse(noBaro.available)
    }

    @Test fun notAdvertisedStaysUnavailable() {
        val r = mapOf(0x10 to Reading(5.0, 100))
        assertFalse(GaugeAssembler.build({ r[it] }, adv, emptySet(), 200).first { it.label == "MAF airflow" }.available)
    }

    @Test fun schedulerCoversEveryDashboardPid() {
        val s = PollScheduler(); PollPlan.configure(s, setOf(0x23, 0x2F))
        assertTrue(s.pids().containsAll(listOf(0x0C, 0x33, 0x23, 0x2F, 0x2C)))
    }
}
