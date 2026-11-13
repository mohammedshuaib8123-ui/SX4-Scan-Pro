package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class ModuleCatalogTest {
    private val NOW = 1_700_000_000_000L

    @Test fun everyNonEngineModuleHasEveryCapabilityListedExactlyOnce() {
        for (m in ModuleId.values().filter { it != ModuleId.ENGINE }) {
            val caps = ModuleCatalog.forModule(m).map { it.capability }
            assertEquals("$m", ModuleCapability.values().toList().sorted(), caps.sorted())
        }
        assertTrue(ModuleCatalog.forModule(ModuleId.ENGINE).isEmpty())   // the engine ECU is covered by real evidence, not by documents
    }

    @Test fun onlyDocumentedItemsCarryASourceAndNothingIsPresentedAsConfirmed() {
        assertTrue(ModuleCatalog.entries.all { it.source.isNotBlank() })
        val documented = ModuleCatalog.entries.filter { it.level == KnowledgeLevel.DOCUMENTED_GLOBAL_MANUAL }
        assertTrue(documented.isNotEmpty()); assertTrue(documented.all { it.module == ModuleId.BCM })
        assertTrue(documented.all { it.source.contains("not checked against the Indian 2012 ZDi") })
        assertTrue(ModuleCatalog.entries.none { it.level == KnowledgeLevel.UNVERIFIED && it.source != "no source consulted" && it.module != ModuleId.EPS })
    }

    @Test fun addressAndProtocolAreNeverDocumented() {
        assertTrue(ModuleCatalog.entries.filter { it.capability == ModuleCapability.ADDRESS_AND_PROTOCOL }.all { it.level == KnowledgeLevel.UNVERIFIED })
        assertTrue(ModuleCatalog.entries.filter { it.capability == ModuleCapability.FITMENT }.all { it.level == KnowledgeLevel.UNVERIFIED })
    }

    @Test fun catalogContainsNoAddressesOrCommands() {
        val hex = Regex("\\b(7[0-9A-F]{2}|0x[0-9A-Fa-f]+|AT[A-Z]{1,3}|(?:[0-9A-F]{2} ){2,}[0-9A-F]{2})\\b")
        for (e in ModuleCatalog.entries) { assertFalse(e.source, hex.containsMatchIn(e.source)); assertFalse(hex.containsMatchIn(ModuleCatalog.summary(e.module))) }
    }

    @Test fun documentationNeverRaisesAModuleStatus() {
        val m = ModuleStatusModel.derive(null, null, ParameterRegistry.withBuiltInDefinitions(), null)
        assertTrue(m.filter { it.id != ModuleId.ENGINE }.all { it.status == ModuleStatus.NOT_SCANNED && it.address == null })
        val bcm = m.first { it.id == ModuleId.BCM }
        assertTrue(bcm.note.contains("not confirmed for this car")); assertTrue(bcm.note.contains("Needs vehicle confirmation")); assertTrue(bcm.note.contains("Address and protocol are unknown"))
    }

    @Test fun everyModuleIsListedAndOthersStayNotScannedEvenWithFullEngineEvidence() {
        val reg = ParameterRegistry.withBuiltInDefinitions(); val id = reg.ensure("01", "0C").id
        reg.onAdvertised(id, Evidence(NOW, EvidenceKind.BITMAP_ADVERTISED)); reg.onPositiveResponse(id, Evidence(NOW, EvidenceKind.POSITIVE_RESPONSE)); reg.onDecoded(id, Evidence(NOW, EvidenceKind.DECODED_OK))
        val prof = EcuProfile(NOW, "v", "a", "i", "12V", "ISO 14230-4", Framing.KLINE, listOf(0x0C), ProfileField(ScanStatus.READ, listOf("02")),
            ProfileField(ScanStatus.READ, listOf("MA3EYB81S00123456")), ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED)
        val m = ModuleStatusModel.derive(null, prof, reg, NOW - 1)
        assertEquals(ModuleId.values().toList(), m.map { it.id })
        assertEquals(ModuleStatus.VERIFIED, m.first().status)
        assertTrue(m.drop(1).all { it.status == ModuleStatus.NOT_SCANNED })
        assertTrue(m.none { it.status == ModuleStatus.UNAVAILABLE })
        assertTrue(m.map { it.id }.containsAll(listOf(ModuleId.BCM, ModuleId.EPS, ModuleId.ABS, ModuleId.CLUSTER, ModuleId.AIRBAG)))
    }

    @Test fun p8HistoricalSafeguardStillHolds() {
        val reg = ParameterRegistry.withBuiltInDefinitions(); val id = reg.ensure("01", "0C").id
        reg.onAdvertised(id, Evidence(NOW, EvidenceKind.BITMAP_ADVERTISED)); reg.onPositiveResponse(id, Evidence(NOW, EvidenceKind.POSITIVE_RESPONSE)); reg.onDecoded(id, Evidence(NOW, EvidenceKind.DECODED_OK))
        val later = ModuleStatusModel.derive(null, null, reg, NOW + 10_000_000L).first()
        assertEquals(ModuleStatus.NOT_SCANNED, later.status); assertEquals(EvidenceScope.HISTORICAL, later.scope)
    }

    @Test fun moduleLabelsUseTheFullNames() {
        assertEquals("ABS/ESP", ModuleId.ABS.label); assertEquals("SRS / Airbag", ModuleId.AIRBAG.label); assertEquals("Instrument cluster", ModuleId.CLUSTER.label)
    }
}
