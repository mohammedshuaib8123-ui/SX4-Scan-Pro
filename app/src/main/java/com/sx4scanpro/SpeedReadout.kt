package com.sx4scanpro

/** A gauge reading older than this is shown as stale. Shared by the cluster, the telemetry row and the speed readout. */
internal const val STALE_MS = 5_000L

/**
 * What the digital speed readout shows. Built only from an ECU speed reading (PID 010D) that is present, valid and fresh;
 * anything else is [kmh] = null, shown as a dash with an empty bar. Nothing is simulated or extrapolated between updates.
 */
internal data class SpeedReadout(val kmh: Int?) {
    val valid: Boolean get() = kmh != null

    /** Three digits ("042", "000") for a valid speed, or "—". A real zero from the ECU is shown as "000", never as a dash. */
    val digits: String get() = kmh?.toString()?.padStart(3, '0') ?: "—"

    /** The leading zeros that are drawn dimmed, e.g. "0" of "042", or "00" of "000" (the last digit always stays lit). */
    val dimPrefix: String get() = if (kmh == null) "" else digits.takeWhile { it == '0' }.take(2)

    /** Lit bar segments for the displayed speed: whole segments of [maxKmh] / [segments] km/h, clamped to the bar. */
    fun litSegments(segments: Int = SEGMENTS, maxKmh: Int = BAR_MAX_KMH): Int =
        if (kmh == null) 0 else (kmh.coerceIn(0, maxKmh) * segments) / maxKmh

    companion object {
        const val SEGMENTS = 20
        /** Full-scale of the bar, matching the speed dial range. A faster car keeps a full bar while the number stays exact. */
        const val BAR_MAX_KMH = 180
        /** Highest value PID 010D can encode (one byte). */
        const val ECU_MAX_KMH = 255

        fun of(kmh: Double?): SpeedReadout =
            if (kmh == null || !kmh.isFinite() || kmh < 0.0 || kmh > ECU_MAX_KMH) SpeedReadout(null) else SpeedReadout(Math.round(kmh).toInt())

        /** From a live gauge: unavailable, unparsable or stale readings all become "no speed". */
        fun from(available: Boolean, value: String, capturedAtMs: Long, nowMs: Long): SpeedReadout {
            val v = value.toDoubleOrNull()
            return if (!available || v == null || nowMs - capturedAtMs > STALE_MS) of(null) else of(v)
        }
    }
}
