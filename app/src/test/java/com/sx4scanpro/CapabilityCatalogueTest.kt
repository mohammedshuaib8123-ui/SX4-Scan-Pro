package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

private const val T0 = 1_700_000_000_000L
private const val SINCE0 = T0 - 1_000L
private fun e(k: EvidenceKind, t: Long = T0) = Evidence(t, k)
private fun prof(vin: ProfileField = ProfileField.NOT_ATTEMPTED, cal: ProfileField = ProfileField.NOT_ATTEMPTED, cvn: ProfileField = ProfileField.NOT_ATTEMPTED, at: Long = T0) =
    EcuProfile(at, "v", "a", "id", "12V", "ISO 14230-4", Framing.KLINE, emptyList(), ProfileField.NOT_ATTEMPTED, vin, cal, cvn, ProfileField.NOT_ATTEMPTED)

class CapabilityCatalogueTest {
    private fun reg() = ParameterRegistry.withBuiltInDefinitions()
    private fun cap(r: ParameterRegistry, id: String, since: Long? = SINCE0) = CapabilityCatalogue.capability(r, r.def(id)!!, since)

    // ---- documentation never promotes
    @Test fun documentedButUntestedStaysUnverified() {
        val r = reg(); val c = cap(r, "01:0C")
        assertTrue(c.documented); assertEquals(ObservedLevel.UNVERIFIED, c.observed); assertEquals(EvidenceScope.NONE, c.scope)
    }
    @Test fun noParameterIsAboveUnverifiedOnAFreshRegistry() {
        val s = CapabilityCatalogue.summarize(reg(), null, null)
        assertTrue(s.capabilities.all { it.observed == ObservedLevel.UNVERIFIED })
        assertEquals(s.documentedCount, s.documentedButUnverified)
        assertEquals(s.capabilities.size, s.counts[ObservedLevel.UNVERIFIED])
    }
    @Test fun unknownParameterIsUndocumentedNotInvented() {
        val r = reg(); val d = r.ensure("01", "FE")
        val c = CapabilityCatalogue.capability(r, d, null)
        assertFalse(c.documented); assertEquals("ecu-advertised", c.documentationSource); assertEquals(ObservedLevel.UNVERIFIED, c.observed)
    }
    @Test fun noDefinitionIsInventedForCapabilitiesTheAppNeverRegistered() {
        val s = CapabilityCatalogue.summarize(reg(), null, null)
        assertTrue(s.capabilities.none { it.id.startsWith("22:") || it.id.startsWith("19:") || it.id.startsWith("21:") })
        assertTrue(s.capabilities.all { it.id.startsWith("01:") || it.id.startsWith("09:") })
    }

