package com.sx4scanpro

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for P1b: the OBD session belongs to ObdRuntime, not to the Activity.
 * (Android lifecycle behaviour itself is not unit-testable here; see the requirements doc for what needs the tablet.)
 */
class SessionOwnershipTest {
    private fun src(name: String): String? =
        listOf("src/main/java/com/sx4scanpro/", "app/src/main/java/com/sx4scanpro/").map { File(it + name) }.firstOrNull { it.exists() }?.readText()

    @Test fun runtimeOwnsSocketPipelineAndLoop() {
        val rt = src("ObdRuntime.kt"); assumeTrue(rt != null)
        for (m in listOf("var socket: BluetoothSocket?", "fun sendCommand(", "fun connectSession()", "fun runPollSlice(", "val pollGate", "fun disconnect("))
            assertTrue("ObdRuntime must own: $m", rt!!.contains(m))
        assertTrue("sendCommand must stay the single synchronized pipeline", Regex("@Synchronized\\s+fun sendCommand").containsMatchIn(rt!!))
    }

    @Test fun activityNoLongerOwnsTheSession() {
        val a = src("MainActivity.kt"); assumeTrue(a != null)
        assertFalse("Activity must not hold the socket itself", a!!.contains("private var socket: BluetoothSocket? = null"))
        assertFalse("Activity must not define its own sendCommand body", a.contains("Timed out waiting for OBD response"))
        assertFalse("the connect loop must not run on the Compose scope", Regex("scope\\.launch \\{\\s*disconnect\\(").containsMatchIn(a))
    }

    @Test fun activityDestroyDoesNotEndTheSession() {
        val a = src("MainActivity.kt"); assumeTrue(a != null)
        val body = a!!.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()")
        assertFalse("onDestroy must not close the socket", body.contains("closeSocket()"))
        assertFalse("onDestroy must not end the trip", Regex("\\bendTrip\\(\\)").containsMatchIn(body))
        assertTrue(body.contains("rt.onConnected = null"))
    }

    @Test fun stopFromNotificationEndsTheSessionWithoutAnActivity() {
        val rt = src("ObdRuntime.kt"); assumeTrue(rt != null)
        assertTrue(rt!!.contains("TripService.onStopRequested = { scope.launch { disconnect(true) } }"))
        val svc = src("TripService.kt"); assumeTrue(svc != null)
        assertFalse("service must not hold an Activity", svc!!.contains("MainActivity.kt") || svc.contains("runOnUiThread"))
    }
}
