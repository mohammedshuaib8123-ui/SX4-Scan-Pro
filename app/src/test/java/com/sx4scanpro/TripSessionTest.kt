package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Test

class TripSessionTest {
    private fun live() = TripSession().also { it.begin(500L, "t.csv") }

    @Test fun freshSamplesAccumulate() {
        val s = live()
        for (t in 1..10) s.onSample(t * 1000L, 36.0, t * 1000L, null, true)
        assertEquals(TripPhase.RECORDING, s.phase)
        assertEquals(0.1, s.stats.distanceKm, 0.011) // 9 s at 36 km/h = 0.09 km
    }

    @Test fun staleSpeedPausesAndNeverAccumulates() {
        val s = live()
        s.onSample(1000, 50.0, 1000, null, true)
        s.onSample(9000, 50.0, 2000, null, true) // reading is 7 s old
        assertEquals(TripPhase.PAUSED, s.phase)
        assertEquals(TripPause.NO_FRESH_SPEED, s.pause)
        assertEquals(0.0, s.stats.distanceKm, 0.0)
    }

    @Test fun missingSpeedIsNotZero() {
        val s = live()
        s.onSample(1000, null, null, null, true)
        assertEquals(TripPhase.PAUSED, s.phase)
        assertEquals(0L, s.stats.dataMs)
    }

    @Test fun adapterLossPausesAndReconnectDoesNotBridgeGap() {
        val s = live()
        s.onSample(1000, 60.0, 1000, null, true)
        s.onSample(2000, 60.0, 2000, null, true)
        val before = s.stats.distanceKm
        s.onSample(3000, null, null, null, false)
        assertEquals(TripPause.ADAPTER_LOST, s.pause)
        s.onSample(6000, 60.0, 6000, null, true) // back within MAX_GAP_MS: still must not bridge
        assertEquals(TripPhase.RECORDING, s.phase)
        assertEquals(before, s.stats.distanceKm, 1e-9)
        s.onSample(7000, 60.0, 7000, null, true)
        assertTrue(s.stats.distanceKm > before)
    }

    @Test fun tickPausesWhenSamplesStop() {
        val s = live()
        s.onSample(1000, 40.0, 1000, null, true)
        s.tick(3000); assertEquals(TripPhase.RECORDING, s.phase)
        s.tick(7000); assertEquals(TripPhase.PAUSED, s.phase)
    }

    @Test fun stopClearsSessionAndReturnsTotals() {
        val s = live()
        s.onSample(1000, 36.0, 1000, null, true); s.onSample(2000, 36.0, 2000, null, true)
        val st = s.stop()
        assertTrue(st.distanceKm > 0)
        assertEquals(TripPhase.IDLE, s.phase)
        assertNull(s.snapshot(3000))
        s.onSample(3000, 36.0, 3000, null, true)
        assertEquals(0.0, s.stats.distanceKm, 0.0) // idle session ignores samples
    }

    @Test fun snapshotRoundTripRestoresPausedWithoutBridging() {
        val s = live()
        for (t in 1..5) s.onSample(t * 1000L, 72.0, t * 1000L, 5.0, true)
        val snap = TripCodec.decode(TripCodec.encode(s.snapshot(6000)!!))!!
        val r = TripSession(); r.restore(snap)
        assertEquals(TripPhase.PAUSED, r.phase)
        assertEquals(TripPause.APP_RESTARTED, r.pause)
        assertEquals(s.stats.distanceKm, r.stats.distanceKm, 1e-9)
        assertEquals(s.stats.maxSpeedKmh, r.stats.maxSpeedKmh, 0.0)
        assertTrue(r.stats.fuelSeen)
        val d = r.stats.distanceKm
        r.onSample(100_000, 72.0, 100_000, 5.0, true) // first sample after restore adds nothing
        assertEquals(d, r.stats.distanceKm, 1e-9)
        assertEquals(TripPhase.RECORDING, r.phase)
    }

    @Test fun corruptOrMissingSnapshotIsRejected() {
        assertNull(TripCodec.decode(null))
        assertNull(TripCodec.decode(""))
        assertNull(TripCodec.decode("v=2\nkm=1"))
        val good = TripCodec.encode(TripSnapshot(5, "a.csv", TripStats.Totals(1.0, 2, 3, 4.0, 5.0, true), 9))
        assertNotNull(TripCodec.decode(good))
        assertNull(TripCodec.decode(good.replace("km=1.0", "km=abc")))
        assertNull(TripCodec.decode(good.replace("km=1.0", "km=-3")))
        assertNull(TripCodec.decode(good.replace("km=1.0", "km=NaN")))
        assertNull(TripCodec.decode(good.lines().filterNot { it.startsWith("file=") }.joinToString("\n")))
    }

    @Test fun resumeAfterReconnectKeepsTotals() {
        val s = live()
        s.onSample(1000, 36.0, 1000, null, true); s.onSample(2000, 36.0, 2000, null, true)
        val d = s.stats.distanceKm
        s.tick(20_000)
        s.resume(21_000)
        assertEquals(d, s.stats.distanceKm, 0.0)
    }
}
