package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAccessTest {
    @Test fun android12AsksForNearbyDevicesAsOneGroup() {
        assertEquals(listOf(BluetoothAccess.CONNECT), BluetoothAccess.forConnecting(31))
        assertEquals(listOf(BluetoothAccess.CONNECT, BluetoothAccess.SCAN), BluetoothAccess.forScanning(34))
    }

    @Test fun android11NeedsNothingToConnectAndLocationToScan() {
        assertTrue(BluetoothAccess.forConnecting(30).isEmpty())
        assertEquals(listOf(BluetoothAccess.FINE_LOCATION), BluetoothAccess.forScanning(30))
    }

    @Test fun grantedWhenEverythingIsAllowed() {
        val s = BluetoothAccess.status(listOf("a", "b"), { true }, askedBefore = true, canShowRationale = { false })
        assertEquals(AccessStatus.GRANTED, s)
    }

    @Test fun freshInstallIsNotBlocked() {
        // Never asked: Android reports "no rationale", which must not read as permanently denied.
        val s = BluetoothAccess.status(listOf("a"), { false }, askedBefore = false, canShowRationale = { false })
        assertEquals(AccessStatus.NEEDS_REQUEST, s)
    }

    @Test fun deniedOnceCanStillBeAskedAgain() {
        val s = BluetoothAccess.status(listOf("a"), { false }, askedBefore = true, canShowRationale = { true })
        assertEquals(AccessStatus.NEEDS_REQUEST, s)
    }

    @Test fun deniedForeverIsBlockedAndPointsToSettings() {
        val s = BluetoothAccess.status(listOf("a", "b"), { it == "a" }, askedBefore = true, canShowRationale = { false })
        assertEquals(AccessStatus.BLOCKED, s)
        assertTrue(BluetoothAccess.message(s).contains("settings"))
    }

    private val vgate = BtDevice("Vgate iCar Pro", "AA:BB:CC:00:00:01", true)
    private val buds = BtDevice("Realme Buds", "AA:BB:CC:00:00:02", true)
    private val odd = BtDevice("Mystery Dongle", "AA:BB:CC:00:00:03", true)

    @Test fun explicitChoiceBeatsNameGuessing() {
        val c = AdapterSelector.choose(listOf(vgate, odd), "aa:bb:cc:00:00:03")
        assertEquals(AdapterChoice.Use(odd), c)
    }

    @Test fun singleKnownAdapterIsUsedAutomatically() {
        assertEquals(AdapterChoice.Use(vgate), AdapterSelector.choose(listOf(buds, vgate), null))
    }

    @Test fun savedAdapterThatIsNoLongerPairedFallsBackToNames() {
        assertEquals(AdapterChoice.Use(vgate), AdapterSelector.choose(listOf(vgate), "FF:FF:FF:FF:FF:FF"))
    }

    @Test fun nothingPairedSaysSo() {
        val c = AdapterSelector.choose(emptyList(), null) as AdapterChoice.Unavailable
        assertTrue(c.message, c.message.contains("scan"))
    }

    @Test fun unrecognisedDevicesAreListedSoTheDriverCanPickOne() {
        val c = AdapterSelector.choose(listOf(buds, odd), null) as AdapterChoice.Unavailable
        assertTrue(c.message, c.message.contains("Realme Buds") && c.message.contains("Mystery Dongle"))
    }

    @Test fun twoKnownAdaptersNeedAChoice() {
        val second = BtDevice("OBDII", "AA:BB:CC:00:00:04", true)
        val c = AdapterSelector.choose(listOf(vgate, second), null) as AdapterChoice.Unavailable
        assertTrue(c.message, c.message.contains("Choose one"))
    }
}