    // ---- observed classification
    @Test fun advertisedIsNotTested() {
        val r = reg(); r.onAdvertised("01:0C", e(EvidenceKind.BITMAP_ADVERTISED))
        assertEquals(ObservedLevel.ADVERTISED, cap(r, "01:0C").observed)
    }
    @Test fun testedThenDecoded() {
        val r = reg(); r.onAdvertised("01:0C", e(EvidenceKind.BITMAP_ADVERTISED)); r.onPositiveResponse("01:0C", e(EvidenceKind.POSITIVE_RESPONSE))
        assertEquals(ObservedLevel.TESTED, cap(r, "01:0C").observed)
        r.onDecoded("01:0C", e(EvidenceKind.DECODED_OK))
        assertEquals(ObservedLevel.DECODED, cap(r, "01:0C").observed)
    }
    @Test fun noDataAfterAdvertisedIsUnavailableNotUnsupported() {
        val r = reg(); r.onAdvertised("01:0D", e(EvidenceKind.BITMAP_ADVERTISED)); r.onInconclusive("01:0D", e(EvidenceKind.NO_DATA, T0 + 1))
        assertEquals(ObservedLevel.UNAVAILABLE, cap(r, "01:0D").observed)
    }
    @Test fun timeoutOnUntestedIsUnavailableNotUnsupported() {
        val r = reg(); r.onInconclusive("01:05", e(EvidenceKind.TIMEOUT))
        assertEquals(ObservedLevel.UNAVAILABLE, cap(r, "01:05").observed)
    }
    private fun decoded(r: ParameterRegistry, id: String = "01:0C") { r.onPositiveResponse(id, e(EvidenceKind.POSITIVE_RESPONSE)); r.onDecoded(id, e(EvidenceKind.DECODED_OK)) }
    @Test fun decodedThenNoDataIsUnavailableButRegistryKeepsDecoded() {
        val r = reg(); decoded(r); r.onInconclusive("01:0C", e(EvidenceKind.NO_DATA, T0 + 1))
        assertEquals(ObservedLevel.UNAVAILABLE, cap(r, "01:0C").observed)
        assertEquals(ParamState.DECODED, r.state("01:0C"))
        assertTrue(r.record("01:0C")!!.evidence.any { it.kind == EvidenceKind.DECODED_OK })
        assertNotNull(r.record("01:0C")!!.lastVerifiedMs)
    }
    @Test fun decodedThenTimeoutIsUnavailable() {
        val r = reg(); decoded(r); r.onInconclusive("01:0C", e(EvidenceKind.TIMEOUT, T0 + 1))
        assertEquals(ObservedLevel.UNAVAILABLE, cap(r, "01:0C").observed); assertEquals(ParamState.DECODED, r.state("01:0C"))
    }
    @Test fun verifiedThenNoDataOrTimeoutIsUnavailable() {
        val r = reg()
        r.onPositiveResponse("01:0C", e(EvidenceKind.POSITIVE_RESPONSE)); r.onInconclusive("01:0C", e(EvidenceKind.NO_DATA, T0 + 1))
        r.onPositiveResponse("01:0D", e(EvidenceKind.POSITIVE_RESPONSE)); r.onInconclusive("01:0D", e(EvidenceKind.TIMEOUT, T0 + 1))
        assertEquals(ObservedLevel.UNAVAILABLE, cap(r, "01:0C").observed); assertEquals(ObservedLevel.UNAVAILABLE, cap(r, "01:0D").observed)
        assertEquals(ParamState.VERIFIED, r.state("01:0C")); assertEquals(ParamState.VERIFIED, r.state("01:0D"))
    }
    @Test fun laterSuccessRestoresDecodedAndTested() {
        val r = reg(); decoded(r); r.onInconclusive("01:0C", e(EvidenceKind.NO_DATA, T0 + 1))
        r.onPositiveResponse("01:0C", e(EvidenceKind.POSITIVE_RESPONSE, T0 + 2)); r.onDecoded("01:0C", e(EvidenceKind.DECODED_OK, T0 + 3))
        assertEquals(ObservedLevel.DECODED, cap(r, "01:0C").observed)
        r.onPositiveResponse("01:0D", e(EvidenceKind.POSITIVE_RESPONSE)); r.onInconclusive("01:0D", e(EvidenceKind.TIMEOUT, T0 + 1))
        r.onPositiveResponse("01:0D", e(EvidenceKind.POSITIVE_RESPONSE, T0 + 2))
        assertEquals(ObservedLevel.TESTED, cap(r, "01:0D").observed)
    }
    @Test fun unavailableFromEarlierSessionStaysHistorical() {
        val r = reg(); decoded(r); r.onInconclusive("01:0C", e(EvidenceKind.NO_DATA, T0 + 1))
        assertEquals(EvidenceScope.CURRENT, cap(r, "01:0C", SINCE0).scope)
        assertEquals(EvidenceScope.HISTORICAL, cap(r, "01:0C", T0 + 5_000_000L).scope)
        assertEquals(EvidenceScope.HISTORICAL, cap(r, "01:0C", null).scope)
        r.onPositiveResponse("01:0D", e(EvidenceKind.POSITIVE_RESPONSE))   // old success must not look current in a new connection
        assertEquals(EvidenceScope.HISTORICAL, cap(r, "01:0D", T0 + 5_000_000L).scope)
    }
    @Test fun unsupportedNeedsExplicitEvidence() {
        val r = reg(); r.onUnsupported("01:05", e(EvidenceKind.NO_DATA))
        assertEquals(ObservedLevel.UNAVAILABLE, cap(r, "01:05").observed)   // refused as non-support; kept only as a failed request
        r.onUnsupported("01:05", e(EvidenceKind.NEGATIVE_RESPONSE))
        assertEquals(ObservedLevel.UNSUPPORTED, cap(r, "01:05").observed)
    }
    @Test fun decodeFailureDoesNotMakeItUnsupportedOrDecoded() {
        val r = reg(); r.onPositiveResponse("01:0C", e(EvidenceKind.POSITIVE_RESPONSE)); r.onDecodeFailed("01:0C", e(EvidenceKind.DECODE_FAILED, T0 + 1))
        assertEquals(ObservedLevel.TESTED, cap(r, "01:0C").observed)
    }

