package com.sx4scanpro

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class ValidationExportTest {
    private fun zip(snap: ValidationSnapshot?, redact: Boolean = false): Pair<Map<String, String>, ExportResult> {
        val dir = Files.createTempDirectory("sx4val").toFile()
        val r = SessionExport.build(File(dir, "e.zip"), ExportRequest("b", "ADAPTER-X", "p", "S1", false, redact, 5_000L),
            ExportSources(emptyList(), emptyList(), null, null, snap))
        val m = LinkedHashMap<String, String>()
        ZipInputStream(r.file!!.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return m to r
    }
    @Suppress("UNCHECKED_CAST") private fun json(s: String) = MiniJson.parse(s) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun checks(b: Map<String, Any?>) = (b["checks"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
    private fun saved(vararg r: ValidationRecord) = ValidationCodec.encode(r.toList())
    private fun body(s: ValidationSnapshot?) = json(zip(s).first.getValue(ValidationExport.FILE))

    @Test fun allSixteenChecksAppearIncludingUntested() {
        val b = body(ValidationSnapshot.fromSavedText(saved(ValidationRecord("LIVE_DATA", ValidationStatus.PASSED, 100L))))
        val c = checks(b)
        assertEquals(16, ValidationPlan.items.size); assertEquals(16, c.size)
        ValidationPlan.items.forEach { assertEquals(it.group, c.getValue(it.id)["group"]); assertEquals(it.title, c.getValue(it.id)["title"]) }
        assertEquals("UNTESTED", c.getValue("TRIP_STOP")["status"]); assertNull(c.getValue("TRIP_STOP")["enteredAtMs"])
        assertEquals(true, b["fullyReadable"])
    }

    @Test fun enteredResultTimestampAndNoteSurvive() {
        val b = body(ValidationSnapshot.fromSavedText(saved(
            ValidationRecord("DTC_SCAN", ValidationStatus.FAILED, 1_700_000_000_123L, "slow reply"),
            ValidationRecord("REPORT", ValidationStatus.PASSED, 42L))))
        val c = checks(b)
        assertEquals("FAILED", c.getValue("DTC_SCAN")["status"]); assertEquals(1_700_000_000_123L, c.getValue("DTC_SCAN")["enteredAtMs"]); assertEquals("slow reply", c.getValue("DTC_SCAN")["note"])
        assertEquals("PASSED", c.getValue("REPORT")["status"]); assertEquals(42L, c.getValue("REPORT")["enteredAtMs"]); assertNull(c.getValue("REPORT")["note"])
        assertEquals(true, c.getValue("REPORT")["manuallyEnteredByOwner"])
        assertTrue((b["notice"] as String).contains("NOT independently verified"))
        val n = b["counts"] as Map<*, *>; assertEquals(1L, n["passed"]); assertEquals(1L, n["failed"]); assertEquals(14L, n["untested"])
    }

    @Test fun nothingSavedIsHonestlyAllUntestedAndNothingIsInvented() {
        val (e, r) = zip(ValidationSnapshot.fromSavedText(null))
        val b = json(e.getValue(ValidationExport.FILE))
        assertEquals("NONE_ENTERED", b["dataState"])
        assertTrue(checks(b).values.all { it["status"] == "UNTESTED" && it["enteredAtMs"] == null && it["note"] == null })
        assertFalse((r.manifest["completenessIssues"] as List<*>).any { it.toString().contains(ValidationExport.FILE) })
    }

    @Test fun unavailableOrMissingDataIsReportedAsUnknownAndIncomplete() {
        for (s in listOf(ValidationSnapshot.unavailable("storage error"), null)) {
            val (e, r) = zip(s)
            val b = json(e.getValue(ValidationExport.FILE))
            assertEquals("UNAVAILABLE", b["dataState"]); assertEquals(false, b["fullyReadable"])
            assertTrue(checks(b).values.all { it["status"] == "UNKNOWN" })
            assertFalse(r.complete)
            assertTrue((r.manifest["completenessIssues"] as List<*>).any { it.toString().contains(ValidationExport.FILE) })
        }
    }

    @Test fun malformedSavedLinesDoNotCrashOrBecomeResults() {
        val text = saved(ValidationRecord("LIVE_DATA", ValidationStatus.PASSED, 7L, "ok")) + "\ngarbage\nTRIP_STOP|PASSED|notatime|x\nGONE|PASSED|1|x"
        val (e, r) = zip(ValidationSnapshot.fromSavedText(text))
        val b = json(e.getValue(ValidationExport.FILE)); val c = checks(b)
        assertEquals("PARTIALLY_UNREADABLE", b["dataState"]); assertEquals(3L, b["unreadableSavedLines"])
        assertEquals("PASSED", c.getValue("LIVE_DATA")["status"])
        assertEquals("UNKNOWN", c.getValue("TRIP_STOP")["status"]); assertNull(c.getValue("TRIP_STOP")["enteredAtMs"])
        assertFalse(r.complete)
        val allBad = json(zip(ValidationSnapshot.fromSavedText("garbage\nmore")).first.getValue(ValidationExport.FILE))
        assertEquals("UNREADABLE", allBad["dataState"]); assertTrue(checks(allBad).values.all { it["status"] == "UNKNOWN" })
    }

    @Test fun manifestHashAndBytesCoverTheNewEntry() {
        val (e, r) = zip(ValidationSnapshot.fromSavedText(saved(ValidationRecord("LIVE_DATA", ValidationStatus.PASSED, 7L))))
        val x = (r.manifest["files"] as List<*>).map { it as Map<*, *> }.first { it["name"] == ValidationExport.FILE }
        val body = e.getValue(ValidationExport.FILE)
        assertEquals(body.toByteArray().size.toLong(), x["bytes"])
        assertEquals(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }, x["sha256"])
        assertEquals(true, x["present"]); assertEquals(16L, x["records"])
    }

    @Test fun redactionStillAppliesToNotes() {
        val note = "adapter at AA:BB:CC:DD:EE:FF"
        val s = ValidationSnapshot.fromSavedText(saved(ValidationRecord("LIVE_DATA", ValidationStatus.PASSED, 7L, note)))
        assertTrue(zip(s, false).first.getValue(ValidationExport.FILE).contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(zip(s, true).first.getValue(ValidationExport.FILE).contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun snapshotIsImmutableAndDuplicatesUseTheLastRecord() {
        val s = ValidationSnapshot.fromSavedText("LIVE_DATA|FAILED|1|a\nLIVE_DATA|PASSED|2|b")
        val b = body(s)
        assertEquals("PASSED", checks(b).getValue("LIVE_DATA")["status"]); assertEquals(2L, checks(b).getValue("LIVE_DATA")["enteredAtMs"])
        assertTrue((b["notes"] as List<*>).isNotEmpty())
    }
}

class ValidationPersistenceTest {
    private val bad = "garbage\nTRIP_STOP|PASSED|notatime|x\nGONE|PASSED|1|x"
    private fun updated(saved: String?, id: String, st: ValidationStatus, at: Long, note: String = "") =
        ValidationCodec.saveWith(saved, id, ValidationRecord(id, st, at, note))

    @Test fun validRecordsSurviveAndMalformedLinesStayDetectableAfterAnUpdate() {
        val start = ValidationCodec.encode(listOf(ValidationRecord("LIVE_DATA", ValidationStatus.PASSED, 7L, "ok"), ValidationRecord("REPORT", ValidationStatus.FAILED, 8L))) + "\n" + bad
        val after = updated(start, "DTC_SCAN", ValidationStatus.PASSED, 99L, "n")
        val d = ValidationCodec.decodeDetailed(after)
        assertEquals(3, d.unreadableLines); assertEquals(setOf("TRIP_STOP"), d.unreadableIds)
        assertEquals(listOf(ValidationRecord("LIVE_DATA", ValidationStatus.PASSED, 7L, "ok"), ValidationRecord("REPORT", ValidationStatus.FAILED, 8L), ValidationRecord("DTC_SCAN", ValidationStatus.PASSED, 99L, "n")), d.records)
        assertTrue(bad.lines().all { after.lines().contains(it) })
    }

    @Test fun exportAfterTheUpdateStillReportsUnknownAndIncomplete() {
        val after = updated("LIVE_DATA|PASSED|7|ok\n" + bad, "DTC_SCAN", ValidationStatus.FAILED, 99L)
        val snap = ValidationSnapshot.fromSavedText(after)
        val b = ValidationExport.body(snap)
        @Suppress("UNCHECKED_CAST") val c = (b["checks"] as List<Map<String, Any?>>).associateBy { it["id"] }
        assertEquals("PARTIALLY_UNREADABLE", b["dataState"]); assertEquals("UNKNOWN", c.getValue("TRIP_STOP")["status"])
        assertEquals("FAILED", c.getValue("DTC_SCAN")["status"]); assertEquals("PASSED", c.getValue("LIVE_DATA")["status"])
        assertTrue(ValidationExport.issues(snap).isNotEmpty())
        val dir = Files.createTempDirectory("sx4val2").toFile()
        val r = SessionExport.build(File(dir, "e.zip"), ExportRequest("b", "A", "p", "S1", false, false, 5_000L), ExportSources(emptyList(), emptyList(), null, null, snap))
        assertFalse(r.complete)
    }

    @Test fun aNewResultForADamagedCheckSupersedesTheDamagedLineButKeepsTheEvidence() {
        val after = updated("TRIP_STOP|BROKEN|1|x", "TRIP_STOP", ValidationStatus.PASSED, 5L)
        val d = ValidationCodec.decodeDetailed(after)
        assertEquals(1, d.unreadableLines); assertTrue(d.unreadableIds.isEmpty()); assertEquals(ValidationStatus.PASSED, ValidationPlan.statusOf(d.records, "TRIP_STOP"))
    }

    @Test fun normalUpdatesBehaveAsBefore() {
        var saved: String? = null
        saved = updated(saved, "LIVE_DATA", ValidationStatus.FAILED, 1L, "a | b")
        saved = updated(saved, "LIVE_DATA", ValidationStatus.PASSED, 2L)
        saved = updated(saved, "REPORT", ValidationStatus.PASSED, 3L)
        assertEquals(ValidationCodec.encode(listOf(ValidationRecord("LIVE_DATA", ValidationStatus.PASSED, 2L), ValidationRecord("REPORT", ValidationStatus.PASSED, 3L))), saved)
        assertEquals("REPORT|PASSED|3|", ValidationCodec.saveWith(saved, "LIVE_DATA", null))
        assertEquals("", ValidationCodec.saveWith(null, "LIVE_DATA", null))
        assertTrue(ValidationCodec.decodeDetailed(saved).unreadableLines == 0)
    }
}
