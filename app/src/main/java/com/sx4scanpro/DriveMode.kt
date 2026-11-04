package com.sx4scanpro

/** Cockpit drive modes from the android-ui-refresh prototype: SPORT red, ECO cyan, HUD dim red on black. */
internal enum class DriveMode {
    SPORT, ECO, HUD;

    companion object {
        fun parse(text: String?): DriveMode = values().firstOrNull { it.name == text } ?: SPORT
    }
}

/** The 8-LED shift light bar: 1-3 green, 4-6 amber, 7-8 red, flashing when all eight are lit. */
internal object ShiftLights {
    /** Thresholds taken from the owner's own prototype for the SX4 1.3 DDiS. */
    val THRESHOLDS = listOf(2200, 2470, 2740, 3000, 3270, 3540, 3900, 4100)

    /** Number of lit LEDs; no reading lights none. */
    fun lit(rpm: Double?): Int = if (rpm == null || rpm.isNaN()) 0 else THRESHOLDS.count { rpm >= it }

    fun flashing(rpm: Double?): Boolean = lit(rpm) == THRESHOLDS.size
}

internal object Economy {
    private const val MIN_SPEED_KMH = 5.0
    private const val MIN_FUEL_LPH = 0.1

    /**
     * Instantaneous kilometres per litre from road speed and the ECU's fuel rate. Null when either is
     * missing or the car is crawling or idling, where the ratio is meaningless: nothing is guessed.
     */
    fun kmPerLitre(speedKmh: Double?, litresPerHour: Double?): Double? {
        if (speedKmh == null || litresPerHour == null || speedKmh.isNaN() || litresPerHour.isNaN()) return null
        if (speedKmh < MIN_SPEED_KMH || litresPerHour < MIN_FUEL_LPH) return null
        return speedKmh / litresPerHour
    }
}
