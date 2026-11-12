package com.sx4scanpro

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

private const val VIN = "MA3EYD81S00123456"
private const val BT = "VGATE iCar Pro 4C"

private fun tmp(): File = Files.createTempDirectory("sx4export").toFile()

private fun profile(withVin: Boolean = true) = EcuProfile(
    capturedAtMs = 1_000L, appVersion = "v", adapterName = BT, adapterId = "ELM327 v2.1", adapterVoltage = "12.4V", protocol = "ISO 14230-4",
    framing = Framing.KLINE, mode01Supported = listOf(0x0C, 0x0D),
    mode09Supported = ProfileField(ScanStatus.READ, listOf("02", "04")),
    vin = if (withVin) ProfileField(ScanStatus.READ, listOf(VIN), "49 02 01 4D 41 33 45 59 44 38 31 53 30 30 31 32 33 34 35 36") else ProfileField.NOT_ATTEMPTED,
    calIds = ProfileField.NOT_ATTEMPTED, cvns = ProfileField.NOT_ATTEMPTED, ecuName = ProfileField.NOT_ATTEMPTED, notes = listOf("scanned via $BT")
)

private class Fixture(val dir: File = tmp()) {
    val sessionLog = SessionLog(File(dir, "logs"))
    val capture = CaptureStore(File(dir, "capture"))
    var registry: ParameterRegistry? = ParameterRegistry.withBuiltInDefinitions()
    var profile: EcuProfile? = profile()

    fun populate() {
        sessionLog.append("EVT", "connection DISCONNECTED -> CONNECTING", 1_000)
        sessionLog.append("TX", "010C", 1_010); sessionLog.append("RX", "41 0C 1A F8", 1_020)
        sessionLog.append("TX", "0902", 1_030); sessionLog.append("RX", "49 02 01 4D 41 33 45 59 44 38 31 53 30 30 31 32 33 34 35 36", 1_040)
        sessionLog.append("EVT", "adapter $BT ready, vin $VIN", 1_050)
        sessionLog.append("EVT", "connection READY -> DISCONNECTED", 2_000)
        capture.record("S1", 1_010, "010C", "41 0C 1A F8", "DATA", 12, "ISO 14230-4")
        capture.record("S1", 1_030, "0902", "49 02 01 4D 41 33 45 59 44 38 31 53 30 30 31 32 33 34 35 36", "DATA", 40, "ISO 14230-4", error = "seen on $BT")
    }
    fun request(redact: Boolean, live: Boolean = false) = ExportRequest("build X", BT, "ISO 14230-4", "S1", live, redact, 5_000L)
    fun sources() = ExportSources(sessionLog.files(), capture.files(), registry, profile)
    fun build(redact: Boolean, live: Boolean = false, max: Long = SessionExport.DEFAULT_MAX_SOURCE_BYTES, dest: File = File(dir, "out/e.zip")) =
        SessionExport.build(dest, request(redact, live), sources(), max)
}

private fun entries(f: File): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    ZipInputStream(f.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
    return m
}
private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
@Suppress("UNCHECKED_CAST") private fun manifest(f: File) = MiniJson.parse(entries(f).getValue("manifest.json")) as Map<String, Any?>

class SessionExportTest {
    @Test fun containsAllComponentsAndAManifest() {
        val fx = Fixture().also { it.populate() }
        val r = fx.build(false)
        assertNull(r.error); assertNotNull(r.file)
        val e = entries(r.file!!)
        assertEquals(setOf("session_events.log", "capture.jsonl", "registry_summary.json", "ecu_profile.json", "manifest.json"), e.keys)
        val m = manifest(r.file!!)
        assertEquals(SessionExport.FORMAT, m["format"]); assertEquals(1L, m["formatVersion"])
        assertEquals("build X", m["build"]); assertEquals(5_000L, m["exportedAtMs"])
        assertEquals("ISO 14230-4", (m["adapter"] as Map<*, *>)["protocol"]); assertEquals(BT, (m["adapter"] as Map<*, *>)["name"])
        val s = m["session"] as Map<*, *>
        assertEquals("S1", s["currentId"]); assertEquals(1_010L, s["startMs"]); assertEquals(1_030L, s["endMs"])
    }

    @Test fun manifestHashesAndSizesMatchTheEntries() {
        val fx = Fixture().also { it.populate() }
        val f = fx.build(false).file!!
        val e = entries(f)
        for (x in manifest(f)["files"] as List<*>) {
            x as Map<*, *>
            val body = e.getValue(x["name"] as String)
            assertEquals(sha(body), x["sha256"]); assertEquals(body.toByteArray().size.toLong(), x["bytes"])
        }
    }

