package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitSessionTest {
    @Test fun parsesEveryDocumentedState() {
        val map = mapOf(
            "connecting" to SessionPhase.CONNECTING, "connected" to SessionPhase.CONNECTED,
            "projecting" to SessionPhase.PROJECTING, "disconnected" to SessionPhase.DISCONNECTED,
            "failed" to SessionPhase.FAILED
        )
        map.forEach { (text, phase) -> assertEquals(phase, HeadUnitProtocol.parse(text, "wifi", "", 0).phase) }
        assertEquals(SessionPhase.PROJECTING, HeadUnitProtocol.parse(" PROJECTING ", null, null, 5).phase)
    }

    @Test fun unknownStateIsUnknownNotGuessed() {
        assertEquals(SessionPhase.UNKNOWN, HeadUnitProtocol.parse("warming_up", "usb", "", 0).phase)
        assertEquals(SessionPhase.UNKNOWN, HeadUnitProtocol.parse(null, null, null, 0).phase)
    }

    @Test fun describesProjectionAndFailure() {
        assertEquals("Android Auto is projecting over Wi-Fi.", HeadUnitProtocol.describe(HeadUnitProtocol.parse("projecting", "wifi", "", 1000)))
        assertEquals("Connection failed: the Android Auto handshake failed.", HeadUnitProtocol.describe(HeadUnitProtocol.parse("failed", "", "handshake_failed", 0)))
        assertEquals("Not connected: the link was lost.", HeadUnitProtocol.describe(HeadUnitProtocol.parse("disconnected", "usb", "link_lost", 0)))
    }

    @Test fun unfamiliarReasonIsQuotedNotMapped() {
        val text = HeadUnitProtocol.describe(HeadUnitProtocol.parse("failed", "", "cosmic_ray", 0))
        assertTrue(text.contains("cosmic_ray"))
    }

    @Test fun actionNamesUseTheNamespaceNotThePackage() {
        assertEquals("com.andrerinas.openheadunit.ACTION_CONNECT", HeadUnitProtocol.action(HeadUnitProtocol.CONNECT))
        assertEquals("com.andrerinas.headunitrevived", HeadUnitProtocol.PACKAGE)
    }

    @Test fun nightModeStates() {
        assertEquals("auto", HeadUnitProtocol.nightModeState(ThemeMode.AUTO))
        assertEquals("night", HeadUnitProtocol.nightModeState(ThemeMode.NIGHT))
        assertEquals("day", HeadUnitProtocol.nightModeState(ThemeMode.DAY))
    }

    @Test fun gatewayIsLittleEndianDottedQuad() {
        // 192.168.43.1 as the system stores it: 0x012BA8C0
        assertEquals("192.168.43.1", HeadUnitProtocol.gatewayIp(0x012BA8C0))
        assertNull(HeadUnitProtocol.gatewayIp(0))
    }

    @Test fun negativeIntFromHighOctetStillDecodes() {
        // 200.0.0.255 has the high bit set, so it is a negative Int.
        assertEquals("200.0.0.255", HeadUnitProtocol.gatewayIp(0xFF0000C8.toInt()))
    }
}
