package com.sx4scanpro

/** Why the trip is not accumulating right now. Shown to the driver; never hidden. */
internal enum class TripPause(val text: String) {
    ADAPTER_LOST("Paused: adapter or ECU not answering"),
    NO_FRESH_SPEED("Paused: no fresh speed reading"),
    APP_RESTARTED("Paused: app restarted, reconnect to continue this trip")
}

internal enum class TripPhase { IDLE, RECORDING, PAUSED }

/** What is written to disk so a trip survives app switching, screen off and process death. */
internal data class TripSnapshot(
    val startMs: Long,
    val fileName: String,
    val totals: TripStats.Totals,
    val savedAtMs: Long
)

internal object TripCodec {
    fun encode(s: TripSnapshot): String = listOf(
        "v=1", "start=${s.startMs}", "file=${s.fileName.replace('\n', ' ')}", "saved=${s.savedAtMs}",
        "km=${s.totals.distanceKm}", "moving=${s.totals.movingMs}", "data=${s.totals.dataMs}",
        "max=${s.totals.maxSpeedKmh}", "fuel=${s.totals.fuelLitres}", "fuelSeen=${s.totals.fuelSeen}"
    ).joinToString("\n")

    /** Null for anything incomplete, malformed, negative or non-finite: a corrupt record is dropped, never guessed. */
    fun decode(text: String?): TripSnapshot? {
        if (text.isNullOrBlank()) return null
        val m = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        if (m["v"] != "1") return null
        val km = m["km"]?.toDoubleOrNull() ?: return null
        val fuel = m["fuel"]?.toDoubleOrNull() ?: return null
        val max = m["max"]?.toDoubleOrNull() ?: return null
        val moving = m["moving"]?.toLongOrNull() ?: return null
        val data = m["data"]?.toLongOrNull() ?: return null
        val start = m["start"]?.toLongOrNull() ?: return null
        val saved = m["saved"]?.toLongOrNull() ?: return null
        val seen = m["fuelSeen"]?.toBooleanStrictOrNull() ?: return null
        val file = m["file"] ?: return null
        if (listOf(km, fuel, max).any { !it.isFinite() || it < 0.0 } || moving < 0 || data < 0 || start <= 0) return null
        return TripSnapshot(start, file, TripStats.Totals(km, moving, data, max, fuel, seen), saved)
    }
}

/**
 * Trip state machine, independent of any Activity. Only a fresh speed from an answering ECU
 * accumulates; otherwise the trip is paused with a visible reason and the next sample starts
 * a new segment (no distance is ever bridged across the pause).
 */
internal class TripSession {
    var phase = TripPhase.IDLE; private set
    var pause: TripPause? = null; private set
    var stats = TripStats(); private set
    var startMs = 0L; private set
    var fileName = ""; private set
    private var lastSampleMs = 0L

    fun begin(now: Long, file: String) { stats = TripStats(); startMs = now; fileName = file; lastSampleMs = now; phase = TripPhase.RECORDING; pause = null }

    /** Re-adopt a saved trip after the process was killed. It stays paused until live data returns. */
    fun restore(s: TripSnapshot) {
        stats = TripStats.restore(s.totals); startMs = s.startMs; fileName = s.fileName
        phase = TripPhase.PAUSED; pause = TripPause.APP_RESTARTED; lastSampleMs = 0L
    }

    /** Continue the held trip after a reconnect. */
    fun resume(now: Long) { if (phase == TripPhase.PAUSED) { lastSampleMs = now; stats.breakContinuity() } }

    fun hasTrip() = phase != TripPhase.IDLE

    /** One call per recorded poll. [speedCapturedAtMs] is when the ECU actually answered with that speed. */
    fun onSample(now: Long, speedKmh: Double?, speedCapturedAtMs: Long?, fuelLph: Double?, ecuAnswering: Boolean) {
        if (phase == TripPhase.IDLE) return
        if (!ecuAnswering) { pauseFor(TripPause.ADAPTER_LOST); return }
        val fresh = speedKmh != null && speedCapturedAtMs != null && now - speedCapturedAtMs <= STALE_MS
        if (!fresh) { pauseFor(TripPause.NO_FRESH_SPEED); return }
        if (phase == TripPhase.PAUSED) { stats.breakContinuity(); phase = TripPhase.RECORDING; pause = null }
        lastSampleMs = now
        stats.add(now, speedKmh, fuelLph)
    }

    /** Clock tick: if the poll loop stopped feeding samples (hung adapter, recovery in progress) the trip pauses. */
    fun tick(now: Long) {
        if (phase == TripPhase.RECORDING && now - lastSampleMs > STALE_MS) pauseFor(TripPause.ADAPTER_LOST)
    }

    private fun pauseFor(reason: TripPause) {
        if (phase == TripPhase.RECORDING) stats.breakContinuity()
        phase = TripPhase.PAUSED; pause = reason
    }

    fun snapshot(now: Long): TripSnapshot? = if (phase == TripPhase.IDLE) null else TripSnapshot(startMs, fileName, stats.totals(), now)

    /** The only way a trip ends (besides a new trip): returns the final stats and clears the session. */
    fun stop(): TripStats { val s = stats; stats = TripStats(); phase = TripPhase.IDLE; pause = null; startMs = 0L; fileName = ""; return s }

    companion object { const val STALE_MS = 5_000L }
}
