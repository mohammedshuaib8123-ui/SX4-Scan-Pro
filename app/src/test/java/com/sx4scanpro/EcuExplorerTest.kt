package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

private fun ev(kind: EvidenceKind, t: Long = 1_700_000_000_000L, note: String = "", seq: Long? = null) = Evidence(t, kind, note, seq)

private fun outcome(address: String? = "10", status: DiscoveryStatus = DiscoveryStatus.COMPLETE, advertised: Boolean = false) =
    DiscoveryOutcome(status, "msg", DiscoveryRun(if (advertised) listOf(BlockRead(0x00, BlockKind.OK, 0x00080000L, "4100", ElmKind.DATA)) else emptyList(), "486B10", address, null, status == DiscoveryStatus.COMPLETE))

private fun profile(vin: Boolean = false) = EcuProfile(
    1L, "v", "a", "id", "12V", "ISO 14230-4", Framing.KLINE, listOf(0x0C),
    ProfileField(ScanStatus.READ, listOf("02")), if (vin) ProfileField(ScanStatus.READ, listOf("MA3EYB81S00123456")) else ProfileField.NOT_ATTEMPTED,
    ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED
)

class ModuleStatusModelTest {
    private fun status(d: DiscoveryOutcome?, p: EcuProfile?, r: ParameterRegistry?) = ModuleStatusModel.derive(d, p, r).first { it.id == ModuleId.ENGINE }.status

    @Test fun nothingScannedMeansEveryModuleIsNotScanned() {
        val m = ModuleStatusModel.derive(null, null, ParameterRegistry.withBuiltInDefinitions())
        assertEquals(ModuleId.values().toList(), m.map { it.id })
        assertTrue(m.all { it.status == ModuleStatus.NOT_SCANNED })
    }

    @Test fun otherModulesStayNotScannedWhateverTheEngineDid() {
        val reg = ParameterRegistry.withBuiltInDefinitions(); val d = reg.ensure("01", "0C")
        reg.onAdvertised(d.id, ev(EvidenceKind.BITMAP_ADVERTISED)); reg.onPositiveResponse(d.id, ev(EvidenceKind.POSITIVE_RESPONSE)); reg.onDecoded(d.id, ev(EvidenceKind.DECODED_OK))
        val m = ModuleStatusModel.derive(outcome(), profile(vin = true), reg)
        assertEquals(ModuleStatus.VERIFIED, m.first { it.id == ModuleId.ENGINE }.status)
        for (id in listOf(ModuleId.BCM, ModuleId.EPS, ModuleId.ABS, ModuleId.AIRBAG)) {
            val e = m.first { it.id == id }
            assertEquals(ModuleStatus.NOT_SCANNED, e.status); assertNull(e.address)
            assertFalse(e.note.contains("unavailable", ignoreCase = true) || e.note.contains("absent", ignoreCase = true) && !e.note.contains("may or may not"))
        }
    }

    @Test fun unavailableIsNeverProduced() {
        val reg = ParameterRegistry.withBuiltInDefinitions(); val d = reg.ensure("01", "0C"); reg.onUnsupported(d.id, ev(EvidenceKind.BITMAP_NOT_ADVERTISED))
        for (disc in listOf(null, outcome(), outcome(status = DiscoveryStatus.FAILED), outcome(status = DiscoveryStatus.REFUSED)))
            assertTrue(ModuleStatusModel.derive(disc, null, reg).none { it.status == ModuleStatus.UNAVAILABLE })
    }

    @Test fun engineStatusFollowsTheEvidenceLadder() {
        assertEquals(ModuleStatus.NOT_SCANNED, status(null, null, ParameterRegistry.withBuiltInDefinitions()))
        assertEquals(ModuleStatus.NOT_SCANNED, status(outcome(status = DiscoveryStatus.REFUSED), null, ParameterRegistry.withBuiltInDefinitions()))
        assertEquals(ModuleStatus.ATTEMPTED, status(outcome(status = DiscoveryStatus.FAILED), null, ParameterRegistry.withBuiltInDefinitions()))
        val reg = ParameterRegistry.withBuiltInDefinitions(); val id = reg.ensure("01", "0C").id
        reg.onAdvertised(id, ev(EvidenceKind.BITMAP_ADVERTISED))
        assertEquals(ModuleStatus.ADVERTISED, status(null, null, reg))
        reg.onPositiveResponse(id, ev(EvidenceKind.POSITIVE_RESPONSE))
        assertEquals(ModuleStatus.REACHABLE, status(null, null, reg))
        reg.onDecoded(id, ev(EvidenceKind.DECODED_OK))
        assertEquals(ModuleStatus.DECODED, status(null, profile(vin = false).copy(mode09Supported = ProfileField.NOT_ATTEMPTED), reg))
        assertEquals(ModuleStatus.VERIFIED, status(null, profile(vin = true), reg))
    }

    @Test fun addressIsOnlyShownWhenCapturedAndNeverGuessed() {
        assertEquals("10", ModuleStatusModel.derive(outcome("10"), null, null).first().address)
        assertNull(ModuleStatusModel.derive(outcome(null), null, null).first().address)
        assertNull(ModuleStatusModel.derive(null, null, null).first().address)
    }

    @Test fun engineNoteDoesNotClaimTheEcuIsTheEngineEcu() {
        val reg = ParameterRegistry.withBuiltInDefinitions(); reg.onAdvertised(reg.ensure("01", "0C").id, ev(EvidenceKind.BITMAP_ADVERTISED))
        assertTrue(ModuleStatusModel.derive(null, null, reg).first().note.contains("assumed, not verified"))
    }
}

