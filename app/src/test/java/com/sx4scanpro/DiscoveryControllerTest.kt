package com.sx4scanpro

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryControllerTest {
    private fun ok(base: Int, hex: String) = BlockRead(base, BlockKind.OK, hex.toLong(16), "41%02X$hex".format(base), ElmKind.DATA)
    private fun bad(base: Int, kind: BlockKind) = BlockRead(base, kind, null, "", ElmKind.NO_DATA)
    private val carReads = listOf(ok(0x00, "983B0011"), ok(0x20, "A0102001"), ok(0x40, "C0C00000"))
    private fun reg() = ParameterRegistry.withBuiltInDefinitions()
    private fun id(pid: Int) = ParameterRegistry.idOf("01", "%02X".format(pid))
    private fun ev(r: ParameterRegistry, pid: Int) = r.record(id(pid))!!.evidence

    // ---- registry integration ----
    @Test fun advertisedAndUnsupportedFollowTheBitmap() {
        val r = reg()
        val c = DiscoveryRegistry.apply(carReads, r, 1000L)
        assertEquals(ParamState.ADVERTISED, r.state(id(0x0C)))
        assertEquals(ParamState.UNSUPPORTED, r.state(id(0x11)))           // catalog PID inside a block that was read, not listed
        assertEquals(EvidenceKind.BITMAP_NOT_ADVERTISED, ev(r, 0x11).last().kind)
        assertEquals(ParamState.UNSUPPORTED, r.state(id(0x5C)))           // 0x41-0x60 block was read too
        assertTrue(c.advertised > 0 && c.unsupported > 0 && c.inconclusive == 0)
    }

    @Test fun nonCatalogAdvertisedPidsAreRecordedButNeverUnsupported() {
        val r = reg()
        DiscoveryRegistry.apply(carReads, r, 1000L)
        assertNull(PidCatalog.forPid(0x1C))
        assertEquals(ParamState.ADVERTISED, r.state(id(0x1C)))           // advertised, no catalog entry: ensured
        assertEquals("ecu-advertised", r.def(id(0x1C))!!.source)
        val unlisted = (0x01..0x60).first { PidCatalog.forPid(it) == null && it % 0x20 != 0 && it !in carPidsListed }
        assertNull("reserved/unknown PIDs that are not listed get no state at all", r.def(id(unlisted)))
    }
    private val carPidsListed = setOf(0x01, 0x1C, 0x41)

    @Test fun blockPidsAreNotRegisteredAsParameters() {
        val r = reg()
        DiscoveryRegistry.apply(carReads, r, 1000L)
        listOf(0x00, 0x20, 0x40, 0x60).forEach { assertNull(r.def(id(it))) }
    }

    @Test fun blocksNeverReadStayUntested() {
        val r = reg()
        DiscoveryRegistry.apply(carReads, r, 1000L)
        assertEquals(ParamState.UNTESTED, r.state(id(0x61)))
        assertEquals(ParamState.UNTESTED, r.state(id(0x63)))
    }

    @Test fun noDataTimeoutAndParseErrorsStayInconclusive() {
        for ((kind, evKind) in listOf(BlockKind.NO_DATA to EvidenceKind.NO_DATA, BlockKind.TIMEOUT to EvidenceKind.TIMEOUT, BlockKind.PARSE_ERROR to EvidenceKind.DECODE_FAILED, BlockKind.NEGATIVE to EvidenceKind.NEGATIVE_RESPONSE)) {
            val r = reg()
            val c = DiscoveryRegistry.apply(listOf(ok(0x00, "983B0011"), bad(0x20, kind)), r, 1000L)
            assertEquals("$kind must not change state", ParamState.UNTESTED, r.state(id(0x21)))
            assertEquals(evKind, ev(r, 0x21).last().kind)
            assertTrue((0x21..0x40).filter { PidCatalog.forPid(it) != null }.none { r.state(id(it)) == ParamState.UNSUPPORTED })
            assertTrue(c.inconclusive > 0)
        }
    }

    @Test fun anAlreadyAnsweredPidIsNeverDowngraded() {
        val r = reg()
        r.onAdvertised(id(0x11), Evidence(1, EvidenceKind.BITMAP_ADVERTISED))
        r.onPositiveResponse(id(0x11), Evidence(2, EvidenceKind.POSITIVE_RESPONSE))
        val c = DiscoveryRegistry.apply(carReads, r, 1000L)
        assertEquals(ParamState.VERIFIED, r.state(id(0x11)))
        assertTrue(c.rejected >= 1)
    }

    @Test fun anUnsupportedPidThatIsLaterListedIsResetAndAdvertised() {
        val r = reg()
        DiscoveryRegistry.apply(carReads, r, 1000L)
        assertEquals(ParamState.UNSUPPORTED, r.state(id(0x11)))
        DiscoveryRegistry.apply(listOf(ok(0x00, "983B0011").copy(mask = 0x983B0011L or (1L shl (31 - 0x10)))), r, 2000L)
        assertEquals(ParamState.ADVERTISED, r.state(id(0x11)))
    }

    // ---- controller ----
    private class Rig(val discover: () -> DiscoveryRun) {
        val gate = PollGate(); val registry = ParameterRegistry.withBuiltInDefinitions(); val logs = mutableListOf<String>()
        var saves = 0; var refusal: String? = null
        val controller = DiscoveryController(gate, { registry }, { saves++; true }, { 5000L }, { logs += it }, 300L, { refusal }, discover)
    }
    private val goodRun get() = DiscoveryRun(carReads, null, null, null, true)

    @Test fun successfulRequestUpdatesAndSavesTheRegistryAndResumesPolling() {
        val rig = Rig { goodRun }
        val out = rig.controller.request("test")
        assertEquals(DiscoveryStatus.COMPLETE, out.status)
        assertEquals(1, rig.saves)
        assertEquals(ParamState.ADVERTISED, rig.registry.state(id(0x0C)))
        assertFalse(rig.gate.isPaused())
        assertTrue(rig.gate.tryEnterSlice())
        assertFalse(rig.controller.isRunning())
    }

    @Test fun pollingResumesAndTheNextRequestWorksAfterAFailure() {
        var fail = true
        val rig = Rig { if (fail) throw java.io.IOException("socket closed") else goodRun }
        val first = rig.controller.request("a")
        assertEquals(DiscoveryStatus.FAILED, first.status)
        assertFalse(rig.gate.isPaused())
        assertFalse(rig.controller.isRunning())
        assertEquals(0, rig.saves)
        fail = false
        assertEquals(DiscoveryStatus.COMPLETE, rig.controller.request("b").status)
    }

    @Test fun aBlockedCommandIsAFailureNotACrashAndPollingResumes() {
        val rig = Rig { throw BlockedCommandException("2E1234", "write service") }
        assertEquals(DiscoveryStatus.FAILED, rig.controller.request("x").status)
        assertFalse(rig.gate.isPaused())
    }

    @Test fun aSecondConcurrentRequestIsRefusedNotQueued() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val rig = Rig { started.countDown(); release.await(5, TimeUnit.SECONDS); goodRun }
        val first = AtomicReference<DiscoveryOutcome>()
        val t = Thread { first.set(rig.controller.request("first")) }.also { it.start() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(rig.controller.isRunning())
        val second = rig.controller.request("second")
        assertEquals(DiscoveryStatus.REFUSED, second.status)
        release.countDown(); t.join(3000)
        assertEquals(DiscoveryStatus.COMPLETE, first.get().status)
        assertEquals(1, rig.saves)
    }

    @Test fun aRefusalReasonStopsBeforePollingIsTouched() {
        var called = false
        val rig = Rig { called = true; goodRun }
        rig.refusal = "ECU link is not communicating"
        val out = rig.controller.request("x")
        assertEquals(DiscoveryStatus.REFUSED, out.status)
        assertFalse(called)
        assertFalse(rig.gate.isPaused())
        assertFalse(rig.controller.isRunning())
    }

    @Test fun aSliceThatNeverEndsRefusesInsteadOfHanging() {
        val rig = Rig { goodRun }
        val inSlice = CountDownLatch(1); val release = CountDownLatch(1)
        Thread { rig.gate.tryEnterSlice(); inSlice.countDown(); release.await(); rig.gate.exitSlice() }.also { it.isDaemon = true; it.start() }
        assertTrue(inSlice.await(2, TimeUnit.SECONDS))
        val out = rig.controller.request("x")
        assertEquals(DiscoveryStatus.REFUSED, out.status)
        assertFalse(rig.gate.isPaused())
        release.countDown()
    }

    @Test fun discoveryMayBeRequestedFromInsideAPollSlice() {
        val rig = Rig { goodRun }
        val out = AtomicReference<DiscoveryOutcome>()
        val t = Thread {
            assertTrue(rig.gate.tryEnterSlice())
            try { out.set(rig.controller.request("from poll thread")) } finally { rig.gate.exitSlice() }
        }.also { it.isDaemon = true; it.start() }
        t.join(3000)
        assertFalse(t.isAlive)
        assertNotNull(out.get())
        assertEquals(DiscoveryStatus.COMPLETE, out.get().status)
        assertFalse(rig.gate.isPaused())
    }

    @Test fun anUnrestoredHeaderIsVisibleInTheOutcome() {
        val rig = Rig { DiscoveryRun(listOf(carReads[0]), "486B10", "10", HeaderRestore(false, false), false) }
        val out = rig.controller.request("x")
        assertEquals(DiscoveryStatus.PARTIAL, out.status)
        assertTrue(out.message.contains("HEADERS NOT RESTORED"))
    }
}
