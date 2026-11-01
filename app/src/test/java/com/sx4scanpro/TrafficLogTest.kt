package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLogTest {
    @Test fun keepsOnlyTheNewestLines() {
        val log = TrafficLog(maxLines = 3)
        (1..5).forEach { log.add("TX", "cmd$it", nowMs = 0) }
        val lines = log.snapshot()
        assertEquals(3, lines.size)
        assertTrue(lines.first().endsWith("TX cmd3"))
        assertTrue(lines.last().endsWith("TX cmd5"))
    }

    @Test fun tailReturnsNewestLastAndTrimsText() {
        val log = TrafficLog()
        log.add("TX", "0100\r", nowMs = 0)
        log.add("RX", "  41 00 BE 3F A8 13  ", nowMs = 0)
        assertEquals(listOf("RX 41 00 BE 3F A8 13"), log.tail(1).map { it.substringAfter(' ') })
    }

    @Test fun textExportCarriesTheHeaderAndHandlesEmpty() {
        val log = TrafficLog()
        assertTrue(log.asText("v1").contains("no adapter traffic"))
        log.add("ERR", "timeout", nowMs = 0)
        val text = log.asText("v1\nstatus: x")
        assertTrue(text.startsWith("v1\nstatus: x\n----\n"))
        assertTrue(text.endsWith("ERR timeout"))
    }

    @Test fun clearEmptiesTheLog() {
        val log = TrafficLog()
        log.add("TX", "x")
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }
}
