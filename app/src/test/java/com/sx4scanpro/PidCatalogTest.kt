package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidCatalogTest {
    private fun d(pid: Int, reply: String) = PidCatalog.decode(PidCatalog.forPid(pid)!!, reply)

    @Test fun commonFormulas() {
        assertEquals(2000.0, d(0x0C, "410C1F40")!!, 0.0)
        assertEquals(60.0, d(0x05, "410564")!!, 0.0)
        assertEquals(-64.0, d(0x0E, "410E00")!!, 0.0)
        assertEquals(12.0, d(0x42, "41422EE0")!!, 1e-9)
        assertEquals(0.0, d(0x06, "410680")!!, 0.0)
        assertEquals(99.21875, d(0x07, "4107FF")!!, 1e-9)
        assertEquals(30.0, d(0x0A, "410A0A")!!, 0.0)
        assertEquals(5000.0, d(0x23, "412301F4")!!, 0.0)
        assertEquals(0.0, d(0x61, "41617D")!!, 0.0)
    }

    @Test fun catalogHasNoDuplicatePidsOrNames() {
        assertEquals(PidCatalog.all.size, PidCatalog.all.map { it.pid }.toSet().size)
        assertEquals(PidCatalog.all.size, PidCatalog.all.map { it.name }.toSet().size)
    }

    @Test fun strictAboutReplyShape() {
        assertNull(d(0x0C, "410C1F"))
        assertNull(d(0x0C, "410D1F40"))
        assertNull(d(0x0C, "NODATA"))
        assertNull(d(0x05, "4105GG"))
        assertNull(d(0x05, "41050000"))
    }

    @Test fun commandAndFormat() {
        val spec = PidCatalog.forPid(0x5C)!!
        assertEquals("015C", spec.command)
        assertEquals("85", PidCatalog.format(spec, 85.0))
        assertEquals("12.00", PidCatalog.format(PidCatalog.forPid(0x42)!!, 12.0))
        assertNotNull(PidCatalog.forName("EGR commanded"))
    }

    @Test fun selectionRoundTripIgnoresUnknown() {
        val s = PidCatalog.parseSelection("92, 5,999,abc,92")
        assertEquals(setOf(92, 5), s)
        assertEquals("5,92", PidCatalog.selectionText(s))
        assertTrue(PidCatalog.parseSelection(null).isEmpty())
    }
}
