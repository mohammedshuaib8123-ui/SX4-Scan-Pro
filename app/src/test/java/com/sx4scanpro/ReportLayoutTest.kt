package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLayoutTest {
    @Test fun shortLinesAreUnchanged() {
        assertEquals(listOf("  a", "", "b"), ReportLayout.wrap(listOf("  a", "", "b"), 40))
    }

    @Test fun longLineWrapsAtSpacesAndKeepsIndent() {
        val out = ReportLayout.wrap(listOf("  " + "word ".repeat(30).trim()), 40)
        assertTrue(out.size > 1)
        assertTrue(out.all { it.length <= 40 })
        assertTrue(out[0].startsWith("  word"))
        assertTrue(out[1].startsWith("      word"))
    }

    @Test fun noCharacterIsLost() {
        val src = listOf("    note: " + "42 0C 00 1F 40 ".repeat(25).trim(), "  P0118  [STORED, reported by engine ECU] " + "x".repeat(150))
        val out = ReportLayout.wrap(src, 50)
        assertEquals(src.joinToString("").filterNot { it == ' ' }, out.joinToString("").filterNot { it == ' ' })
        assertTrue(out.all { it.length <= 50 })
    }

    @Test fun tokenLongerThanTheLineIsHardBroken() {
        val out = ReportLayout.wrap(listOf("  raw: " + "A".repeat(200)), 40)
        assertTrue(out.all { it.length <= 40 })
        assertEquals(200, out.joinToString("").count { it == 'A' })
    }

    @Test fun paginationKeepsEveryLineInOrder() {
        val lines = (1..250).map { "line $it" }
        val pages = ReportLayout.paginate(lines, 52)
        assertEquals(5, pages.size)
        assertEquals(lines, pages.flatten())
        assertTrue(pages.all { it.size <= 52 })
    }

    @Test fun emptyReportStillHasOnePage() {
        assertEquals(1, ReportLayout.paginate(emptyList(), 10).size)
    }

    @Test fun longFullReportFitsOnSeveralPagesWithoutLoss() {
        val lines = (1..200).map { "  P0118 detail line number $it with some text to make it longer than average for the page width" }
        val wrapped = ReportLayout.wrap(lines, 80)
        val pages = ReportLayout.paginate(wrapped, 50)
        assertEquals(wrapped, pages.flatten())
        assertTrue(pages.size > 1)
    }
}