    // ---- P8 historical safeguard preserved
    @Test fun evidenceFromEarlierSessionIsHistoricalNotCurrent() {
        val r = reg(); r.onPositiveResponse("01:0C", e(EvidenceKind.POSITIVE_RESPONSE))
        assertEquals(EvidenceScope.CURRENT, cap(r, "01:0C", SINCE0).scope)
        assertEquals(EvidenceScope.HISTORICAL, cap(r, "01:0C", T0 + 5_000_000L).scope)
        assertEquals(EvidenceScope.HISTORICAL, cap(r, "01:0C", null).scope)
    }
    @Test fun p8ModuleStatusStillNeverVerifiedAcrossSessions() {
        val r = reg(); r.onPositiveResponse("01:0C", e(EvidenceKind.POSITIVE_RESPONSE)); r.onDecoded("01:0C", e(EvidenceKind.DECODED_OK))
        val p = prof(vin = ProfileField(ScanStatus.READ, listOf("MA3EYB81S00123456")), at = T0 + 20_000_000L)
        val st = ModuleStatusModel.derive(null, p, r, T0 + 10_000_000L).first { it.id == ModuleId.ENGINE }.status
        assertNotEquals(ModuleStatus.VERIFIED, st)
    }

    // ---- identity evidence
    @Test fun noProfileMeansNotAttemptedNeverAvailable() {
        val i = CapabilityCatalogue.identityEvidence(null, SINCE0)
        assertEquals(3, i.size)
        assertTrue(i.all { it.status == ScanStatus.NOT_ATTEMPTED && !it.available && it.scope == EvidenceScope.NONE && it.scannedAtMs == null })
    }
    @Test fun readVinIsAvailableWithTimestampAndScope() {
        val p = prof(vin = ProfileField(ScanStatus.READ, listOf("MA3EYB81S00123456"), rawHex = "4902"))
        val v = CapabilityCatalogue.identityEvidence(p, SINCE0).first()
        assertTrue(v.available); assertTrue(v.rawCaptured); assertEquals(T0, v.scannedAtMs); assertEquals(EvidenceScope.CURRENT, v.scope); assertEquals(1, v.valueCount)
        assertEquals(EvidenceScope.HISTORICAL, CapabilityCatalogue.identityEvidence(p, null).first().scope)
    }
    @Test fun malformedMissingAndRejectedIdentityIsNeverAvailable() {
        val p = prof(ProfileField(ScanStatus.MALFORMED, rawHex = "49"), ProfileField(ScanStatus.NO_DATA), ProfileField(ScanStatus.REJECTED))
        val i = CapabilityCatalogue.identityEvidence(p, SINCE0)
        assertEquals(listOf(ScanStatus.MALFORMED, ScanStatus.NO_DATA, ScanStatus.REJECTED), i.map { it.status })
        assertTrue(i.none { it.available })
    }
    @Test fun readStatusWithNoItemsIsNotAvailable() {
        assertFalse(CapabilityCatalogue.identityEvidence(prof(vin = ProfileField(ScanStatus.READ)), SINCE0).first().available)
    }
    @Test fun decoderParsesVinAndRejectsMissingOrMalformed() {
        val vin = "MA3EYB81S00123456"
        val data = "000000" + vin.map { "%02X".format(it.code) }.joinToString("")
        val raw = data.chunked(8).mapIndexed { i, d -> "4902%02X".format(i + 1) + d }.joinToString("\r")
        assertEquals(listOf(vin), Mode09Decoder.decode(2, raw, Framing.KLINE).items)
        for (pid in intArrayOf(2, 4, 6)) {
            assertEquals(ScanStatus.NO_DATA, Mode09Decoder.decode(pid, "NO DATA", Framing.KLINE).status)
            assertTrue(Mode09Decoder.decode(pid, "", Framing.KLINE).items.isEmpty())
            assertTrue(Mode09Decoder.decode(pid, "49 zz", Framing.KLINE).items.isEmpty())
        }
    }

    // ---- view wiring
    @Test fun explorerViewCarriesCatalogueAndStaysEvidenceOnly() {
        val v = EcuExplorerView.build(reg(), null, null, "", null)
        assertNotNull(v.capabilities)
        assertEquals(0, (v.capabilities!!.counts[ObservedLevel.DECODED] ?: 0))
        assertEquals(0, v.params.size)
    }

    // ---- ReadOnlyGuard preserved
    @Test fun guardStillBlocksWriteResetClearAndActuatorServices() {
        for (c in listOf("04", "10 03", "11 01", "14 FF FF FF", "2E F1 90 00", "2F 01 02 03", "3B 00", "31 01 02", "27 01", "ATPP FF SV 00"))
            assertTrue("should be blocked: $c", ReadOnlyGuard.check(c) is GuardVerdict.Blocked)
        assertEquals(GuardVerdict.Allowed, ReadOnlyGuard.check("0100"))
        assertEquals(GuardVerdict.Allowed, ReadOnlyGuard.check("0902"))
    }
}
