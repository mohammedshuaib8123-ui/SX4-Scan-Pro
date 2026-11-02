package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {
    @Test fun defaultInitSequence() {
        assertEquals(listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"), ConnectionConfig().initCommands())
    }

    @Test fun timeoutAndAdaptiveOff() {
        val c = ConnectionConfig(adaptiveTiming = false, timeoutByte = 255)
        assertEquals(listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSTFF", "ATSP0"), c.initCommands())
        assertEquals("ATST01", ConnectionConfig(adaptiveTiming = false, timeoutByte = 0).initCommands()[4])
    }

    @Test fun protocolLookup() {
        assertEquals(ObdProtocol.KWP_FAST, ConnectionConfig.protocolOf("KWP_FAST"))
        assertEquals(ObdProtocol.AUTO, ConnectionConfig.protocolOf("nonsense"))
        assertTrue("5" in ConnectionConfig.PROTOCOL_NUMBERS)
    }

    @Test fun preferredProtocolIsTriedFirst() {
        val sent = mutableListOf<String>()
        val r = EcuNegotiation { cmd, _ ->
            sent += cmd
            when (cmd) {
                "ATSP5" -> "OK"
                "0100" -> if (sent.contains("ATSP5")) "4100BE3FA813" else "UNABLETOCONNECT"
                else -> "NODATA"
            }
        }.apply { preferredProtocol = "5" }.run()
        assertEquals("5", r.protocol)
        assertEquals("ATSP5", sent.first())
    }
}
