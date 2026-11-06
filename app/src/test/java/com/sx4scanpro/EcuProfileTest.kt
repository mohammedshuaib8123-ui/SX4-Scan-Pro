package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

private class ScriptPort(val replies: Map<String, String>) : ElmPort {
    val sent = ArrayList<String>()
    override fun send(command: String, timeoutMs: Long): ElmResult = sendLines(command, timeoutMs)
    override fun sendLines(command: String, timeoutMs: Long): ElmResult {
        sent += command
        val raw = replies[command] ?: "NO DATA"
        return ElmResult(command, raw, ElmReply.classify(raw), 1)
    }
}

class EcuProfileTest {
    private val facts = AdapterFacts("Vgate", "ELM327 v1.5", "12.4V", "ISO 14230-4 (KWP FAST)")
    private fun hexOf(s: String) = s.map { "%02X".format(it.code) }.joinToString("")

    private fun scan(replies: Map<String, String>, framing: String = facts.protocol) =
        EcuScan.run(ScriptPort(replies), facts.copy(protocol = framing), setOf(0x0C, 0x05), "t", 1000L)

    private val vinData = "000000" + hexOf("MA3EYB81S00123456")
    private val vinKline = vinData.chunked(8).mapIndexed { i, d -> "4902%02X".format(i + 1) + d }.joinToString("\n")

    @Test fun readsOnlyAdvertisedItems() {
        val port = ScriptPort(mapOf("0900" to "49 00 40 00 00 00", "0902" to vinKline))
        val p = EcuScan.run(port, facts, setOf(0x0C), "t", 1L)
        assertEquals(listOf("MA3EYB81S00123456"), p.vin.items)
        assertEquals(ScanStatus.NOT_ADVERTISED, p.calIds.status)
        assertEquals(listOf("0900", "0902"), port.sent)
    }

    @Test fun noSupportListMeansNothingElseIsRequested() {
        val port = ScriptPort(emptyMap())
        val p = EcuScan.run(port, facts, emptySet(), "t", 1L)
        assertEquals(listOf("0900"), port.sent)
        assertEquals(ScanStatus.NOT_ATTEMPTED, p.vin.status)
        assertTrue(p.notes.isNotEmpty())
    }

    @Test fun onlyReadOnlyServicesAreSent() {
        val port = ScriptPort(mapOf("0900" to "49 00 54 00 00 00"))
        EcuScan.run(port, facts, emptySet(), "t", 1L)
        assertTrue(port.sent.all { it.startsWith("09") })
    }

    @Test fun jsonRoundTrip() {
        val p = scan(mapOf("0900" to "49 00 40 00 00 00", "0902" to vinKline))
        val back = EcuProfile.fromJson(p.toJson())!!
        assertEquals(p, back)
        assertNull(EcuProfile.fromJson("not json"))
        assertNull(EcuProfile.fromJson("{\"schema\": 2}"))
    }

    @Test fun csvHasOneRowPerField() {
        val csv = scan(mapOf("0900" to "49 00 40 00 00 00", "0902" to vinKline)).toCsv()
        assertTrue(csv.lines().first().startsWith("field,status"))
        assertTrue(csv.contains("MA3EYB81S00123456"))
    }

    @Test fun diffDetectsEcuChangeButNotMissingReads() {
        val a = scan(mapOf("0900" to "49 00 40 00 00 00", "0902" to vinKline))
        val same = scan(mapOf("0900" to "49 00 40 00 00 00", "0902" to vinKline))
        assertTrue(ProfileDiff.compare(a, same).unchanged)
        val otherData = "000000" + hexOf("MA3EYB81S00999999")
        val other = otherData.chunked(8).mapIndexed { i, d -> "4902%02X".format(i + 1) + d }.joinToString("\n")
        val changed = scan(mapOf("0900" to "49 00 40 00 00 00", "0902" to other))
        val d = ProfileDiff.compare(a, changed)
        assertTrue(d.identityChanged)
        val missing = scan(emptyMap())
        val d2 = ProfileDiff.compare(a, missing)
        assertFalse(d2.identityChanged)
        assertTrue(d2.entries.all { it.kind == DiffKind.NOT_COMPARABLE })
    }

    @Test fun pidSetChangesAreReported() {
        val a = scan(emptyMap()); val b = a.copy(mode01Supported = listOf(0x0C, 0x05, 0x10))
        val d = ProfileDiff.compare(a, b)
        assertEquals(DiffKind.PIDS_ADDED, d.entries.single().kind)
    }
}

class MiniJsonTest {
    @Test fun roundTripsEscapesAndNesting() {
        val v = linkedMapOf("a" to "q\"\\\n\u0001é", "b" to listOf(1L, 2.5, true, null), "c" to linkedMapOf<String, Any?>())
        assertEquals(v, MiniJson.parse(MiniJson.write(v)))
    }
    @Test fun badInputIsNullNotException() {
        assertNull(MiniJson.parse("{\"a\":"))
        assertNull(MiniJson.parse("[1,2"))
        assertNull(MiniJson.parse("{} trailing"))
    }
}
