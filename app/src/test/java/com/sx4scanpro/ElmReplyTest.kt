package com.sx4scanpro

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ElmReplyTest {
    @Test fun classifiesCommonReplies() {
        assertEquals(ElmKind.NO_DATA, ElmReply.classify("NO DATA"))
        assertEquals(ElmKind.UNABLE_TO_CONNECT, ElmReply.classify("SEARCHING...\rUNABLE TO CONNECT"))
        assertEquals(ElmKind.OK, ElmReply.classify("OK"))
        assertEquals(ElmKind.UNKNOWN_COMMAND, ElmReply.classify("?"))
        assertEquals(ElmKind.STOPPED, ElmReply.classify("STOPPED"))
        assertEquals(ElmKind.BUS_BUSY, ElmReply.classify("BUS BUSY"))
        assertEquals(ElmKind.EMPTY, ElmReply.classify("  "))
        assertEquals(ElmKind.DATA, ElmReply.classify("41 0C 1A F8"))
    }

    @Test fun bannerIsStrippedButDataKept() {
        assertEquals("410C1AF8", ElmReply.stripBanners("SEARCHING...\rBUS INIT: OK\r41 0C 1A F8"))
        assertEquals(ElmKind.DATA, ElmReply.classify("SEARCHING...\r41 0C 1A F8"))
    }

    @Test fun silentEcuStates() {
        assertTrue(ElmReply.isEcuSilent(ElmKind.NO_DATA))
        assertTrue(ElmReply.isEcuSilent(ElmKind.UNABLE_TO_CONNECT))
        assertFalse(ElmReply.isEcuSilent(ElmKind.DATA))
        assertFalse(ElmReply.isEcuSilent(ElmKind.OK))
    }
}

class SessionLogTest {
    private fun tmp() = File.createTempFile("slog", "").let { it.delete(); it.mkdirs(); it }

    @Test fun appendsAndReadsBack() {
        val log = SessionLog(tmp())
        log.append("TX", "010C", 0L)
        log.append("RX", "NO DATA", 1L)
        val text = log.readAll()
        assertTrue(text.contains("TX 010C"))
        assertTrue(text.indexOf("TX 010C") < text.indexOf("RX NO DATA"))
    }

    @Test fun rotatesAndKeepsOnlyNewest() {
        val dir = tmp()
        val log = SessionLog(dir, maxBytes = 200, keep = 3)
        repeat(60) { log.append("TX", "line-$it-xxxxxxxxxxxx", it.toLong()) }
        assertTrue(dir.listFiles()!!.count { it.name.startsWith("session") } <= 3)
        val text = log.readAll()
        assertTrue(text.contains("line-59-"))
        assertFalse(text.contains("line-0-"))
    }

    @Test fun exportHasHeaderAndLog() {
        val log = SessionLog(tmp())
        log.append("EVT", "ignition on", 0L)
        val f = log.export("HEADER")!!
        val s = f.readText()
        assertTrue(s.startsWith("HEADER"))
        assertTrue(s.contains("ignition on"))
    }

    @Test fun neverThrowsOnBadDirectory() {
        val bad = File.createTempFile("notadir", "")
        SessionLog(File(bad, "sub")).append("TX", "x")
    }
}
