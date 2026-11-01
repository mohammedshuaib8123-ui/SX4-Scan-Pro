package com.sx4scanpro

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuNegotiationTest {
    /** Scripted adapter: replies keyed by the protocol currently selected. */
    private class FakeElm(
        private val answeringProtocols: Set<String>,
        private val noAnswer: String = "UNABLETOCONNECT",
        private val answer0100: String = "4100BE3FA813"
    ) {
        var protocol = "0"
        val sent = mutableListOf<String>()
        fun send(cmd: String, timeoutMs: Long): String {
            sent += cmd
            return when {
                cmd.startsWith("ATSP") -> { protocol = cmd.removePrefix("ATSP"); "OK" }
                cmd == "0100" -> if (protocol in answeringProtocols) answer0100 else noAnswer
                cmd == "0120" -> "412080000000"      // advertises nothing further
                cmd == "0140" -> "414000000000"
                else -> "NODATA"
            }
        }
    }

    @Test fun automaticProtocolWorks() {
        val elm = FakeElm(setOf("0"))
        val r = EcuNegotiation(elm::send).run()
        assertEquals("0", r.protocol)
        assertTrue(0x0C in r.supportedPids)
        assertEquals("", r.failure)
        assertFalse(elm.sent.any { it.startsWith("ATSP") })
    }

    @Test fun fallsBackToKwpFastWhenAutomaticFails() {
        val elm = FakeElm(setOf("5"))
        val r = EcuNegotiation(elm::send).run()
        assertEquals("5", r.protocol)
        assertTrue(r.supportedPids.isNotEmpty())
        assertEquals(listOf("0100", "ATSP5", "0100", "0120"), elm.sent.take(4))
    }

    @Test fun triesProtocolsInSx4Order() {
        val elm = FakeElm(setOf("3"))
        val r = EcuNegotiation(elm::send).run()
        assertEquals("3", r.protocol)
        assertEquals(listOf("ATSP5", "ATSP4", "ATSP3"), elm.sent.filter { it.startsWith("ATSP") })
    }

    @Test fun silentEcuIsAFailureNotASilentSuccess() {
        val elm = FakeElm(emptySet())
        val r = EcuNegotiation(elm::send).run()
        assertNull(r.protocol)
        assertTrue(r.supportedPids.isEmpty())
        assertTrue(r.failure, r.failure.contains("ignition"))
        // adapter is handed back in automatic mode
        assertEquals("ATSP0", elm.sent.last())
        assertEquals("0", elm.protocol)
    }

    @Test fun noDataIsExplained() {
        val r = EcuNegotiation(FakeElm(emptySet(), noAnswer = "NODATA")::send).run()
        assertTrue(r.failure, r.failure.contains("did not answer"))
    }

    @Test fun busInitErrorIsExplained() {
        val r = EcuNegotiation(FakeElm(emptySet(), noAnswer = "BUSINIT:...ERROR")::send).run()
        assertTrue(r.failure, r.failure.contains("K-line"))
    }

    @Test fun garbageIsNotMistakenForAnAnswer() {
        val r = EcuNegotiation(FakeElm(setOf("0"), answer0100 = "41FF")::send).run()
        assertTrue(r.supportedPids.isEmpty())
        assertTrue(r.failure.isNotBlank())
    }

    @Test fun chainedBlocksAreFollowedOnlyWhenAdvertised() {
        val sent = mutableListOf<String>()
        val r = EcuNegotiation { cmd, _ ->
            sent += cmd
            when (cmd) {
                "0100" -> "4100FFFFFFFF"   // advertises 0x20
                "0120" -> "4120FFFFFFFF"   // advertises 0x40
                "0140" -> "41400000001F"
                else -> "OK"
            }
        }.run()
        assertEquals(listOf("0100", "0120", "0140"), sent)
        assertTrue(0x0C in r.supportedPids && 0x33 in r.supportedPids && 0x40 in r.supportedPids)
    }

    @Test fun ioFailurePropagatesSoTheCallerCanClose() {
        var thrown = false
        try {
            EcuNegotiation { _, _ -> throw IOException("link dropped") }.run()
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test fun adapterNameMatching() {
        listOf("Vgate iCar Pro", "OBDII", "V-LINK", "ELM327", "vLinker MC+", "iCar2").forEach {
            assertTrue(it, EcuNegotiation.looksLikeObdAdapter(it))
        }
        listOf("JBL Flip 5", "Realme Buds", "", null, "Maruti Car Audio").forEach {
            assertFalse(it.toString(), EcuNegotiation.looksLikeObdAdapter(it))
        }
    }
}
