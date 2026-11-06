package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class DialLayoutTest {
    @Test fun ringNeverCollapsesInSmallSlots() {
        for (side in listOf(60f, 90f, 110f, 150f, 200f)) {
            val label = DialLayout.labelSize(side, 9f, 12f)
            assertTrue(DialLayout.ringRadius(side, side * 0.065f, label) >= side * 0.30f)
        }
    }
    @Test fun largeSlotIsUnchangedFromBefore() {
        // Before: label = max(0.05*side, 12) and radius = side/2 - (stroke/2 + 1.9*label). At 180 the new label is also 12.
        val side = 180f; val old = side / 2 - (side * 0.065f / 2 + 12f * 1.9f)
        assertEquals(old, DialLayout.ringRadius(side, side * 0.065f, DialLayout.labelSize(side, 9f, 12f)), 0.01f)
    }
    @Test fun smallSlotRingIsLargerThanBefore() {
        val side = 110f; val old = side / 2 - (side * 0.065f / 2 + 12f * 1.9f)
        assertTrue(DialLayout.ringRadius(side, side * 0.065f, DialLayout.labelSize(side, 9f, 12f)) > old)
    }
    @Test fun rowHeightDependsOnlyOnTileWidth() = assertEquals(256f, DialLayout.rowHeight(320f), 0.01f)
}
