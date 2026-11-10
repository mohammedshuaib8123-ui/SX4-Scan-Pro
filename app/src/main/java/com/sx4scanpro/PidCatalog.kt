package com.sx4scanpro

/** One standard Mode 01 sensor, as Torque Pro lists them. Formulas are SAE J1979. */
internal data class PidSpec(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    /** Drawing range of the dial only. It is never a limit. */
    val max: Float,
    val step: Float,
    val decimals: Int,
    val decode: (a: Int, b: Int) -> Double
) {
    val command: String get() = "01" + String.format("%02X", pid)
    val scale: DialScale get() = DialScale(max, step)
}

internal object PidCatalog {
    private fun p(pid: Int, name: String, unit: String, bytes: Int, max: Float, step: Float, decimals: Int, f: (Int, Int) -> Double) =
        PidSpec(pid, name, unit, bytes, max, step, decimals, f)

    val all: List<PidSpec> = listOf(
        p(0x04, "Engine load", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x05, "Coolant temperature", "°C", 1, 120f, 20f, 0) { a, _ -> a - 40.0 },
        p(0x06, "Short fuel trim B1", "%", 1, 100f, 20f, 1) { a, _ -> (a - 128) * 100.0 / 128.0 },
        p(0x07, "Long fuel trim B1", "%", 1, 100f, 20f, 1) { a, _ -> (a - 128) * 100.0 / 128.0 },
        p(0x0A, "Fuel pressure", "kPa", 1, 765f, 100f, 0) { a, _ -> a * 3.0 },
        p(0x0B, "Intake manifold pressure", "kPa", 1, 250f, 50f, 0) { a, _ -> a.toDouble() },
        p(0x0C, "Engine RPM", "rpm", 2, 5000f, 1000f, 0) { a, b -> (a * 256 + b) / 4.0 },
        p(0x0D, "Vehicle speed", "km/h", 1, 180f, 20f, 0) { a, _ -> a.toDouble() },
        p(0x0E, "Timing advance", "°", 1, 64f, 16f, 1) { a, _ -> a / 2.0 - 64.0 },
        p(0x0F, "Intake air temperature", "°C", 1, 100f, 20f, 0) { a, _ -> a - 40.0 },
        p(0x10, "MAF air flow rate", "g/s", 2, 100f, 20f, 2) { a, b -> (a * 256 + b) / 100.0 },
        p(0x11, "Throttle position", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x1F, "Run time since start", "s", 2, 3600f, 600f, 0) { a, b -> (a * 256 + b).toDouble() },
        p(0x21, "Distance with MIL on", "km", 2, 1000f, 200f, 0) { a, b -> (a * 256 + b).toDouble() },
        p(0x22, "Fuel rail pressure (vacuum)", "kPa", 2, 5000f, 1000f, 0) { a, b -> (a * 256 + b) * 0.079 },
        p(0x23, "Fuel rail pressure", "kPa", 2, 200000f, 50000f, 0) { a, b -> (a * 256 + b) * 10.0 },
        p(0x2C, "EGR commanded", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x2D, "EGR error", "%", 1, 100f, 25f, 1) { a, _ -> a * 100.0 / 128.0 - 100.0 },
        p(0x2F, "Fuel level", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x30, "Warm-ups since codes cleared", "", 1, 255f, 50f, 0) { a, _ -> a.toDouble() },
        p(0x31, "Distance since codes cleared", "km", 2, 5000f, 1000f, 0) { a, b -> (a * 256 + b).toDouble() },
        p(0x33, "Barometric pressure", "kPa", 1, 120f, 20f, 0) { a, _ -> a.toDouble() },
        p(0x3C, "Catalyst temperature B1S1", "°C", 2, 1000f, 200f, 0) { a, b -> (a * 256 + b) / 10.0 - 40.0 },
        p(0x42, "Control module voltage", "V", 2, 16f, 4f, 2) { a, b -> (a * 256 + b) / 1000.0 },
        p(0x43, "Absolute engine load", "%", 2, 100f, 20f, 1) { a, b -> (a * 256 + b) * 100.0 / 255.0 },
        p(0x44, "Commanded equivalence ratio", "λ", 2, 2f, 0.5f, 3) { a, b -> (a * 256 + b) / 32768.0 },
        p(0x45, "Relative throttle position", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x46, "Ambient air temperature", "°C", 1, 60f, 10f, 0) { a, _ -> a - 40.0 },
        p(0x47, "Absolute throttle position B", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x49, "Accelerator pedal position D", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x4A, "Accelerator pedal position E", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x4C, "Commanded throttle actuator", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x4D, "Run time with MIL on", "min", 2, 1000f, 200f, 0) { a, b -> (a * 256 + b).toDouble() },
        p(0x4E, "Time since codes cleared", "min", 2, 1000f, 200f, 0) { a, b -> (a * 256 + b).toDouble() },
        p(0x5A, "Relative accelerator pedal position", "%", 1, 100f, 20f, 1) { a, _ -> a * 100.0 / 255.0 },
        p(0x5C, "Engine oil temperature", "°C", 1, 150f, 25f, 0) { a, _ -> a - 40.0 },
        p(0x5D, "Fuel injection timing", "°", 2, 60f, 15f, 1) { a, b -> (a * 256 + b) / 128.0 - 210.0 },
        p(0x5E, "Engine fuel rate", "L/h", 2, 50f, 10f, 2) { a, b -> (a * 256 + b) / 20.0 },
        p(0x61, "Driver demand torque", "%", 1, 125f, 25f, 0) { a, _ -> a - 125.0 },
        p(0x62, "Actual torque", "%", 1, 125f, 25f, 0) { a, _ -> a - 125.0 },
        p(0x63, "Reference torque", "Nm", 2, 500f, 100f, 0) { a, b -> (a * 256 + b).toDouble() }
    )

    private val byPid = all.associateBy { it.pid }
    private val byName = all.associateBy { it.name }

    fun forPid(pid: Int): PidSpec? = byPid[pid]
    fun forName(name: String): PidSpec? = byName[name]

    /**
     * Decodes a single-frame reply like "410C1F40" for [spec], or null for anything that is not exactly that service, PID and payload
     * length. The parsing lives in [DecoderRegistry]; the arithmetic is [PidSpec.decode], the only copy of each formula.
     */
    fun decode(spec: PidSpec, response: String): Double? =
        (DecoderRegistry.decode(response, spec.pid) as? Decoding.Decoded)?.value

    fun format(spec: PidSpec, value: Double): String =
        String.format(java.util.Locale.US, "%." + spec.decimals + "f", value)

    /** Comma-separated PID numbers (decimal) <-> set, for saving the user's chosen gauges. */
    fun parseSelection(text: String?): Set<Int> =
        text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { byPid.containsKey(it) }.toCollection(linkedSetOf())

    fun selectionText(pids: Set<Int>): String = pids.sorted().joinToString(",")
}
