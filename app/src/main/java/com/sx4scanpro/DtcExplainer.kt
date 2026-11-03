package com.sx4scanpro

/**
 * Explains the STRUCTURE of an OBD-II trouble code (SAE J2012): which system the letter names, whether
 * the code is standardised or manufacturer-specific, and which subsystem the third character selects.
 * It never invents a fault description: what a specific code means for this car still has to be looked
 * up in the manual or by a workshop.
 */
internal object DtcExplainer {
    data class Explanation(val system: String, val scope: String, val subsystem: String?) {
        fun lines(): List<String> = listOfNotNull(system, scope, subsystem)
    }

    private val systems = mapOf('P' to "Powertrain (engine, fuel, emissions, transmission)", 'C' to "Chassis (brakes, steering, suspension)", 'B' to "Body (airbags, locks, lights, comfort)", 'U' to "Network (module communication)")

    private val powertrainSubsystems = mapOf(
        '0' to "Fuel and air metering, auxiliary emission controls",
        '1' to "Fuel and air metering",
        '2' to "Fuel and air metering, injector circuit",
        '3' to "Ignition system or misfire",
        '4' to "Auxiliary emission controls",
        '5' to "Vehicle speed, idle control and auxiliary inputs",
        '6' to "Computer and output circuits",
        '7' to "Transmission",
        '8' to "Transmission",
        '9' to "Transmission"
    )

    /** Null for anything that is not a letter P/C/B/U plus four hex digits. */
    fun explain(code: String): Explanation? {
        val c = code.trim().uppercase()
        if (c.length != 5 || c[0] !in systems.keys || c.substring(1).any { it !in "0123456789ABCDEF" }) return null
        val scope = when (c[1]) {
            '0' -> "Standardised code (same meaning on every make)"
            '1' -> "Manufacturer-specific code (meaning set by Suzuki / Fiat; check the service manual)"
            '2' -> if (c[0] == 'P') "Standardised code (same meaning on every make)" else "Manufacturer-specific code (check the service manual)"
            '3' -> "Mixed or reserved range (check the service manual)"
            else -> "Unrecognised code range"
        }
        val sub = if (c[0] == 'P') powertrainSubsystems[c[2]]?.let { "Subsystem: $it" } else null
        return Explanation(systems.getValue(c[0]), scope, sub)
    }
}
