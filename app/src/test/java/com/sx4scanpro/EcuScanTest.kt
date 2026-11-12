package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

/** Fake adapter: scripted replies, a record of every command sent, optional timeouts. No vehicle, no real traffic. */
private class FakeScanPort(val replies: Map<String, String>, val timeouts: Set<String> = emptySet()) : ElmPort {
    val sent = ArrayList<String>()
    override fun send(command: String, timeoutMs: Long): ElmResult = sendLines(command, timeoutMs)
    override fun sendLines(command: String, timeoutMs: Long): ElmResult {
        sent += command
        if (command in timeouts) return ElmResult(command, "", ElmKind.EMPTY, timeoutMs, true)
        val raw = replies[command] ?: "NO DATA"
        return ElmResult(command, raw, ElmReply.classify(raw), 5)
    }
}

class EcuScanTest {
    private val facts = AdapterFacts("Vgate", "ELM327 v1.5", "12.4V", "ISO 14230-4 (KWP FAST)")
    private fun hexOf(s: String) = s.map { "%02X".format(it.code) }.joinToString("")
    private fun kline(pid: Int, data: String) = data.chunked(8).mapIndexed { i, d -> "49%02X%02X".format(pid, i + 1) + d }.joinToString("\n")
    private val vin = kline(2, "000000" + hexOf("MA3EYB81S00123456"))
    private val cal = kline(4, hexOf("ABCD1234EF567890"))
    private val cvn = "49 06 01 DE AD BE EF"
    private val name = kline(0x0A, hexOf("ECM-ENGINE") + "0".repeat(0))
    private val allAdvertised = "49 00 54 40 00 00"   // PIDs 02, 04, 06 and 0A

    private fun run(port: FakeScanPort, mode01: Set<Int> = setOf(0x0C)) = EcuScan.run(port, facts, mode01, "t", 1_000L)

    @Test fun successfulScanReadsEveryAdvertisedItem() {
        val port = FakeScanPort(mapOf("0900" to allAdvertised, "0902" to vin, "0904" to cal, "0906" to cvn))
        val p = run(port)
        assertEquals(ScanStatus.READ, p.mode09Supported.status)
        assertEquals(listOf("MA3EYB81S00123456"), p.vin.items)
        assertEquals(listOf("ABCD1234EF567890"), p.calIds.items)
        assertEquals(listOf("DEADBEEF"), p.cvns.items)
        assertEquals(listOf("0900", "0902", "0904", "0906", "090A"), port.sent)
        assertEquals(listOf(0x0C), p.mode01Supported)
    }

    @Test fun onlyReadOnlyMode09RequestsAreSent() {
        val port = FakeScanPort(mapOf("0900" to allAdvertised, "0902" to vin))
        run(port)
        assertTrue(port.sent.all { it.matches(Regex("09[0-9A-F]{2}")) })
    }

    @Test fun itemsTheEcuDoesNotAdvertiseAreNotRequested() {
        val port = FakeScanPort(mapOf("0900" to "49 00 40 00 00 00", "0902" to vin))
        val p = run(port)
        assertEquals(listOf("0900", "0902"), port.sent)
        assertEquals(ScanStatus.NOT_ADVERTISED, p.calIds.status); assertEquals(ScanStatus.NOT_ADVERTISED, p.cvns.status); assertEquals(ScanStatus.NOT_ADVERTISED, p.ecuName.status)
    }

    @Test fun advertisedButMissingFieldIsReportedAsNoDataNotInvented() {
        val port = FakeScanPort(mapOf("0900" to allAdvertised, "0902" to vin))   // 0904/0906/090A fall through to NO DATA
        val p = run(port)
        assertEquals(ScanStatus.READ, p.vin.status)
        for (f in listOf(p.calIds, p.cvns, p.ecuName)) { assertEquals(ScanStatus.NO_DATA, f.status); assertTrue(f.items.isEmpty()) }
    }

    @Test fun unsupportedRequestIsRejectedNotRead() {
        val port = FakeScanPort(mapOf("0900" to "49 00 40 00 00 00", "0902" to "7F 09 12"))
        val p = run(port)
        assertEquals(ScanStatus.REJECTED, p.vin.status); assertTrue(p.vin.items.isEmpty())
    }

    @Test fun malformedReplyIsMalformedWithRawKept() {
        val port = FakeScanPort(mapOf("0900" to "49 00 40 00 00 00", "0902" to "490203" + "00000031" + "\n" + "490201" + "00000032"))
        val p = run(port)
        assertEquals(ScanStatus.MALFORMED, p.vin.status); assertTrue(p.vin.items.isEmpty())
        assertTrue(p.vin.rawHex.isNotBlank() || p.vin.note.isNotBlank())
    }

    @Test fun invalidVinIsNeverPresentedAsAVin() {
        val bad = kline(2, "000000" + hexOf("1234567890123456I"))
        val p = run(FakeScanPort(mapOf("0900" to "49 00 40 00 00 00", "0902" to bad)))
        assertNotEquals(ScanStatus.READ, p.vin.status); assertTrue(p.vin.items.isEmpty())
    }

    @Test fun ecuCommunicationFailureOnTheSupportListStopsTheScan() {
        for (reply in listOf("UNABLE TO CONNECT", "BUS INIT: ...ERROR", "NO DATA")) {
            val port = FakeScanPort(mapOf("0900" to reply))
            val p = run(port)
            assertEquals(listOf("0900"), port.sent)
            assertNotEquals(ScanStatus.READ, p.mode09Supported.status)
            assertEquals(ScanStatus.NOT_ATTEMPTED, p.vin.status)
            assertTrue(p.notes.any { it.contains("Mode 09 support list") })
        }
    }

    @Test fun adapterTimeoutIsAnAdapterErrorNotAFabricatedAnswer() {
        val port = FakeScanPort(emptyMap(), timeouts = setOf("0900"))
        val p = run(port)
        assertEquals(ScanStatus.ADAPTER_ERROR, p.mode09Supported.status)
        assertEquals(ScanStatus.NOT_ATTEMPTED, p.vin.status)
    }

    @Test fun partialScanKeepsWhatWasReadAndMarksTheRest() {
        val port = FakeScanPort(mapOf("0900" to allAdvertised, "0902" to vin, "0906" to cvn), timeouts = setOf("0904"))
        val p = run(port)
        assertEquals(ScanStatus.READ, p.vin.status)
        assertEquals(ScanStatus.ADAPTER_ERROR, p.calIds.status)
        assertEquals(ScanStatus.READ, p.cvns.status)
        assertEquals(ScanStatus.NO_DATA, p.ecuName.status)
        assertEquals(5, port.sent.size)   // one failure does not stop the remaining reads
    }

    @Test fun stepsAreReportedForEveryRequest() {
        val steps = ArrayList<String>()
        EcuScan.run(FakeScanPort(mapOf("0900" to "49 00 40 00 00 00", "0902" to vin)), facts, emptySet(), "t", 1L) { steps += it }
        assertEquals(listOf("Reading 0900", "Reading 0902"), steps)
    }

    @Test fun unknownProtocolIsNotedAndDoesNotInventFraming() {
        val p = EcuScan.run(FakeScanPort(mapOf("0900" to "49 00 40 00 00 00", "0902" to vin)), facts.copy(protocol = ""), emptySet(), "t", 1L)
        assertTrue(p.notes.any { it.contains("Protocol was not reported") })
    }
}
