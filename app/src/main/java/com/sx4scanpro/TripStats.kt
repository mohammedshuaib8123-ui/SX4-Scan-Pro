package com.sx4scanpro

/**
 * Trip computer driven only by real samples. A gap longer than [MAX_GAP_MS] (adapter dropped,
 * no ECU answer) is skipped, never filled in, so distance is never invented.
 */
internal class TripStats {
    var distanceKm = 0.0; private set
    var movingMs = 0L; private set
    var dataMs = 0L; private set
    var maxSpeedKmh = 0.0; private set
    var fuelLitres = 0.0; private set
    var fuelSeen = false; private set

    private var lastT: Long? = null
    private var lastSpeed: Double? = null
    private var lastFuel: Double? = null

    fun add(tMs: Long, speedKmh: Double?, fuelLitresPerHour: Double?) {
        val lt = lastT
        if (lt != null && tMs > lt && tMs - lt <= MAX_GAP_MS) {
            val dt = tMs - lt
            val hours = dt / 3_600_000.0
            val s0 = lastSpeed
            if (s0 != null && speedKmh != null) {
                val avg = (s0 + speedKmh) / 2.0
                distanceKm += avg * hours
                dataMs += dt
                if (avg > MOVING_KMH) movingMs += dt
            }
            val f0 = lastFuel
            if (f0 != null && fuelLitresPerHour != null) {
                fuelLitres += (f0 + fuelLitresPerHour) / 2.0 * hours
                fuelSeen = true
            }
        }
        if (speedKmh != null && speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
        lastT = tMs
        lastSpeed = speedKmh
        lastFuel = fuelLitresPerHour
    }

    /** Average speed while moving, or null before the car has moved. */
    fun averageMovingKmh(): Double? = if (movingMs > 0) distanceKm / (movingMs / 3_600_000.0) else null

    /** Only when the ECU reported a fuel rate and the trip is long enough to mean something. */
    fun litresPer100Km(): Double? = if (fuelSeen && distanceKm >= 0.5) fuelLitres / distanceKm * 100.0 else null

    companion object {
        const val MAX_GAP_MS = 10_000L
        const val MOVING_KMH = 1.0

        fun clock(ms: Long): String {
            val total = ms / 1000
            return String.format(java.util.Locale.US, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
        }
    }
}

/** CSV lines for a recorded trip. Missing readings are left empty, never zero. */
internal object TripCsv {
    private fun esc(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    fun header(columns: List<String>): String = (listOf("time") + columns).joinToString(",") { esc(it) }

    fun row(time: String, values: List<String?>): String = (listOf(time) + values.map { it.orEmpty() }).joinToString(",") { esc(it) }
}
