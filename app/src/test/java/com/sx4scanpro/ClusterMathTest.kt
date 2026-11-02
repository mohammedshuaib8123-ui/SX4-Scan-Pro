package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterMathTest {
    @Test fun segments() {
        assertEquals(0, ClusterMath.litSegments(0f, 12))
        assertEquals(12, ClusterMath.litSegments(1f, 12))
        assertEquals(6, ClusterMath.litSegments(0.5f, 12))
        assertEquals(12, ClusterMath.litSegments(3f, 12))
        assertEquals(0, ClusterMath.litSegments(Float.NaN, 12))
        assertEquals(0, ClusterMath.litSegments(0.5f, 0))
    }

    @Test fun phaseClamps() {
        assertEquals(0f, ClusterMath.phase(0.1f, 0.2f, 0.4f), 0f)
        assertEquals(0.5f, ClusterMath.phase(0.3f, 0.2f, 0.4f), 1e-5f)
        assertEquals(1f, ClusterMath.phase(0.9f, 0.2f, 0.4f), 0f)
    }

    @Test fun welcomeNeedleRisesThenReturnsToZero() {
        assertEquals(0f, ClusterMath.welcomeNeedle(0f), 0f)
        assertEquals(1f, ClusterMath.welcomeNeedle(0.5f), 1e-5f)
        assertEquals(0f, ClusterMath.welcomeNeedle(0.82f), 1e-5f)
        assertEquals(0f, ClusterMath.welcomeNeedle(1f), 1e-5f)
    }
}