class EcuExplorerViewTest {
    private fun reg(): ParameterRegistry {
        val r = ParameterRegistry.withBuiltInDefinitions()
        val a = r.ensure("01", "0C").id; val b = r.ensure("01", "0D").id; val c = r.ensure("01", "05").id; val d = r.ensure("01", "42").id; val e = r.ensure("01", "10").id
        r.onAdvertised(a, ev(EvidenceKind.BITMAP_ADVERTISED)); r.onPositiveResponse(a, ev(EvidenceKind.POSITIVE_RESPONSE, seq = 7)); r.onDecoded(a, ev(EvidenceKind.DECODED_OK, note = "ok"))
        r.onAdvertised(b, ev(EvidenceKind.BITMAP_ADVERTISED)); r.onPositiveResponse(b, ev(EvidenceKind.POSITIVE_RESPONSE))
        r.onAdvertised(c, ev(EvidenceKind.BITMAP_ADVERTISED))
        r.onUnsupported(d, ev(EvidenceKind.BITMAP_NOT_ADVERTISED))
        r.onAdvertised(e, ev(EvidenceKind.BITMAP_ADVERTISED)); r.onUnsupported(e, ev(EvidenceKind.NEGATIVE_RESPONSE, note = "7F"))
        return r
    }

    @Test fun showsAllFiveStatesWithCounts() {
        val v = EcuExplorerView.build(reg(), null, null, "")
        assertEquals(ParamState.values().toSet(), v.counts.keys)
        assertEquals(1, v.counts[ParamState.DECODED]); assertEquals(1, v.counts[ParamState.VERIFIED]); assertEquals(1, v.counts[ParamState.ADVERTISED])
        assertEquals(2, v.counts[ParamState.UNSUPPORTED]); assertTrue(v.counts.getValue(ParamState.UNTESTED) > 0)
    }

    @Test fun distinguishesAdvertisedRequestedDecodedAndUnsupported() {
        val rows = EcuExplorerView.build(reg(), null, null, "").params.associateBy { it.id }
        val decoded = rows.getValue("01:0C"); val verified = rows.getValue("01:0D"); val adv = rows.getValue("01:05"); val uns = rows.getValue("01:42")
        assertTrue(decoded.advertised && decoded.requested && decoded.decoded && !decoded.unsupported)
        assertTrue(verified.advertised && verified.requested && !verified.decoded)
        assertTrue(adv.advertised && !adv.requested && !adv.decoded)
        assertTrue(uns.unsupported && !uns.requested && !uns.decoded)
        assertEquals("Reply decoded successfully", decoded.meaning); assertEquals("Requested successfully; reply not decoded", verified.meaning)
        assertEquals("ECU advertised it; not requested successfully yet", adv.meaning)
        assertEquals("Unsupported: the ECU did not advertise it", uns.meaning)
        assertEquals("Unsupported: the ECU rejected the request", rows.getValue("01:10").meaning)
    }

    @Test fun evidenceIsShownWithKindNoteAndCaptureReference() {
        val ev = EcuExplorerView.build(reg(), null, null, "").params.first { it.id == "01:0C" }.evidence
        assertEquals(3, ev.size)
        assertTrue(ev.any { it.contains("ECU advertised it") }); assertTrue(ev.any { it.contains("request answered") && it.contains("[capture #7]") })
        assertTrue(ev.any { it.contains("reply decoded") && it.contains("(ok)") })
    }

    @Test fun untestedParametersAreNotListedAndOrderingIsStrongestFirst() {
        val v = EcuExplorerView.build(reg(), null, null, "")
        assertTrue(v.params.none { it.state == ParamState.UNTESTED })
        assertEquals(listOf(ParamState.DECODED, ParamState.VERIFIED, ParamState.ADVERTISED), v.params.take(3).map { it.state })
    }

    @Test fun rowCapIsReportedNotSilent() {
        val r = ParameterRegistry.withBuiltInDefinitions()
        for (i in 0..0x7F) r.onAdvertised(r.ensure("01", "%02X".format(i)).id, ev(EvidenceKind.BITMAP_ADVERTISED))
        val v = EcuExplorerView.build(r, null, null, "")
        assertEquals(EcuExplorerView.MAX_ROWS, v.params.size); assertTrue(v.hidden > 0)
    }

    @Test fun identityRowsShowOnlyWhatWasCaptured() {
        val none = EcuExplorerView.build(null, null, null, "").identity.toMap()
        assertEquals("not captured yet", none["ECU address (as captured)"]); assertEquals("not reported", none["Protocol"])
        assertEquals("not run in this session", none["Mode 01 discovery"]); assertEquals("not run", none["Identity scan"])
        val some = EcuExplorerView.build(null, outcome("10"), profile(), "ISO 14230-4 (KWP FAST)").identity.toMap()
        assertEquals("10", some["ECU address (as captured)"]); assertEquals("ISO 14230-4 (KWP FAST)", some["Protocol"])
        assertTrue(some.getValue("Mode 01 discovery").startsWith("complete"))
    }

    @Test fun missingRegistryIsHandled() {
        val v = EcuExplorerView.build(null, null, null, "")
        assertTrue(v.params.isEmpty()); assertEquals(0, v.hidden); assertTrue(v.counts.values.all { it == 0 })
    }

    @Test fun buildingTheViewDoesNotChangeTheRegistry() {
        val r = reg(); val before = r.records()
        EcuExplorerView.build(r, outcome(), profile(), "p")
        assertEquals(before, r.records())
    }
}
