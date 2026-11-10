package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderRegistryTest {
    private fun hex(pid: Int, vararg data: Int) = "41%02X".format(pid) + data.joinToString("") { "%02X".format(it) }
    private fun decoded(raw: String, pid: Int? = null) = DecoderRegistry.decode(raw, pid) as Decoding.Decoded
    private fun invalid(raw: String, expectedPid: Int? = null) = DecoderRegistry.decode(raw, expectedPid) as Decoding.Invalid

    /** The documented formulas, written out again here independently of PidCatalog (from the "formula" column of the public tables). */
    private val expected: Map<Int, (Int, Int) -> Double> = mapOf(
        0x04 to { a, _ -> a * 100.0 / 255 }, 0x05 to { a, _ -> a - 40.0 }, 0x06 to { a, _ -> a * 100.0 / 128 - 100 }, 0x07 to { a, _ -> a * 100.0 / 128 - 100 },
        0x0A to { a, _ -> a * 3.0 }, 0x0B to { a, _ -> a * 1.0 }, 0x0C to { a, b -> (256 * a + b) / 4.0 }, 0x0D to { a, _ -> a * 1.0 },
        0x0E to { a, _ -> a / 2.0 - 64 }, 0x0F to { a, _ -> a - 40.0 }, 0x10 to { a, b -> (256 * a + b) / 100.0 }, 0x11 to { a, _ -> a * 100.0 / 255 },
        0x1F to { a, b -> 256.0 * a + b }, 0x21 to { a, b -> 256.0 * a + b }, 0x22 to { a, b -> 0.079 * (256 * a + b) }, 0x23 to { a, b -> 10.0 * (256 * a + b) },
        0x2C to { a, _ -> a * 100.0 / 255 }, 0x2D to { a, _ -> a * 100.0 / 128 - 100 }, 0x2F to { a, _ -> a * 100.0 / 255 }, 0x30 to { a, _ -> a * 1.0 },
        0x31 to { a, b -> 256.0 * a + b }, 0x33 to { a, _ -> a * 1.0 }, 0x3C to { a, b -> (256 * a + b) / 10.0 - 40 }, 0x42 to { a, b -> (256 * a + b) / 1000.0 },
        0x43 to { a, b -> (256 * a + b) * 100.0 / 255 }, 0x44 to { a, b -> (256 * a + b) * 2.0 / 65536 }, 0x45 to { a, _ -> a * 100.0 / 255 }, 0x46 to { a, _ -> a - 40.0 },
        0x47 to { a, _ -> a * 100.0 / 255 }, 0x49 to { a, _ -> a * 100.0 / 255 }, 0x4A to { a, _ -> a * 100.0 / 255 }, 0x4C to { a, _ -> a * 100.0 / 255 },
        0x4D to { a, b -> 256.0 * a + b }, 0x4E to { a, b -> 256.0 * a + b }, 0x5A to { a, _ -> a * 100.0 / 255 }, 0x5C to { a, _ -> a - 40.0 },
        0x5D to { a, b -> (256 * a + b) / 128.0 - 210 }, 0x5E to { a, b -> (256 * a + b) / 20.0 }, 0x61 to { a, _ -> a - 125.0 }, 0x62 to { a, _ -> a - 125.0 },
        0x63 to { a, b -> 256.0 * a + b }
    )

    // ---- every formula is documented and verified ----
    @Test fun everyCatalogParameterHasADocumentedDecoder() {
        assertEquals(PidCatalog.all.map { it.pid }.toSet(), DecoderRegistry.definitions.map { it.pid }.toSet())
        assertEquals(expected.keys, DecoderRegistry.definitions.map { it.pid }.toSet())
    }

    @Test fun everyDecoderCarriesAFormulaAndSources() {
        for (d in DecoderRegistry.definitions) {
            assertTrue("${d.pid} formula", d.formula.isNotBlank())
            assertTrue("${d.pid} spec reference", d.sources.first().startsWith(DecoderSources.SPEC) && d.sources.first().endsWith("PID %02X".format(d.pid)))
            assertTrue("${d.pid} cross-check", d.sources.drop(1).any { it.startsWith("https://") })
            assertEquals(PidCatalog.forPid(d.pid)!!.bytes, d.bytes)
            assertEquals(PidCatalog.forPid(d.pid)!!.unit, d.unit)
        }
    }

    @Test fun decodedValuesMatchTheDocumentedFormulasAtEverySampleByte() {
        val samples = listOf(0 to 0, 0 to 1, 1 to 0, 0x12 to 0x34, 0x7F to 0xFF, 0x80 to 0x00, 0xFE to 0xFF, 0xFF to 0xFF)
        for (d in DecoderRegistry.definitions) for ((a, b) in samples) {
            val raw = if (d.bytes == 2) hex(d.pid, a, b) else hex(d.pid, a)
            val want = expected.getValue(d.pid)(a, if (d.bytes == 2) b else 0)
            assertEquals("PID %02X A=%d B=%d".format(d.pid, a, b), want, decoded(raw, d.pid).value, 1e-9)
        }
    }

    // ---- normal values and boundaries ----
    @Test fun normalValues() {
        assertEquals(2000.0, decoded("410C1F40").value, 0.0)
        assertEquals("2000", decoded("410C1F40").text)
        assertEquals(42.0, decoded("410D2A").value, 0.0)
        assertEquals(87 - 40.0, decoded("410557").value, 0.0)
        assertEquals("14.37", decoded("41423826").text)   // 0x3826 = 14374 mV
        assertEquals("5.00", decoded("411001F4").text)
    }

    @Test fun boundaries() {
        assertEquals(0.0, decoded("410C0000").value, 0.0)
        assertEquals(16383.75, decoded("410CFFFF").value, 1e-9)
        assertEquals(0.0, decoded("410D00").value, 0.0)             // a real zero is a value
        assertEquals(255.0, decoded("410DFF").value, 0.0)
        assertEquals(-40.0, decoded("410500").value, 0.0)
        assertEquals(215.0, decoded("4105FF").value, 0.0)
        assertEquals(-100.0, decoded("410600").value, 0.0)
        assertEquals(100.0, decoded("4104FF").value, 1e-9)
        assertEquals(-210.0, decoded("415D0000").value, 0.0)
        assertEquals(65.535, decoded("4142FFFF").value, 1e-9)
        assertEquals(655350.0, decoded("4123FFFF").value, 0.0)
        assertEquals(-125.0, decoded("416100").value, 0.0)
    }

    @Test fun integerDecodersAreMarkedInteger() {
        assertTrue(DecoderRegistry.definition(0x0D)!!.integer)
        assertTrue(DecoderRegistry.definition(0x0C)!!.integer)
        assertFalse(DecoderRegistry.definition(0x42)!!.integer)
        assertFalse(DecoderRegistry.definition(0x04)!!.integer)
    }

    // ---- raw preservation ----
    @Test fun theVerbatimReplyIsKeptAlongsideTheValue() {
        val raw = " 41 0C 1F 40 \r"
        val d = decoded(raw, 0x0C)
        assertEquals(raw, d.raw)
        assertEquals(2000.0, d.value, 0.0)
        val withBanner = "SEARCHING...\r410D2A\r"
        assertEquals(withBanner, decoded(withBanner, 0x0D).raw)
        assertEquals(42.0, decoded(withBanner, 0x0D).value, 0.0)
    }

    // ---- unknown or unverified parameters stay RAW ----
    @Test fun parametersWithoutAVerifiedFormulaAreNeverDecoded() {
        val raw = DecoderRegistry.decode("41FE1234") as Decoding.Raw
        assertEquals(0xFE, raw.pid)
        assertEquals("1234", raw.payloadHex)
        assertEquals("41FE1234", raw.raw)
        // 0x02 is reserved, 0x6A..0x6F are not in the verified set: all stay RAW
        for (pid in listOf(0x02, 0x03, 0x1C, 0x6A, 0x7F, 0xA6)) {
            assertFalse("%02X".format(pid), DecoderRegistry.isDecodable(pid))
            assertTrue(DecoderRegistry.decode(hex(pid, 0x12, 0x34)) is Decoding.Raw)
        }
    }

    @Test fun manufacturerServicesAreNotInterpretedAsMode01() {
        for (r in listOf("6101AA", "6201BB22", "5A8012", "490204AABBCCDD")) {
            assertTrue(r, DecoderRegistry.decode(r) is Decoding.Invalid)
        }
        assertFalse(DecoderRegistry.isDecodable(0x100))
    }

    // ---- malformed or invalid ----
    @Test fun malformedRepliesAreInvalidNeverGuessed() {
        assertEquals(InvalidReason.EMPTY, invalid("").reason)
        assertEquals(InvalidReason.EMPTY, invalid(" \r ").reason)
        assertEquals(InvalidReason.NOT_A_MODE01_RESPONSE, invalid("NO DATA").reason)
        assertEquals(InvalidReason.NOT_A_MODE01_RESPONSE, invalid("UNABLE TO CONNECT").reason)
        assertEquals(InvalidReason.NEGATIVE_RESPONSE, invalid("7F0112").reason)
        assertEquals(InvalidReason.WRONG_LENGTH, invalid("410C1F").reason)            // one byte short
        assertEquals(InvalidReason.WRONG_LENGTH, invalid("410C1F4000").reason)        // one byte long
        assertEquals(InvalidReason.WRONG_LENGTH, invalid("410C").reason)
        assertEquals(InvalidReason.WRONG_LENGTH, invalid("410D2A2A").reason)
        assertEquals(InvalidReason.WRONG_LENGTH, invalid("410D").reason)
        assertEquals(InvalidReason.NON_HEX, invalid("410CZZZZ").reason)
        assertEquals(InvalidReason.NON_HEX, invalid("410D2G").reason)
        assertEquals(InvalidReason.NOT_A_MODE01_RESPONSE, invalid("41").reason)
        assertEquals(InvalidReason.NOT_A_MODE01_RESPONSE, invalid("41G0").reason)
        assertEquals(InvalidReason.NOT_A_MODE01_RESPONSE, invalid("XYZ").reason)
        assertEquals(InvalidReason.NOT_A_MODE01_RESPONSE, invalid("010D2A").reason)    // the request echoed back
        assertEquals(InvalidReason.WRONG_LENGTH, invalid("41FE").reason)               // unknown PID with no data at all
    }

    @Test fun aReplyForAnotherPidIsRejectedWhenAPidWasExpected() {
        val r = invalid("410C1F40", expectedPid = 0x0D)
        assertEquals(InvalidReason.PID_MISMATCH, r.reason)
        assertEquals(0x0C, r.pid)
        assertEquals("410C1F40", r.raw)
        assertNotNull(DecoderRegistry.decode("410C1F40") as? Decoding.Decoded)          // without an expectation it decodes as PID 0C
    }

    // ---- same results as the existing PidCatalog path ----
    @Test fun agreesWithPidCatalogOnEveryCatalogPidAndMalformedVariant() {
        for (spec in PidCatalog.all) {
            val good = if (spec.bytes == 2) "A1B2" else "A1"
            val variants = listOf(good, "", good.dropLast(2), good + "00", good.dropLast(1) + "Z", "00".repeat(spec.bytes), "FF".repeat(spec.bytes))
            for (payload in variants) {
                val reply = "41%02X".format(spec.pid) + payload
                val old = PidCatalog.decode(spec, reply)
                val now = DecoderRegistry.decode(reply, spec.pid)
                if (old == null) assertFalse("PID %02X $reply".format(spec.pid), now is Decoding.Decoded)
                else assertEquals("PID %02X $reply".format(spec.pid), old, (now as Decoding.Decoded).value, 0.0)
            }
        }
    }

    @Test fun theParameterRegistryAgreesWhichParametersHaveADecoder() {
        val r = ParameterRegistry.withBuiltInDefinitions()
        for (def in r.all().filter { it.service == "01" }) {
            assertEquals(def.id, DecoderRegistry.isDecodable(def.identifier.toInt(16)), def.hasDecoder)
        }
    }
}
