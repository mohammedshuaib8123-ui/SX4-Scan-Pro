package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class Mode09DecoderTest {
    private fun hexOf(s: String) = s.map { "%02X".format(it.code) }.joinToString("")

    @Test fun canVinMultiFrame() {
        val vin = "MA3EYB81S00123456"
        val h = "490201" + hexOf(vin)            // 20 bytes
        val first = h.substring(0, 12)
        val rest = h.substring(12).chunked(14)
        val raw = "014\n0:$first\n" + rest.mapIndexed { i, s -> "${i + 1}:$s" }.joinToString("\n")
        val v = Mode09Decoder.decode(2, raw, Framing.CAN)
        assertEquals(ScanStatus.READ, v.status)
        assertEquals(listOf(vin), v.items)
    }

    @Test fun klineVinFiveLines() {
        val vin = "MA3EYB81S00123456"
        val data = "000000" + hexOf(vin)         // 20 bytes -> 5 lines of 4
        val raw = data.chunked(8).mapIndexed { i, d -> "490202".replace("0202", "02%02X".format(i + 1)) + d }.joinToString("\r")
        val v = Mode09Decoder.decode(2, raw, Framing.KLINE)
        assertEquals(ScanStatus.READ, v.status)
        assertEquals(listOf(vin), v.items)
    }

    @Test fun invalidVinIsMalformedNotInvented() {
        val raw = "490201" + hexOf("1234567890123456I")
        val v = Mode09Decoder.decode(2, "014\n0:" + raw.take(12) + "\n1:" + raw.substring(12, 26) + "\n2:" + raw.substring(26), Framing.CAN)
        assertEquals(ScanStatus.MALFORMED, v.status)
        assertTrue(v.items.isEmpty())
    }

    @Test fun noDataAndRejected() {
        assertEquals(ScanStatus.NO_DATA, Mode09Decoder.decode(2, "NO DATA", Framing.CAN).status)
        assertEquals(ScanStatus.REJECTED, Mode09Decoder.decode(2, "7F 09 11", Framing.CAN).status)
        assertEquals(ScanStatus.REJECTED, Mode09Decoder.decode(2, "?", Framing.KLINE).status)
        assertEquals(ScanStatus.ADAPTER_ERROR, Mode09Decoder.decode(2, "UNABLE TO CONNECT", Framing.KLINE).status)
    }

    @Test fun supportBitmap() {
        val v = Mode09Decoder.decode(0, "49 00 54 00 00 00", Framing.KLINE)
        assertEquals(listOf("02", "04", "06"), v.items)
    }

    @Test fun calIdAndCvnKline() {
        val cal = hexOf("ABCD1234EF567890")
        val data = cal
        val raw = data.chunked(8).mapIndexed { i, d -> "4904%02X".format(i + 1) + d }.joinToString("\r")
        assertEquals(listOf("ABCD1234EF567890"), Mode09Decoder.decode(4, raw, Framing.KLINE).items)
        val cvn = "49 06 01 DE AD BE EF"
        assertEquals(listOf("DEADBEEF"), Mode09Decoder.decode(6, cvn, Framing.KLINE).items)
    }

    @Test fun outOfOrderLinesAreMalformed() {
        val raw = "490203" + "00000031" + "\r" + "490201" + "00000032"
        assertEquals(ScanStatus.MALFORMED, Mode09Decoder.decode(2, raw, Framing.KLINE).status)
    }

    @Test fun framingFromProtocolText() {
        assertEquals(Framing.CAN, Framing.fromProtocolText("ISO 15765-4 (CAN 11/500)"))
        assertEquals(Framing.KLINE, Framing.fromProtocolText("AUTO, ISO 14230-4 (KWP 5BAUD)"))
        assertEquals(Framing.UNKNOWN, Framing.fromProtocolText(""))
    }
}

class CanDtcTest {
    @Test fun singleFrameWithCountByte() {
        val r = ObdTroubleCodeDecoder.decodeResponse("43 02 01 18 02 34 00", "03", Framing.CAN)
        assertEquals(listOf("P0118", "P0234"), r.codes)
        assertEquals(1, r.validFrames)
    }
    @Test fun multiFrame() {
        val r = ObdTroubleCodeDecoder.decodeResponse("00A\n0:4304011802\n1:3406710500", "03", Framing.CAN)
        assertEquals(listOf("P0118", "P0234", "P0671", "P0500"), r.codes)
    }
    @Test fun cleanAndNoData() {
        assertEquals(1, ObdTroubleCodeDecoder.decodeResponse("43 00 00 00 00 00 00", "03", Framing.CAN).validFrames)
        assertTrue(ObdTroubleCodeDecoder.decodeResponse("NO DATA", "03", Framing.CAN).noData)
    }
    @Test fun countLargerThanDataIsUnreadable() {
        val r = ObdTroubleCodeDecoder.decodeResponse("43 05 01 18", "03", Framing.CAN)
        assertEquals(0, r.validFrames)
        assertFalse(r.unreadable.isEmpty())
    }
    @Test fun klineDefaultUnchanged() {
        assertEquals(listOf("P0118", "P0234"), ObdTroubleCodeDecoder.decodeResponse("43 01 18 02 34 00 00", "03").codes)
    }
}
