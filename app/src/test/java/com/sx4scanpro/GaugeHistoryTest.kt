package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeHistoryTest {
    @Test fun gapsAreNotRecordedAsZero() {
        val h = GaugeHistory()
        h.record("rpm", 800.0)
        h.record("rpm", null)
        h.record("rpm", Double.NaN)
        assertEquals(listOf(800f), h.series("rpm"))
    }

    @Test fun keepsOnlyTheNewestSamples() {
        val h = GaugeHistory(capacity = 3)
        (1..5).forEach { h.record("x", it.toDouble()) }
        assertEquals(listOf(3f, 4f, 5f), h.series("x"))
    }

    @Test fun seriesAreIndependentAndUnknownIsEmpty() {
        val h = GaugeHistory()
        h.record("a", 1.0)
        assertTrue(h.series("b").isEmpty())
        h.clear()
        assertTrue(h.series("a").isEmpty())
    }

    @Test fun trendNeedsTwoPoints() {
        assertTrue(GaugeHistory.trend(emptyList()).isEmpty())
        assertTrue(GaugeHistory.trend(listOf(5f)).isEmpty())
    }

    @Test fun trendSpansTheUnitSquareWithMargin() {
        val t = GaugeHistory.trend(listOf(10f, 20f, 30f))
        assertEquals(listOf(0f, 0.5f, 1f), t.map { it.x })
        assertEquals(0.08f, t.first().y, 1e-6f)
        assertEquals(0.92f, t.last().y, 1e-6f)
    }

    @Test fun flatLineIsCentredNotStretched() {
        val t = GaugeHistory.trend(listOf(90f, 90f, 90f, 90f))
        assertTrue(t.all { it.y == 0.5f })
    }
}
