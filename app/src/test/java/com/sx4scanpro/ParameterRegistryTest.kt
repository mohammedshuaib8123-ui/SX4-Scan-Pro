package com.sx4scanpro

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ParameterRegistryTest {
    private fun reg() = ParameterRegistry.withBuiltInDefinitions()
    private fun ev(k: EvidenceKind, t: Long = 1L, n: String = "") = Evidence(t, k, n)
    private val rpm = "01:0C"
    private fun tmp() = File.createTempFile("reg", "").let { it.delete(); it.mkdirs(); it }

    // 1 + 7: all five states exist; first install is an all-UNTESTED registry built from the app's own definitions
    @Test fun fiveStatesAndFreshRegistry() {
        assertEquals(setOf("UNTESTED", "ADVERTISED", "VERIFIED", "DECODED", "UNSUPPORTED"), ParamState.values().map { it.name }.toSet())
        val r = reg()
        assertTrue(r.all().isNotEmpty())
        assertTrue(r.all().all { r.state(it.id) == ParamState.UNTESTED })
        assertEquals(RegistryLoad(reg(), emptyList()).issues, RegistryStore(File(tmp(), "none.json")).load().issues)
    }

    // 2: the normal path, one step at a time, with evidence and verification time
    @Test fun validTransitionsInOrder() {
        val r = reg()
        assertEquals(ParamState.ADVERTISED, r.onAdvertised(rpm, ev(EvidenceKind.BITMAP_ADVERTISED, 1)).to)
        assertEquals(ParamState.VERIFIED, r.onPositiveResponse(rpm, Evidence(2, EvidenceKind.POSITIVE_RESPONSE, "41 0C ..", rawSeq = 7)).to)
        assertEquals(2L, r.record(rpm)!!.lastVerifiedMs)
        assertEquals(ParamState.DECODED, r.onDecoded(rpm, ev(EvidenceKind.DECODED_OK, 3)).to)
        assertEquals(listOf(EvidenceKind.BITMAP_ADVERTISED, EvidenceKind.POSITIVE_RESPONSE, EvidenceKind.DECODED_OK), r.record(rpm)!!.evidence.map { it.kind })
        assertEquals(7L, r.record(rpm)!!.evidence[1].rawSeq)
    }

    @Test fun responseWithoutAdvertisementStillVerifies() {
        val r = reg()
        assertEquals(ParamState.VERIFIED, r.onPositiveResponse(rpm, ev(EvidenceKind.POSITIVE_RESPONSE)).to)
    }

    // 3: transitions that must not happen
    @Test fun invalidTransitionsAreRejectedAndExplained() {
        val r = reg()
        assertFalse(r.onDecoded(rpm, ev(EvidenceKind.DECODED_OK)).applied)            // never decoded without a response
        assertEquals(ParamState.UNTESTED, r.state(rpm))
        r.onAdvertised(rpm, ev(EvidenceKind.BITMAP_ADVERTISED))
        assertFalse(r.onDecoded(rpm, ev(EvidenceKind.DECODED_OK)).applied)            // advertised is not verified
        assertEquals(ParamState.ADVERTISED, r.state(rpm))
        r.onPositiveResponse(rpm, ev(EvidenceKind.POSITIVE_RESPONSE))
        assertFalse(r.onUnsupported(rpm, ev(EvidenceKind.NEGATIVE_RESPONSE)).applied)  // it answered, so not unsupported
        assertEquals(ParamState.VERIFIED, r.state(rpm))
        assertTrue(r.record(rpm)!!.evidence.last().note.contains("not applied"))
        assertFalse(r.onPositiveResponse("99:99", ev(EvidenceKind.POSITIVE_RESPONSE)).applied) // unknown parameter
    }

    @Test fun unsupportedNeedsExplicitEvidence() {
        val r = reg()
        for (k in listOf(EvidenceKind.NO_DATA, EvidenceKind.TIMEOUT, EvidenceKind.DECODE_FAILED, EvidenceKind.POSITIVE_RESPONSE)) assertFalse(r.onUnsupported(rpm, ev(k)).applied)
        assertEquals(ParamState.UNTESTED, r.state(rpm))
        assertEquals(ParamState.UNSUPPORTED, r.onUnsupported(rpm, ev(EvidenceKind.NEGATIVE_RESPONSE, 5, "7F 01 12")).to)
        val r2 = reg(); r2.onAdvertised("01:0D", ev(EvidenceKind.BITMAP_ADVERTISED))
        assertEquals(ParamState.UNSUPPORTED, r2.onUnsupported("01:0D", ev(EvidenceKind.BITMAP_NOT_ADVERTISED)).to)
    }

    @Test fun failuresAndSilenceNeverChangeState() {
        val r = reg(); r.onPositiveResponse(rpm, ev(EvidenceKind.POSITIVE_RESPONSE))
        r.onDecodeFailed(rpm, ev(EvidenceKind.DECODE_FAILED)); r.onInconclusive(rpm, ev(EvidenceKind.NO_DATA)); r.onInconclusive(rpm, ev(EvidenceKind.TIMEOUT))
        assertEquals(ParamState.VERIFIED, r.state(rpm))
        assertEquals(4, r.record(rpm)!!.evidence.size)
    }

    @Test fun unsupportedParameterCannotBeReadvertisedUntilReset() {
        val r = reg(); r.onUnsupported(rpm, ev(EvidenceKind.NEGATIVE_RESPONSE))
        assertFalse(r.onAdvertised(rpm, ev(EvidenceKind.BITMAP_ADVERTISED)).applied)
        assertTrue(r.reset(rpm, "ECU changed", 9).applied)
        assertEquals(ParamState.UNTESTED, r.state(rpm))
        assertEquals(EvidenceKind.RESET, r.record(rpm)!!.evidence.last().kind)
        assertTrue(r.record(rpm)!!.evidence.size >= 2)  // history kept
    }

    @Test fun evidenceIsBounded() {
        val r = reg(); repeat(60) { r.onInconclusive(rpm, ev(EvidenceKind.NO_DATA, it.toLong())) }
        assertEquals(ParameterRegistry.MAX_EVIDENCE, r.record(rpm)!!.evidence.size)
        assertEquals(59L, r.record(rpm)!!.evidence.last().tMs)
    }

    // 4 + 5: save, reload, version field
    @Test fun persistsAndReloads() {
        val f = File(tmp(), "registry/parameters.json"); val store = RegistryStore(f)
        val r = reg(); r.onAdvertised(rpm, ev(EvidenceKind.BITMAP_ADVERTISED, 1)); r.onPositiveResponse(rpm, Evidence(2, EvidenceKind.POSITIVE_RESPONSE, rawSeq = 4))
        r.onUnsupported("01:0D", ev(EvidenceKind.NEGATIVE_RESPONSE, 3, "7F 01 12"))
        assertTrue(store.save(r, 100))
        val back = RegistryStore(f).load()
        assertTrue(back.issues.isEmpty())
        assertEquals(ParamState.VERIFIED, back.registry.state(rpm)); assertEquals(2L, back.registry.record(rpm)!!.lastVerifiedMs)
        assertEquals(ParamState.UNSUPPORTED, back.registry.state("01:0D"))
        assertEquals(r.record(rpm), back.registry.record(rpm))
        assertEquals(ParamState.UNTESTED, back.registry.state("01:05"))
    }

    @Test fun storedFileCarriesSchemaVersionAndNoReadings() {
        val json = RegistryStore(File(tmp(), "x.json")).toJson(reg().also { it.onPositiveResponse(rpm, ev(EvidenceKind.POSITIVE_RESPONSE, 1, "41 0C 1A F8")) }, 1)
        assertTrue(json.contains("\"schema\": 1"))
        assertFalse(json.contains("\"value\""))
    }

    @Test fun newerSchemaIsNotLoadedAndFileIsKept() {
        val f = File(tmp(), "p.json"); f.writeText("{\"schema\": 99, \"records\": []}")
        val res = RegistryStore(f).load()
        assertTrue(res.issues.single().contains("newer app version"))
        assertTrue(res.registry.all().all { res.registry.state(it.id) == ParamState.UNTESTED })
        assertEquals("{\"schema\": 99, \"records\": []}", f.readText())
    }

    // 6: damaged data
    @Test fun corruptFileGivesDefaultsKeepsACopyAndNeverThrows() {
        for (bad in listOf("", "not json", "{", "[]", "{\"records\": []}", "{\"schema\": \"x\"}")) {
            val f = File(tmp(), "p.json"); f.writeText(bad)
            val res = RegistryStore(f).load()
            assertTrue("issue for [$bad]", res.issues.isNotEmpty())
            assertEquals(reg().all().size, res.registry.all().size)
            assertTrue(File(f.path + ".corrupt").exists())
        }
    }

    @Test fun oneBadRecordDoesNotLoseTheRest() {
        val f = File(tmp(), "p.json"); val store = RegistryStore(f)
        val r = reg(); r.onPositiveResponse("01:0C", ev(EvidenceKind.POSITIVE_RESPONSE, 2)); store.save(r, 1)
        val damaged = f.readText().replace("\"records\": [", "\"records\": [{\"id\":\"01:0D\",\"state\":\"BOGUS\"},{\"nope\":1},")
        f.writeText(damaged)
        val res = RegistryStore(f).load()
        assertEquals(2, res.issues.size)
        assertEquals(ParamState.VERIFIED, res.registry.state("01:0C"))
    }

    @Test fun unwritableLocationReportsFailureWithoutThrowing() {
        val bad = File.createTempFile("notadir", "")
        assertFalse(RegistryStore(File(bad, "sub/p.json")).save(reg(), 1))
    }

    // 8: duplicates
    @Test fun duplicateDefinitionsArePrevented() {
        val r = reg(); val rpmDef = r.def(rpm)!!; val n = r.all().size
        assertTrue(r.register(rpmDef))                                   // identical: harmless
        assertFalse(r.register(rpmDef.copy(name = "Something else")))    // conflicting: refused
        assertFalse(r.register(rpmDef.copy(id = "01:FF")))               // id that does not match service/identifier
        assertEquals(n, r.all().size)
        assertEquals(r.all().size, r.all().map { it.id }.toSet().size)
    }

    // 9: parameters without decoders
    @Test fun parametersWithoutDecodersStayRawAndCannotBecomeDecoded() {
        val free = "%02X".format((0x70..0xFF).first { PidCatalog.forPid(it) == null })
        val r = reg(); val d = r.ensure("01", free)           // not in the catalog: no invented name or decoder
        assertFalse(d.hasDecoder); assertEquals(ParamDataType.UNKNOWN, d.dataType)
        assertFalse(d.name.contains("temperature", ignoreCase = true))
        assertSame(d, r.ensure("01", free.lowercase()))
        r.onAdvertised(d.id, ev(EvidenceKind.BITMAP_ADVERTISED)); r.onPositiveResponse(d.id, ev(EvidenceKind.POSITIVE_RESPONSE))
        assertFalse(r.onDecoded(d.id, ev(EvidenceKind.DECODED_OK)).applied)
        assertEquals(ParamState.VERIFIED, r.state(d.id))
        // and it survives a reload as an ad-hoc definition
        val f = File(tmp(), "p.json"); RegistryStore(f).save(r, 1)
        val back = RegistryStore(f).load().registry
        assertEquals(d, back.def(d.id)); assertEquals(ParamState.VERIFIED, back.state(d.id))
    }

    // 10: the existing catalog is reused untouched
    @Test fun everyPidCatalogEntryIsRegisteredAsItIs() {
        val r = reg()
        PidCatalog.all.forEach { s ->
            val d = r.def("01:%02X".format(s.pid))!!
            assertEquals(s.name, d.name); assertEquals(s.unit, d.unit); assertTrue(d.hasDecoder); assertEquals("PidCatalog", d.source)
        }
        assertEquals(PidCatalog.all.size, r.all().count { it.service == "01" })
        assertNotNull(PidCatalog.forPid(0x0C))   // catalog API unchanged
        listOf("00", "02", "04", "06", "0A").forEach { assertTrue(r.def("09:$it")!!.hasDecoder) }
    }
}
