package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FreezeFrameReaderTest {
    /** Scripted adapter: answers by command; a command mapped to TIMEOUT times out, to FAIL throws. */
    private class FakePort(private val script: Map<String, String>, private val failOn: Set<String> = emptySet()) : ElmPort {
        val asked = mutableListOf<String>()
        override fun send(command: String, timeoutMs: Long): ElmResult {
            asked += command
            if (command in failOn) throw IOException("link dropped")
            val raw = script[command] ?: "NO DATA"
            val timedOut = raw == "TIMEOUT"
            val shown = if (timedOut) "" else raw
            return ElmResult(command, shown, ElmReply.classify(shown), 5, timedOut)
        }
    }

    private fun read(port: FakePort, mode01: Set<Int> = emptySet()) = FreezeFrameReader.read(port, mode01, 1234L)

    // PIDs 02, 04, 05, 0C, 0D -> bits 1,3,4,11,12 -> 58 18 00 00
    private val basic = mapOf(
        "020200" to "42 02 00 01 18",
        "020000" to "42 00 00 58 18 00 00",
        "020400" to "42 04 00 80",
        "020500" to "42 05 00 5A",
        "020C00" to "42 0C 00 1F 40",
        "020D00" to "42 0D 00 00"
    )

    @Test fun happyPathReadsEveryListedValueAndTheCausingDtc() {
        val r = read(FakePort(basic))
        assertEquals("P0118", r.dtc)
        assertEquals(FreezeStatus.VALUE, r.dtcStatus)
        assertEquals(FreezeStatus.VALUE, r.supportStatus)
        assertEquals(setOf(0x02, 0x04, 0x05, 0x0C, 0x0D), r.supportedPids)
        assertEquals(listOf("Engine RPM", "Vehicle speed", "Coolant temperature", "Engine load"), r.items.map { it.name })
        assertEquals("2000", r.items[0].value)
        assertEquals("rpm", r.items[0].unit)
        assertEquals("50", r.items[2].value)
        assertEquals("50.2", r.items[3].value)
        assertTrue(r.items.all { it.status == FreezeStatus.VALUE })
        assertTrue(r.hasData)
        assertEquals(0, r.frameNumber)
        assertEquals(1234L, r.capturedAtMs)
        assertTrue(r.headline().contains("caused by P0118"))
    }

    @Test fun validZeroIsAValueNotMissing() {
        val speed = read(FakePort(basic)).items.first { it.pid == 0x0D }
        assertEquals(FreezeStatus.VALUE, speed.status)
        assertEquals("0", speed.value)
    }

    @Test fun rawRepliesAreKept() {
        val rpm = read(FakePort(basic)).items.first { it.pid == 0x0C }
        assertEquals("42 0C 00 1F 40", rpm.raw)
        assertEquals("42 02 00 01 18", read(FakePort(basic)).dtcRaw)
    }

    @Test fun noDataOnSupportListMeansNoPidRequests() {
        val port = FakePort(mapOf("020200" to "NO DATA", "020000" to "NO DATA"))
        val r = read(port)
        assertEquals(FreezeStatus.NO_DATA, r.supportStatus)
        assertNull(r.supportedPids)
        assertTrue(r.items.isEmpty())
        assertFalse(r.hasData)
        assertEquals(listOf("020200", "020000"), port.asked)
        assertTrue(r.headline().contains("NO DATA"))
        assertTrue(r.notes.any { it.contains("no individual PIDs") })
    }

    @Test fun oneTimeoutDoesNotStopTheOtherValues() {
        val r = read(FakePort(basic + ("020C00" to "TIMEOUT")))
        assertEquals(FreezeStatus.TIMEOUT, r.items.first { it.pid == 0x0C }.status)
        assertEquals(3, r.dataItems.size)
        assertTrue(r.hasData)
    }

    @Test fun wrongLengthIsMalformedNotAWrongNumber() {
        val r = read(FakePort(basic + ("020C00" to "42 0C 00 1F")))
        val rpm = r.items.first { it.pid == 0x0C }
        assertEquals(FreezeStatus.MALFORMED, rpm.status)
        assertEquals("", rpm.value)
    }

    @Test fun negativeResponseIsUnsupported() {
        val r = read(FakePort(basic + ("020500" to "7F 02 12")))
        assertEquals(FreezeStatus.UNSUPPORTED, r.items.first { it.pid == 0x05 }.status)
    }

    @Test fun otherFrameNumberIsRejected() {
        val r = read(FakePort(basic + ("020C00" to "42 0C 01 1F 40")))
        val rpm = r.items.first { it.pid == 0x0C }
        assertEquals(FreezeStatus.MALFORMED, rpm.status)
        assertTrue(rpm.detail.contains("different frame"))
    }

    @Test fun missingValueIsNeverReplacedByZero() {
        val r = read(FakePort(basic + ("020D00" to "NO DATA")))
        val speed = r.items.first { it.pid == 0x0D }
        assertEquals(FreezeStatus.NO_DATA, speed.status)
        assertEquals("", speed.value)
        assertTrue(FreezeFrameText.itemLine(speed).contains("NO DATA"))
        assertFalse(FreezeFrameText.itemLine(speed).contains(": 0"))
    }

    @Test fun pidWithoutDecoderIsShownRawNotAsANumber() {
        // PIDs 01, 02, 04, 05, 0C, 0D -> D8 18 00 00
        val r = read(FakePort(basic + ("020000" to "42 00 00 D8 18 00 00") + ("020100" to "42 01 00 00 07 E5 00")))
        val x = r.items.first { it.pid == 0x01 }
        assertEquals(FreezeStatus.RAW_ONLY, x.status)
        assertEquals("0007E500", x.value)
        assertTrue(FreezeFrameText.itemLine(x).contains("raw"))
    }

    @Test fun causingDtcZeroMeansEcuNamesNoFault() {
        val r = read(FakePort(basic + ("020200" to "42 02 00 00 00")))
        assertEquals(FreezeStatus.VALUE, r.dtcStatus)
        assertNull(r.dtc)
        assertTrue(FreezeFrameText.dtcLine(r).contains("names no fault"))
    }

    @Test fun causingDtcNoDataDoesNotHideTheValues() {
        val r = read(FakePort(basic + ("020200" to "NO DATA")))
        assertEquals(FreezeStatus.NO_DATA, r.dtcStatus)
        assertEquals(4, r.dataItems.size)
        assertTrue(FreezeFrameText.dtcLine(r).contains("No reply"))
    }

    @Test fun malformedCausingDtcIsFlagged() {
        val r = read(FakePort(basic + ("020200" to "42 02 00 01")))
        assertEquals(FreezeStatus.MALFORMED, r.dtcStatus)
        assertNull(r.dtc)
    }

    @Test fun unreadableSupportListFallsBackToMode01AdvertisedPids() {
        val script = basic + ("020000" to "TIMEOUT")
        val r = read(FakePort(script), mode01 = setOf(0x0C, 0x0D, 0x05, 0x04, 0x99))
        assertEquals(FreezeStatus.TIMEOUT, r.supportStatus)
        assertNull(r.supportedPids)
        assertEquals(listOf(0x0C, 0x0D, 0x05, 0x04), r.items.map { it.pid })
        assertTrue(r.notes.any { it.contains("did not report its Mode 02 support list") })
    }

    @Test fun supportBlocksAreChained() {
        // Block 00 lists 02 and 20 (bit 31); block 20 lists 21.
        val script = mapOf(
            "020200" to "42 02 00 00 00",
            "020000" to "42 00 00 40 00 00 01",
            "022000" to "42 20 00 80 00 00 00",
            "022100" to "42 21 00 00 10"
        )
        val port = FakePort(script)
        val r = read(port)
        assertEquals(setOf(0x02, 0x20, 0x21), r.supportedPids)
        val km = r.items.single()
        assertEquals(0x21, km.pid)
        assertEquals("16", km.value)
        assertEquals("km", km.unit)
        assertTrue("022000" in port.asked)
    }

    @Test fun linkFailureStopsFurtherRequestsAndIsReported() {
        val port = FakePort(basic, failOn = setOf("020C00"))
        val r = read(port)
        assertEquals(FreezeStatus.LINK_FAILURE, r.items.first { it.pid == 0x0C }.status)
        assertEquals(FreezeStatus.NOT_ASKED, r.items.first { it.pid == 0x0D }.status)
        assertTrue(r.notes.any { it.contains("link failed") })
        assertFalse("020D00" in port.asked)
    }

    // ---- reply decoding ----

    @Test fun replyToleratesBannersSpacesAndBlankLines() {
        val r = FreezeFrameDecoder.reply("SEARCHING...\r\n42 0C 00 1F 40\r\r", 0x0C)
        assertEquals(FreezeStatus.VALUE, r.status)
        assertEquals("1F40", r.dataHex)
    }

    @Test fun replyRejectsModeOneAnswer() {
        assertEquals(FreezeStatus.MALFORMED, FreezeFrameDecoder.reply("41 0C 1F 40", 0x0C).status)
    }

    @Test fun replyRejectsAnswerForAnotherPid() {
        assertEquals(FreezeStatus.MALFORMED, FreezeFrameDecoder.reply("42 0D 00 40", 0x0C).status)
    }

    @Test fun emptyAndPendingAreNotAnswers() {
        assertEquals(FreezeStatus.MALFORMED, FreezeFrameDecoder.reply("", 0x0C).status)
        assertEquals(FreezeStatus.MALFORMED, FreezeFrameDecoder.reply("7F 02 78", 0x0C).status)
        assertEquals(FreezeStatus.TIMEOUT, FreezeFrameDecoder.reply("", 0x0C, timedOut = true).status)
    }

    @Test fun legacyHelpersAreUnchanged() {
        assertEquals("020C00", FreezeFrame.command(0x0C))
        val rpm = PidCatalog.forPid(0x0C)!!
        assertEquals("2000", FreezeFrame.decode(rpm, "420C001F40")!!.value.substringBefore('.'))
        assertNull(FreezeFrame.decode(rpm, "NO DATA"))
    }

    @Test fun everyStatusHasWording() {
        FreezeStatus.values().forEach { assertTrue(FreezeFrameText.status(it).isNotBlank()) }
    }
}
