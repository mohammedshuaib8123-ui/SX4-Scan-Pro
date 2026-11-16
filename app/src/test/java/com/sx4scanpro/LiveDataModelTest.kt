package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class LiveDataModelTest {
    private val now = 100_000L
    private fun g(label: String, v: String, unit: String, ok: Boolean, at: Long = now, stale: Long = 5_000L) = LiveReading(label, v, unit, ok, at, stale)
    private fun rows(gs: List<LiveReading>, sup: Set<Int>, connected: Boolean = true, reg: (Int) -> ParamState? = { null }) = LiveDataModel.rows(gs, sup, connected, now, reg)
    private fun row(rs: List<LiveRow>, label: String) = rs.first { it.label == label }

    @Test fun freshReadingIsLiveWithItsValueAndPid() {
        val r = row(rows(listOf(g("Engine RPM", "850", "rpm", true, now - 1_000)), setOf(0x0C)) { ParamState.DECODED }, "Engine RPM")
        assertEquals(Freshness.LIVE, r.freshness); assertEquals("850", r.value); assertEquals(0x0C, r.pid)
        assertTrue(r.source.contains("PID 0C") && r.source.contains("DECODED")); assertEquals(LiveCategory.ENGINE, r.category)
    }

    @Test fun oldReadingIsStaleAndNeverLive() {
        val r = row(rows(listOf(g("Engine RPM", "850", "rpm", true, now - 9_000, 4_000)), setOf(0x0C)), "Engine RPM")
        assertEquals(Freshness.STALE, r.freshness); assertTrue(r.note.contains("9 s ago"))
    }

    @Test fun missingReadingShowsDashesNotZero() {
        val rs = rows(listOf(g("Coolant temperature", "--", "°C", false), g("Engine RPM", "0", "rpm", false)), setOf(0x05, 0x0C))
        assertTrue(rs.all { it.value == "--" && it.freshness == Freshness.UNAVAILABLE && it.ageMs == null })
    }

    @Test fun unsupportedOnlyWhenTheEcuListWasReadAndLacksThePid() {
        val gs = listOf(g("Fuel rail pressure", "--", "kPa", false))
        assertEquals(Freshness.UNSUPPORTED, row(rows(gs, setOf(0x0C)), "Fuel rail pressure").freshness)
        assertEquals(Freshness.UNAVAILABLE, row(rows(gs, emptySet()), "Fuel rail pressure").freshness)
        assertEquals(Freshness.UNAVAILABLE, row(rows(gs, setOf(0x23)), "Fuel rail pressure").freshness)
        assertEquals("not connected", row(rows(gs, setOf(0x0C), connected = false), "Fuel rail pressure").note)
    }

    @Test fun boostIsLabelledDerivedAndNeedsBothPids() {
        val gs = listOf(g("Turbo boost", "--", "kPa", false))
        assertEquals(Freshness.UNSUPPORTED, row(rows(gs, setOf(0x0B)), "Turbo boost").freshness)
        val live = row(rows(listOf(g("Turbo boost", "55.0", "kPa", true)), setOf(0x0B, 0x33)), "Turbo boost")
        assertEquals(Freshness.LIVE, live.freshness); assertTrue(live.source.startsWith("Derived")); assertNull(live.pid); assertEquals(LiveCategory.AIR_BOOST, live.category)
    }

    @Test fun everyCatalogParameterAndCoreGaugeHasACategory() {
        assertTrue(PidCatalog.all.all { LiveDataModel.categoryOfPid(it.pid) != null })
        PollPlan.core.forEach { assertNotNull(it.label, LiveDataModel.categoryOfPid(it.pid)) }
        assertNull(rows(listOf(g("Mystery", "1", "x", true)), emptySet()).firstOrNull())
    }

    @Test fun groupsFollowFixedOrderAndSkipEmptyOnes() {
        val gs = listOf(g("Coolant temperature", "90", "°C", true), g("Engine RPM", "800", "rpm", true), g("Control module voltage", "13.9", "V", true))
        val groups = LiveDataModel.grouped(rows(gs, setOf(0x05, 0x0C, 0x42)))
        assertEquals(listOf(LiveCategory.ENGINE, LiveCategory.TEMPERATURE, LiveCategory.ELECTRICAL), groups.map { it.first })
    }

    @Test fun summaryCountsEachState() {
        val gs = listOf(g("Engine RPM", "800", "rpm", true), g("Vehicle speed", "5", "km/h", true, now - 20_000, 4_000), g("Coolant temperature", "--", "°C", false))
        assertEquals("1 live • 1 stale • 1 unavailable • 0 unsupported", LiveDataModel.summary(rows(gs, setOf(0x0C, 0x0D, 0x05))))
    }
}
