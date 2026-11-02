package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialScaleTest {
    @Test fun fractionIsClampedAndNullSafe() {
        val s = DialScale.forLabel("Engine RPM")
        assertEquals(0f, s.fraction(null), 0f)
        assertEquals(0f, s.fraction(-50.0), 0f)
        assertEquals(0.5f, s.fraction(2500.0), 1e-6f)
        assertEquals(1f, s.fraction(9999.0), 0f)
        assertEquals(0f, s.fraction(Double.NaN), 0f)
    }

    @Test fun rpmTicksAreLabelledInThousands() {
        val s = DialScale.forLabel("Engine RPM")
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), s.majorTicks().map { s.tickLabel(it) })
    }

    @Test fun speedTicksCoverTheWholeRange() {
        val s = DialScale.forLabel("Vehicle speed")
        assertEquals(0f, s.majorTicks().first(), 0f)
        assertEquals(180f, s.majorTicks().last(), 0f)
        assertEquals(10, s.majorTicks().size)
    }

    @Test fun everyDashboardLabelHasAScale() {
        listOf(
            "Engine RPM", "Vehicle speed", "Coolant temperature", "Intake air temperature",
            "Manifold pressure", "Turbo boost", "MAF airflow", "Calculated engine load", "Throttle position"
        ).forEach { assertTrue(it, DialScale.forLabel(it).max > 0f && DialScale.forLabel(it).majorTicks().size >= 3) }
    }
}