    @Test fun structuredCaptureIsIncludedUnchangedWhenNotRedacted() {
        val fx = Fixture().also { it.populate() }
        val e = entries(fx.build(false).file!!)
        val lines = e.getValue("capture.jsonl").trim().lines()
        assertEquals(2, lines.size)
        assertEquals(fx.capture.readAll().size, lines.size)
        val parsed = lines.map { RawExchange.fromMap(MiniJson.parse(it) as Map<*, *>)!! }
        assertEquals(listOf(1_010L, 1_030L), parsed.map { it.tMs })
        assertTrue(parsed[0].rawText == "41 0C 1A F8")
        assertEquals(fx.sessionLog.readAll().trim().lines().size, e.getValue("session_events.log").trim().lines().size)
    }

    @Test fun registryAndProfileAreIncluded() {
        val fx = Fixture().also { it.populate() }
        val e = entries(fx.build(false).file!!)
        val reg = MiniJson.parse(e.getValue("registry_summary.json")) as Map<*, *>
        assertEquals(true, reg["available"]); assertTrue((reg["parameters"] as List<*>).isNotEmpty()); assertNotNull(reg["counts"])
        val prof = MiniJson.parse(e.getValue("ecu_profile.json")) as Map<*, *>
        assertEquals("READ", (prof["vin"] as Map<*, *>)["status"]); assertTrue(e.getValue("ecu_profile.json").contains(VIN))
    }

    @Test fun redactionMasksVinEverywhere() {
        val fx = Fixture().also { it.populate() }
        val f = fx.build(true).file!!
        for ((name, body) in entries(f)) assertFalse("$name still has the VIN", body.contains(VIN))
        val e = entries(f)
        assertTrue(e.getValue("ecu_profile.json").contains("MA3**************"))
        assertTrue(e.getValue("session_events.log").contains(Redactor.VIN_REPLY_HIDDEN))
        assertTrue(e.getValue("capture.jsonl").contains(Redactor.VIN_REPLY_HIDDEN))
        assertFalse(e.getValue("capture.jsonl").contains("49 02 01 4D"))
        assertFalse(e.getValue("ecu_profile.json").contains("49 02 01 4D"))
        // ordinary readings survive redaction
        assertTrue(e.getValue("capture.jsonl").contains("41 0C 1A F8"))
        assertTrue(e.getValue("session_events.log").contains("41 0C 1A F8"))
    }

    @Test fun redactionHidesAdapterNameEverywhere() {
        val fx = Fixture().also { it.populate() }
        val f = fx.build(true).file!!
        for ((name, body) in entries(f)) assertFalse("$name still has the adapter name", body.contains(BT))
        assertEquals(Redactor.ADAPTER_HIDDEN, ((manifest(f)["adapter"]) as Map<*, *>)["name"])
        assertEquals(true, (manifest(f)["redaction"] as Map<*, *>)["enabled"])
    }

    @Test fun unredactedExportSaysSoAndKeepsEverything() {
        val fx = Fixture().also { it.populate() }
        val f = fx.build(false).file!!
        val e = entries(f)
        assertTrue(e.getValue("session_events.log").contains(VIN)); assertTrue(e.getValue("capture.jsonl").contains(BT))
        assertEquals(false, (manifest(f)["redaction"] as Map<*, *>)["enabled"])
        assertTrue(((manifest(f)["redaction"] as Map<*, *>)["note"] as String).startsWith("NOT redacted"))
    }

    @Test fun storedDataIsNeverModifiedByRedaction() {
        val fx = Fixture().also { it.populate() }
        val logBefore = fx.sessionLog.readAll(); val capBefore = fx.capture.readAll(); val prof = fx.profile!!.toJson()
        fx.build(true)
        assertEquals(logBefore, fx.sessionLog.readAll()); assertEquals(capBefore, fx.capture.readAll()); assertEquals(prof, fx.profile!!.toJson())
        assertTrue(logBefore.contains(VIN))
    }

    @Test fun macAddressesAndUnknownVinsAreMaskedToo() {
        val r = Redactor(true)
        assertEquals("[mac hidden]", r.text("00:1D:A5:68:98:8B"))
        assertEquals("WDB**************", r.text("WDB1234567A123456"))
        assertEquals("41 0C 1A F8", r.text("41 0C 1A F8"))
    }

    @Test fun missingRegistryAndProfileAreReportedNotHidden() {
        val fx = Fixture().also { it.populate(); it.registry = null; it.profile = null }
        val r = fx.build(false)
        val e = entries(r.file!!)
        assertTrue(e.getValue("registry_summary.json").contains("\"available\": false"))
        assertTrue(e.getValue("ecu_profile.json").contains("\"available\": false"))
        assertFalse(r.complete)
        val issues = r.manifest["completenessIssues"] as List<*>
        assertTrue(issues.any { it.toString().contains("registry") }); assertTrue(issues.any { it.toString().contains("ECU profile") })
    }

    @Test fun emptyCaptureAndLogStillExportAndAreNotCalledComplete() {
        val fx = Fixture()
        val r = fx.build(false)
        assertNull(r.error)
        val e = entries(r.file!!)
        assertEquals("", e.getValue("capture.jsonl")); assertEquals("", e.getValue("session_events.log"))
        assertFalse(r.complete)
        assertTrue((r.manifest["completenessIssues"] as List<*>).any { it.toString().contains("no structured capture") })
        assertNull((r.manifest["session"] as Map<*, *>)["startMs"])
    }

