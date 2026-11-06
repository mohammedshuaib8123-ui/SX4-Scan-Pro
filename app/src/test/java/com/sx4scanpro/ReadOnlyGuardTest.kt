package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class ReadOnlyGuardTest {
    private fun ok(c: String, x: ExtendedAllowlist = ExtendedAllowlist.EMPTY) = assertEquals("should be allowed: $c", GuardVerdict.Allowed, ReadOnlyGuard.check(c, x))
    private fun no(c: String, x: ExtendedAllowlist = ExtendedAllowlist.EMPTY) = assertTrue("should be blocked: $c", ReadOnlyGuard.check(c, x) is GuardVerdict.Blocked)

    @Test fun everyCommandTheAppAlreadySendsIsAllowed() {
        // Initialisation for every protocol, with and without the optional timeout, exactly as ConnectionConfig builds it.
        ok("ATZ")
        for (p in ObdProtocol.values()) for (t in listOf(null, 1, 0x20, 255))
            ConnectionConfig(protocol = p, timeoutByte = t).initCommands().forEach { ok(it) }
        listOf("ATI", "ATRV", "ATDP", "ATPC", "ATSP0", "ATSP3", "ATSP4", "ATSP5", "ATSP6", "ATSPA", "ATSP9", "0100", "0120", "0140", "0133").forEach { ok(it) }
        PidCatalog.all.forEach { ok(it.command) }
        PidCatalog.all.forEach { ok(FreezeFrame.command(it.pid)) }
        listOf("03", "07", "0A", "0900", "0902", "0904", "0906", "090A").forEach { ok(it) }
    }

    @Test fun normalisationIgnoresCaseAndSpaces() { ok("01 0c"); ok("at z"); no("0 4"); no("ate") }

    @Test fun writeAndControlServicesAreBlocked() {
        listOf("04", "0400", "08", "0801", "1001", "1002", "1003", "1101", "14FF00", "2701", "2702", "2E1234AA", "2F1234", "3101", "3102", "34", "3600", "3B", "3D", "85", "28", "2C", "2A", "3E00")
            .forEach { no(it) }
    }

    @Test fun adapterSettingsWritesAndSleepAreBlocked() {
        listOf("ATPP0ASV00", "ATPPFFON", "ATLP", "ATBRD23", "ATBRT20", "ATSS", "ATFE", "ATD", "ATTP5").forEach { no(it) }
    }

    @Test fun malformedOrChainedInputIsBlocked() {
        listOf("", "  ", "0100;0400", "0100\n04", "01", "010C0D", "0200", "0A00", "09", "ATSH12", "ATSHZZZ", "0100 1".replace(" ", "!")).forEach { no(it) }
    }

    @Test fun extendedReadsNeedASourcedAllowlistEntry() {
        no("1A87"); no("22F190")
        val x = ExtendedAllowlist.of(mapOf("1A87" to "ISO 14230-3 readECUIdentification (example citation)", "22 F190" to "ISO 14229-1 Annex C, VIN"))
        ok("1A87", x); ok("22F190", x)
        no("1A88", x); no("22F191", x)
    }

    @Test fun allowlistRejectsWritesAndMissingSources() {
        listOf("1001", "2E1234AA", "2701", "3101", "0400").forEach { r ->
            assertThrows(IllegalArgumentException::class.java) { ExtendedAllowlist.of(mapOf(r to "x")) }
        }
        assertThrows(IllegalArgumentException::class.java) { ExtendedAllowlist.of(mapOf("22F190" to " ")) }
    }

    @Test fun requireThrowsWithoutTouchingTheLink() {
        val e = assertThrows(BlockedCommandException::class.java) { ReadOnlyGuard.require("1003") }
        assertTrue(e.reason.contains("session control"))
        ReadOnlyGuard.require("010C")
    }
}
