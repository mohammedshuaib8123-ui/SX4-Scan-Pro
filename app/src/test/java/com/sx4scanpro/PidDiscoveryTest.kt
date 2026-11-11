package com.sx4scanpro

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PidDiscoveryTest {
    /** Scripted adapter. With headers on, every reply gets a K-line header and checksum in front/behind it. */
    private class FakeEcu(
        val replies: MutableMap<String, String> = mutableMapOf("0100" to "4100983B0011", "0120" to "4120A0102001", "0140" to "4140C0C00000"),
        val header: String = "486B10",
        var stuckHeaders: Boolean = false,
        var throwOnce0100: Boolean = false
    ) {
        var headersOn = false
        val sent = mutableListOf<String>()
        fun send(cmd: String, timeoutMs: Long): ElmResult {
            sent += cmd
            if (cmd == "0100" && throwOnce0100) { throwOnce0100 = false; throw IOException("socket closed") }
            val raw = when {
                cmd == "ATH1" -> { headersOn = true; "OK" }
                cmd == "ATH0" -> { if (!stuckHeaders) headersOn = false; "OK" }
                cmd.startsWith("ATCRA") || cmd.startsWith("ATSH") -> "OK"
                else -> replies[cmd]?.let { if (headersOn) header + it + "5C" else it } ?: "NO DATA"
            }
            return ElmResult(cmd, raw, ElmReply.classify(raw), 1)
        }
    }

    private val carPids = setOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x1C, 0x20, 0x21, 0x23, 0x2C, 0x33, 0x40, 0x41, 0x42, 0x49, 0x4A)

    @Test fun decodesTheRealCarBitmapsAndStopsWhereTheEcuStops() {
        val ecu = FakeEcu()
        val run = PidDiscovery(ecu::send, Framing.KLINE).run(captureAddress = false)
        assertEquals(carPids, run.advertised)
        assertEquals(listOf(0x00, 0x20, 0x40), run.reads.map { it.base })
        assertTrue(run.complete)
        assertEquals(listOf("0100", "0120", "0140"), ecu.sent)
    }

    @Test fun chainsPastBlock40WhenAdvertised() {
        val ecu = FakeEcu()
        ecu.replies["0140"] = "4140C0C00001"
        ecu.replies["0160"] = "416080000000"
        val run = PidDiscovery(ecu::send, Framing.KLINE).run(captureAddress = false)
        assertTrue(0x61 in run.advertised)
        assertTrue(0x60 in run.advertised)
        assertEquals(listOf("0100", "0120", "0140", "0160"), ecu.sent)
        assertTrue(run.complete)
    }

    @Test fun neverAsksBeyondTheLastBlock() {
        val ecu = FakeEcu()
        for (b in 0..0xE0 step 0x20) ecu.replies["01%02X".format(b)] = "41%02XFFFFFFFF".format(b)
        val run = PidDiscovery(ecu::send, Framing.KLINE).run(captureAddress = false)
        assertEquals(8, run.reads.size)
        assertEquals("01E0", ecu.sent.last())
        assertEquals(1, ecu.sent.count { it == "0100" })
    }

    @Test fun aFailedBlockEndsTheWalkAndMakesTheRunPartial() {
        val ecu = FakeEcu()
        ecu.replies.remove("0120")
        val run = PidDiscovery(ecu::send, Framing.KLINE).run(captureAddress = false)
        assertEquals(BlockKind.NO_DATA, run.reads[1].kind)
        assertFalse(run.complete)
        assertEquals(listOf("0100", "0120"), ecu.sent)
        assertTrue(0x0C in run.advertised)
    }

    @Test fun failedBlocksAreClassifiedNotInterpretedAsUnsupported() {
        fun kind(raw: String, timedOut: Boolean = false) =
            Mode01Blocks.classify(0x20, ElmResult("0120", raw, ElmReply.classify(raw), 5, timedOut)).kind
        assertEquals(BlockKind.NO_DATA, kind("NO DATA"))
        assertEquals(BlockKind.TIMEOUT, kind("", timedOut = true))
        assertEquals(BlockKind.TIMEOUT, kind(""))
        assertEquals(BlockKind.PARSE_ERROR, kind("4120ZZZZZZZZ"))
        assertEquals(BlockKind.PARSE_ERROR, kind("4120AB"))
        assertEquals(BlockKind.NEGATIVE, kind("7F0112"))
        assertEquals(BlockKind.ADAPTER_ERROR, kind("UNABLE TO CONNECT"))
        assertEquals(BlockKind.OK, kind("4120A0102001"))
        assertEquals(EvidenceKind.DECODE_FAILED, Mode01Blocks.evidenceKind(BlockKind.PARSE_ERROR))
        assertEquals(EvidenceKind.TIMEOUT, Mode01Blocks.evidenceKind(BlockKind.TIMEOUT))
        assertEquals(EvidenceKind.NO_DATA, Mode01Blocks.evidenceKind(BlockKind.NO_DATA))
    }

    @Test fun headersAreCapturedThenRestoredAndVerifiedWithoutZOrSh() {
        val ecu = FakeEcu()
        val run = PidDiscovery(ecu::send, Framing.KLINE).run()
        assertEquals(listOf("ATH1", "0100", "ATH0", "0100", "0120", "0140"), ecu.sent)
        assertEquals("486B10", run.ecuHeader)
        assertEquals("10", run.ecuAddress)
        assertEquals(HeaderRestore(restored = true, usedReinit = false), run.headerRestore)
        assertFalse(ecu.headersOn)
        assertEquals(carPids, run.advertised)
        assertTrue(ecu.sent.none { it.startsWith("ATZ") || it.startsWith("ATSH") })
    }

    @Test fun canFramingAlsoClearsTheReceiveFilter() {
        val ecu = FakeEcu(header = "7E806")
        val run = PidDiscovery(ecu::send, Framing.CAN).run()
        assertEquals(listOf("ATH1", "0100", "ATH0", "ATCRA", "0100"), ecu.sent.take(5))
        assertEquals("7E8", run.ecuAddress)
    }

    @Test fun headersAreRestoredEvenWhenTheReadThrows() {
        val ecu = FakeEcu(throwOnce0100 = true)
        try { PidDiscovery(ecu::send, Framing.KLINE).run(); fail("expected IOException") } catch (_: IOException) { }
        assertTrue("ATH0 must still be sent", "ATH0" in ecu.sent)
        assertFalse(ecu.headersOn)
    }

    @Test fun stuckHeadersAreReportedAndNeverFixedWithAtz() {
        val ecu = FakeEcu(stuckHeaders = true)
        var reinits = 0
        val run = PidDiscovery(ecu::send, Framing.KLINE, reinit = { reinits++ }).run()
        assertFalse(run.headerRestore!!.restored)
        assertEquals(0, reinits)                       // no custom header was applied, so no re-init
        assertFalse(run.complete)
        assertEquals(1, run.reads.size)                // the walk stops instead of decoding header-polluted replies
        assertTrue(ecu.sent.none { it.startsWith("ATZ") })
    }

    @Test fun reinitFallbackRunsOnlyWhenACustomHeaderWasApplied() {
        val ecu = FakeEcu(stuckHeaders = true)
        var reinits = 0
        val headers = AdapterHeaders(ecu::send, Framing.KLINE, reinit = { reinits++; ecu.stuckHeaders = false; ecu.headersOn = false })
        assertTrue(headers.applyCustomHeader("686AF1"))
        ecu.headersOn = true
        val r = headers.restore()
        assertEquals(1, reinits)
        assertTrue(r.restored && r.usedReinit)
        assertFalse(headers.customHeaderApplied)
    }

    @Test fun everyCommandSentIsReadOnlyAndSerialized() {
        val ecu = FakeEcu()
        PidDiscovery(ecu::send, Framing.CAN).run()
        ecu.sent.forEach { assertEquals("should be allowed: $it", GuardVerdict.Allowed, ReadOnlyGuard.check(it)) }
    }

    @Test fun theHeaderWindowRunsInsideTheExclusiveSection() {
        val ecu = FakeEcu()
        var inside = false
        val seenInside = mutableListOf<String>()
        val wrapped: (String, Long) -> ElmResult = { c, t -> if (inside) seenInside += c; ecu.send(c, t) }
        PidDiscovery(wrapped, Framing.KLINE, exclusive = { inside = true; try { it() } finally { inside = false } }).run()
        assertEquals(listOf("ATH1", "0100", "ATH0", "0100"), seenInside)   // 0120/0140 run after the section is released
    }

    @Test fun addressDerivation() {
        assertNull(PidDiscovery.ecuAddressOf(null))
        assertEquals("10", PidDiscovery.ecuAddressOf("486B10"))
        assertEquals("7E8", PidDiscovery.ecuAddressOf("7E806"))
        assertEquals("10", PidDiscovery.ecuAddressOf("18DAF11006"))   // 29-bit id + PCI byte: source address is the last id byte
    }

    @Test fun supportDecoderNowChainsThroughEveryBlock() {
        val text = "4100FFFFFFFF4120FFFFFFFF4140FFFFFFFF4160FFFFFFFF4180000000014" + "1A0FFFFFFFF"
        val s = ObdPidSupport.decode(text)
        assertTrue(0x80 in s && 0xA0 in s && 0xA1 in s)
        assertFalse(0xC1 in s)
    }
}
