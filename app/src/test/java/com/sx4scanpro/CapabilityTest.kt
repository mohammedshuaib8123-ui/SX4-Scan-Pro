package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class CapabilityTest {
    @Test fun readinessDecodesMilCountAndDieselMonitors() {
        // Constructed to exercise the bit layout (not a captured reply): MIL on, 2 codes, diesel; continuous misfire+components supported, misfire incomplete;
        // C: NMHC(0)+boost(3)+EGR(7) supported; D: boost incomplete.
        val r = ReadinessDecoder.decode("41 01 82 1D 89 08")!!
        assertTrue(r.milOn); assertEquals(2, r.storedDtcCount); assertTrue(r.compressionIgnition)
        assertEquals(listOf("Misfire", "Comprehensive components", "NMHC catalyst", "Boost pressure", "EGR / VVT"), r.monitors.map { it.name })
        assertEquals(listOf("Boost pressure"), r.incomplete.filter { it == "Boost pressure" })
    }
    @Test fun incompleteBitsAreReadPerMonitor() {
        val r = ReadinessDecoder.decode("41 01 00 17 00 00")!!   // spark; B=0x17: misfire/fuel/components supported, misfire bit4 = incomplete
        assertFalse(r.monitors.first { it.name == "Misfire" }.complete)
    }
    @Test fun malformedReadinessIsNull() {
        assertNull(ReadinessDecoder.decode("NO DATA")); assertNull(ReadinessDecoder.decode("4101 00 07")); assertNull(ReadinessDecoder.decode("7F0111"))
    }
    @Test fun mode06ProbeStates() {
        val ok = Mode06Probe.classify("46 00 C0 00 00 01")
        assertEquals(Mode06Outcome.SUPPORTED, ok.outcome); assertEquals(setOf(1, 2, 32), ok.ids)
        assertEquals(Mode06Outcome.NOT_SUPPORTED, Mode06Probe.classify("7F 06 11").outcome)
        assertEquals(Mode06Outcome.NO_DATA, Mode06Probe.classify("NO DATA").outcome)
        assertEquals(Mode06Outcome.UNREADABLE, Mode06Probe.classify("41 00 BE").outcome)
    }
    @Test fun readOnlyGuardAllowsTheTwoProbesAndNothingMore() {
        assertEquals(GuardVerdict.Allowed, ReadOnlyGuard.check("0101")); assertEquals(GuardVerdict.Allowed, ReadOnlyGuard.check("0600"))
        assertTrue(ReadOnlyGuard.check("0601AA00") is GuardVerdict.Blocked)
    }
    @Test fun inventoryNeverClaimsWhatWasNotRead() {
        val none = CapabilityInventory.lines(EcuProfile(0L, "t", "a", "b", "c", "ISO 14230-4", Framing.KLINE, listOf(0x0C), ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED, ProfileField.NOT_ATTEMPTED), null).joinToString("\n")
        assertTrue(none.contains("not read in this session"))
    }
}
