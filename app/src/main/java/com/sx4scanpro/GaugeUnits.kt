package com.sx4scanpro

/** Display units. The ECU always reports km/h and kPa; conversion happens only for display. */
internal enum class SpeedUnit(val label: String) { KMH("km/h"), MPH("mph") }
internal enum class BoostUnit(val label: String) { KPA("kPa"), BAR("bar"), PSI("psi") }

internal object GaugeUnits {
    private const val KMH_PER_MPH = 1.609344
    private const val KPA_PER_PSI = 6.894757
    private const val KPA_PER_BAR = 100.0

    fun speed(kmh: Double, unit: SpeedUnit): Double = when (unit) {
        SpeedUnit.KMH -> kmh
        SpeedUnit.MPH -> kmh / KMH_PER_MPH
    }

    fun boost(kpa: Double, unit: BoostUnit): Double = when (unit) {
        BoostUnit.KPA -> kpa
        BoostUnit.BAR -> kpa / KPA_PER_BAR
        BoostUnit.PSI -> kpa / KPA_PER_PSI
    }

    fun speedText(kmh: Double, unit: SpeedUnit): String =
        String.format(java.util.Locale.US, "%.0f", speed(kmh, unit))

    fun boostText(kpa: Double, unit: BoostUnit): String =
        String.format(java.util.Locale.US, if (unit == BoostUnit.KPA) "%.0f" else "%.1f", boost(kpa, unit))

    /** Dial range in the display unit (a drawing scale only, never a limit). */
    fun speedScale(unit: SpeedUnit): DialScale = when (unit) {
        SpeedUnit.KMH -> DialScale(180f, 20f)
        SpeedUnit.MPH -> DialScale(120f, 20f)
    }

    fun boostScale(unit: BoostUnit): DialScale = when (unit) {
        BoostUnit.KPA -> DialScale(200f, 50f)
        BoostUnit.BAR -> DialScale(2f, 0.5f)
        BoostUnit.PSI -> DialScale(30f, 10f)
    }

    fun speedUnitOf(name: String?): SpeedUnit = SpeedUnit.values().firstOrNull { it.name == name } ?: SpeedUnit.KMH
    fun boostUnitOf(name: String?): BoostUnit = BoostUnit.values().firstOrNull { it.name == name } ?: BoostUnit.KPA
}

/** Device G-force from the tablet's own sensor. Not an OBD value. */
internal object DeviceG {
    /** Magnitude of the linear acceleration vector in g (gravity already removed by the sensor). */
    fun magnitudeG(x: Float, y: Float, z: Float): Double =
        Math.sqrt((x * x + y * y + z * z).toDouble()) / 9.80665
}