    @Test fun completeOnlyWhenEverythingIsPresentAndTheSessionEnded() {
        val fx = Fixture().also { it.populate() }
        val r = fx.build(false)
        assertTrue((r.manifest["completenessIssues"] as List<*>).toString(), r.complete)
        assertEquals("DISCONNECTED", (r.manifest["session"] as Map<*, *>)["ending"])
    }

    @Test fun endingStatusesAreIdentifiedAndNeverGuessed() {
        val live = Fixture().also { it.populate() }.build(false, live = true)
        assertEquals("INCOMPLETE", (live.manifest["session"] as Map<*, *>)["ending"]); assertFalse(live.complete)
        val closed = Fixture().also { it.populate(); it.sessionLog.append("EVT", "app closed", 3_000) }.build(false)
        assertEquals("APP_CLOSED", (closed.manifest["session"] as Map<*, *>)["ending"])
        val err = Fixture().also { it.populate(); it.sessionLog.append("EVT", "connection RECOVERING -> ERROR: x", 3_000) }.build(false)
        assertEquals("INCOMPLETE", (err.manifest["session"] as Map<*, *>)["ending"]); assertFalse(err.complete)
        val unknown = Fixture().also { it.sessionLog.append("TX", "010C", 1); it.capture.record("S1", 1, "010C", "x", "DATA", 1, "p") }.build(false)
        assertEquals("UNKNOWN", (unknown.manifest["session"] as Map<*, *>)["ending"]); assertFalse(unknown.complete)
    }

    @Test fun dropMidSessionIsExportableAndMarkedIncomplete() {
        val fx = Fixture()
        fx.sessionLog.append("EVT", "connection READY -> RECOVERING: Bluetooth link to the adapter was lost", 1_000)
        fx.capture.record("S1", 900, "010C", "41 0C 0A 00", "DATA", 10, "p")
        val r = fx.build(false, live = true)
        assertNotNull(r.file); assertFalse(r.complete)
        assertEquals(1, entries(r.file!!).getValue("capture.jsonl").trim().lines().size)
    }

    @Test fun unwritableDestinationFailsCleanly() {
        val fx = Fixture().also { it.populate() }
        val blocker = File(fx.dir, "blocker").also { it.writeText("a file, not a directory") }
        val r = fx.build(false, dest = File(blocker, "sub/e.zip"))
        assertNull(r.file); assertNotNull(r.error); assertFalse(r.complete)
        assertTrue(fx.capture.readAll().isNotEmpty())   // sources untouched
    }

    @Test fun partialFileIsNotLeftBehindOnFailure() {
        val fx = Fixture().also { it.populate() }
        val dest = File(fx.dir, "out/e.zip")
        val ok = fx.build(false, dest = dest); assertNotNull(ok.file)
        assertFalse(File(fx.dir, "out/e.zip.part").exists())
    }

    @Test fun exportIsBoundedAndSaysWhenOlderDataWasLeftOut() {
        val fx = Fixture()
        val log = SessionLog(File(fx.dir, "logs2"), maxBytes = 400L, keep = 4)
        repeat(60) { log.append("TX", "010C request number $it", it.toLong()) }
        val capture = CaptureStore(File(fx.dir, "cap2"), maxBytes = 400L, keep = 4)
        repeat(30) { capture.record("S1", it.toLong(), "010C", "41 0C 1A F8", "DATA", 1, "p") }
        val src = ExportSources(log.files(), capture.files(), null, null)
        val r = SessionExport.build(File(fx.dir, "o/e.zip"), fx.request(false), src, maxSourceBytes = 700L)
        val m = r.manifest["files"] as List<*>
        val logEntry = m.first { (it as Map<*, *>)["name"] == "session_events.log" } as Map<*, *>
        assertEquals(true, logEntry["truncated"]); assertTrue((logEntry["olderFilesLeftOut"] as Long) >= 1)
        assertFalse(r.complete)
        assertTrue((r.manifest["completenessIssues"] as List<*>).any { it.toString().contains("bounded") })
        val kept = entries(r.file!!).getValue("session_events.log").trim().lines()
        assertTrue(kept.size in 1 until 60); assertTrue(kept.last().contains("request number 59"))   // newest data kept
    }

    @Test fun uncappedExportKeepsEveryRecord() {
        val fx = Fixture()
        repeat(200) { fx.capture.record("S1", it.toLong(), "010C", "41 0C 1A F8", "DATA", 1, "p") }
        val r = fx.build(false)
        assertEquals(fx.capture.readAll().size, entries(r.file!!).getValue("capture.jsonl").trim().lines().size)
    }

    @Test fun logAndCaptureFileListsAreOldestFirstAndOnlyExisting() {
        val fx = Fixture()
        assertTrue(fx.sessionLog.files().isEmpty() && fx.capture.files().isEmpty())
        fx.populate()
        assertEquals(1, fx.sessionLog.files().size); assertEquals(1, fx.capture.files().size)
    }
}
