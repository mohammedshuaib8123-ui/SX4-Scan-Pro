package com.sx4scanpro

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CaptureStoreTest {
    private fun tmp() = File.createTempFile("cap", "").let { it.delete(); it.mkdirs(); it }

    @Test fun rawReplyIsKeptByteForByte() {
        val s = CaptureStore(tmp())
        val raw = "SEARCHING...\r41 0C  1A F8 \r\n\"odd\" \\ \u0001 é\r"
        s.record("S1", 10L, "010C", raw, "DATA", 120, "ISO 14230-4")
        assertEquals(raw, s.readAll().single().rawText)
    }

    @Test fun sequenceSurvivesRestartAndIncreases() {
        val d = tmp()
        val a = CaptureStore(d); a.record("S", 1, "0100", "x", "DATA", 1, "p"); a.record("S", 2, "0100", "y", "DATA", 1, "p")
        val b = CaptureStore(d); b.record("S", 3, "0100", "z", "DATA", 1, "p")
        assertEquals(listOf(1L, 2L, 3L), b.readAll().map { it.seq })
    }

    @Test fun rotatesAndKeepsNewest() {
        val d = tmp()
        val s = CaptureStore(d, maxBytes = 400, keep = 3)
        repeat(40) { s.record("S", it.toLong(), "0100", "reply-$it-xxxxxxxx", "DATA", 1, "p") }
        assertTrue(d.listFiles()!!.size <= 3)
        val all = s.readAll()
        assertTrue(all.last().rawText.startsWith("reply-39"))
        assertFalse(all.any { it.rawText.startsWith("reply-0-") })
    }

    @Test fun errorsTimeoutsAndBlockedAreRecordedWithStatus() {
        val s = CaptureStore(tmp())
        s.record("S", 1, "010C", "", RawExchange.STATUS_TIMEOUT, 5000, "p", error = "no prompt")
        s.record("S", 2, "04", "", RawExchange.STATUS_BLOCKED, 0, "p", error = "clears DTCs")
        val r = s.readAll()
        assertEquals(listOf("TIMEOUT", "BLOCKED"), r.map { it.status })
        assertEquals("clears DTCs", r[1].error)
    }

    @Test fun corruptLineIsSkippedNotRepaired() {
        val d = tmp(); val s = CaptureStore(d)
        s.record("S", 1, "0100", "a", "DATA", 1, "p")
        File(d, "capture.jsonl").appendText("{broken\n")
        s.record("S", 2, "0100", "b", "DATA", 1, "p")
        assertEquals(listOf("a", "b"), s.readAll().map { it.rawText })
    }

    @Test fun neverThrowsOnBadDirectory() {
        val bad = File.createTempFile("notadir", "")
        assertNull(CaptureStore(File(bad, "sub")).record("S", 1, "0100", "a", "DATA", 1, "p"))
    }

    @Test fun statusComesFromAdapterClassification() {
        assertEquals("NO_DATA", RawExchange.statusOf("NO DATA"))
        assertEquals("DATA", RawExchange.statusOf("41 0C 1A F8"))
    }
}
