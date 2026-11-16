package com.sx4scanpro

/** What kind of fault a catalogued code names. It selects the generic possibilities and checks; nothing is code-specific guesswork. */
internal enum class DtcFamily(
    val possibleCauses: List<String>,
    val nextChecks: List<String>
) {
    SENSOR_CIRCUIT(
        listOf("Damaged, open or shorted wiring", "Loose or corroded connector", "Sensor fault", "Missing or wrong sensor supply or ground"),
        listOf("Inspect the sensor connector and harness for damage, water and corrosion", "Compare the live reading of the same sensor with other related readings", "Check supply and ground at the sensor using the service manual procedure")
    ),
    SENSOR_PLAUSIBILITY(
        listOf("Sensor drift or contamination", "A fault in the system the sensor measures (for example an air leak)", "Wiring or connector resistance"),
        listOf("Compare the live reading with related sensors at idle and under load", "Inspect hoses and connections of the measured system", "Check the connector and wiring")
    ),
    INJECTOR_CIRCUIT(
        listOf("Injector or its wiring open or shorted", "Connector fault", "Control-unit output fault (less common)"),
        listOf("Inspect the injector connector and harness", "Measure injector coil resistance against the service manual value", "Have the circuit tested by a workshop if wiring looks sound")
    ),
    BOOST(
        listOf("Leak in the charge-air path (hoses, clamps, intercooler)", "Turbocharger control or actuator fault", "Boost or manifold-pressure sensor fault", "Restricted air intake or exhaust"),
        listOf("Inspect intercooler hoses, clamps and pipes for splits, oil and looseness", "Check the air filter", "Compare manifold pressure and barometric pressure in live data")
    ),
    GLOW_PLUG(
        listOf("Failed glow plug", "Glow plug relay or control unit fault", "Wiring or connector fault", "Low battery voltage"),
        listOf("Check battery voltage and terminals", "Check glow plug resistance and supply as described in the service manual", "Inspect the relay and wiring")
    ),
    EGR(
        listOf("EGR valve sticking or clogged with deposits", "Blocked EGR passages", "EGR actuator, position sensor or wiring fault", "Vacuum supply problem if the valve is vacuum operated"),
        listOf("Inspect the EGR valve and passages for deposits", "Inspect the EGR wiring, connector and vacuum lines", "Compare EGR commanded value with other live data")
    ),
    FUEL_PRESSURE(
        listOf("Rail pressure sensor fault or wiring fault", "Low-pressure fuel supply problem (filter, air in fuel, supply pump)", "High-pressure pump or pressure regulator fault", "Fuel leak or injector return flow too high"),
        listOf("Check the fuel filter service history and look for air or leaks in the fuel lines", "Check battery voltage during cranking", "Have the high-pressure side tested by a workshop; do not open high-pressure fuel lines yourself")
    ),
    SUPPLY_VOLTAGE(
        listOf("Weak or failing battery", "Charging system fault", "Loose or corroded battery or ground connections"),
        listOf("Measure battery voltage at rest and while running", "Inspect and clean battery terminals and earth straps", "Have the charging system tested")
    ),
    CONTROL_MODULE(
        listOf("Control unit internal fault", "Power supply or ground problem to the control unit", "Corrupted software or memory"),
        listOf("Check battery voltage and the control unit power and ground connections", "Have the control unit checked by a workshop; do not reprogram it with this app")
    ),
    SPEED_SENSOR(
        listOf("Speed sensor or wiring fault", "Damaged sensor ring", "A fault in the module that supplies the speed signal"),
        listOf("Inspect the sensor connector and wiring", "Compare the speed shown by the app with the instrument cluster", "Have the signal source tested by a workshop")
    ),
    CRANK_CAM(
        listOf("Position sensor or wiring fault", "Incorrect sensor gap or loose sensor", "Damaged trigger wheel", "Timing component fault (less common)"),
        listOf("Inspect the sensor, connector and wiring", "Check the sensor mounting", "Have timing checked by a workshop if the engine runs poorly or stalls")
    ),
    COOLING_FAN(
        listOf("Fan motor or relay fault", "Wiring or fuse fault", "Control-signal fault"),
        listOf("Check the fan fuse and relay", "Check the fan connector and wiring", "Watch coolant temperature in live data while idling warm")
    ),
    NETWORK(
        listOf("Wiring or connector fault on the communication bus", "The other module has no power or ground", "The other module has failed"),
        listOf("Check the fuses and power supply of the module named in the code", "Inspect the bus wiring and connectors", "Have the network tested by a workshop")
    )
}

internal data class DtcInfo(val code: String, val title: String, val family: DtcFamily)

/**
 * Local, fixed catalogue of standardised (SAE J2012 / ISO 15031-6 generic) trouble-code titles, written in the app's own
 * words. It is deliberately small: a code not listed here is still shown with its exact identifier and a statement that no
 * description is available. Manufacturer-specific codes (second character 1, or 3 in most ranges) are never described.
 */
internal object DtcCatalogue {
    const val SOURCE = "SAE J2012 / ISO 15031-6 generic code titles (app's own wording)"

    private fun e(code: String, title: String, family: DtcFamily) = DtcInfo(code, title, family)

    private val entries: List<DtcInfo> = listOf(
        e("P0087", "Fuel rail pressure too low", DtcFamily.FUEL_PRESSURE),
        e("P0088", "Fuel rail pressure too high", DtcFamily.FUEL_PRESSURE),
        e("P0100", "Mass air flow sensor circuit fault", DtcFamily.SENSOR_CIRCUIT),
        e("P0101", "Mass air flow sensor range or performance problem", DtcFamily.SENSOR_PLAUSIBILITY),
        e("P0102", "Mass air flow sensor circuit, low input", DtcFamily.SENSOR_CIRCUIT),
        e("P0103", "Mass air flow sensor circuit, high input", DtcFamily.SENSOR_CIRCUIT),
        e("P0105", "Manifold or barometric pressure sensor circuit fault", DtcFamily.SENSOR_CIRCUIT),
        e("P0106", "Manifold or barometric pressure sensor range or performance problem", DtcFamily.SENSOR_PLAUSIBILITY),
        e("P0107", "Manifold or barometric pressure sensor circuit, low input", DtcFamily.SENSOR_CIRCUIT),
        e("P0108", "Manifold or barometric pressure sensor circuit, high input", DtcFamily.SENSOR_CIRCUIT),
        e("P0110", "Intake air temperature sensor circuit fault", DtcFamily.SENSOR_CIRCUIT),
        e("P0112", "Intake air temperature sensor circuit, low input", DtcFamily.SENSOR_CIRCUIT),
        e("P0113", "Intake air temperature sensor circuit, high input", DtcFamily.SENSOR_CIRCUIT),
        e("P0115", "Engine coolant temperature sensor circuit fault", DtcFamily.SENSOR_CIRCUIT),
        e("P0116", "Engine coolant temperature sensor range or performance problem", DtcFamily.SENSOR_PLAUSIBILITY),
        e("P0117", "Engine coolant temperature sensor circuit, low input", DtcFamily.SENSOR_CIRCUIT),
        e("P0118", "Engine coolant temperature sensor circuit, high input", DtcFamily.SENSOR_CIRCUIT),
        e("P0120", "Throttle or accelerator pedal position sensor A circuit fault", DtcFamily.SENSOR_CIRCUIT),
        e("P0122", "Throttle or accelerator pedal position sensor A circuit, low input", DtcFamily.SENSOR_CIRCUIT),
        e("P0123", "Throttle or accelerator pedal position sensor A circuit, high input", DtcFamily.SENSOR_CIRCUIT),
        e("P0190", "Fuel rail pressure sensor circuit fault", DtcFamily.SENSOR_CIRCUIT),
        e("P0191", "Fuel rail pressure sensor range or performance problem", DtcFamily.SENSOR_PLAUSIBILITY),
        e("P0192", "Fuel rail pressure sensor circuit, low input", DtcFamily.SENSOR_CIRCUIT),
        e("P0193", "Fuel rail pressure sensor circuit, high input", DtcFamily.SENSOR_CIRCUIT),
        e("P0201", "Injector circuit fault, cylinder 1", DtcFamily.INJECTOR_CIRCUIT),
        e("P0202", "Injector circuit fault, cylinder 2", DtcFamily.INJECTOR_CIRCUIT),
        e("P0203", "Injector circuit fault, cylinder 3", DtcFamily.INJECTOR_CIRCUIT),
        e("P0204", "Injector circuit fault, cylinder 4", DtcFamily.INJECTOR_CIRCUIT),
        e("P0234", "Turbocharger overboost condition", DtcFamily.BOOST),
        e("P0235", "Turbocharger boost sensor circuit fault", DtcFamily.SENSOR_CIRCUIT),
        e("P0236", "Turbocharger boost sensor range or performance problem", DtcFamily.SENSOR_PLAUSIBILITY),
        e("P0237", "Turbocharger boost sensor circuit, low input", DtcFamily.SENSOR_CIRCUIT),
        e("P0238", "Turbocharger boost sensor circuit, high input", DtcFamily.SENSOR_CIRCUIT),
        e("P0299", "Turbocharger underboost condition", DtcFamily.BOOST),
        e("P0335", "Crankshaft position sensor circuit fault", DtcFamily.CRANK_CAM),
        e("P0336", "Crankshaft position sensor range or performance problem", DtcFamily.CRANK_CAM),
        e("P0340", "Camshaft position sensor circuit fault", DtcFamily.CRANK_CAM),
        e("P0341", "Camshaft position sensor range or performance problem", DtcFamily.CRANK_CAM),
        e("P0380", "Glow plug or heater circuit A fault", DtcFamily.GLOW_PLUG),
        e("P0400", "Exhaust gas recirculation flow fault", DtcFamily.EGR),
        e("P0401", "Exhaust gas recirculation flow insufficient", DtcFamily.EGR),
        e("P0402", "Exhaust gas recirculation flow excessive", DtcFamily.EGR),
        e("P0403", "Exhaust gas recirculation control circuit fault", DtcFamily.EGR),
        e("P0404", "Exhaust gas recirculation control range or performance problem", DtcFamily.EGR),
        e("P0480", "Cooling fan 1 control circuit fault", DtcFamily.COOLING_FAN),
        e("P0500", "Vehicle speed sensor fault", DtcFamily.SPEED_SENSOR),
        e("P0501", "Vehicle speed sensor range or performance problem", DtcFamily.SPEED_SENSOR),
        e("P0560", "System voltage fault", DtcFamily.SUPPLY_VOLTAGE),
        e("P0562", "System voltage low", DtcFamily.SUPPLY_VOLTAGE),
        e("P0563", "System voltage high", DtcFamily.SUPPLY_VOLTAGE),
        e("P0601", "Control module memory checksum error", DtcFamily.CONTROL_MODULE),
        e("P0602", "Control module programming error", DtcFamily.CONTROL_MODULE),
        e("P0606", "Control module processor fault", DtcFamily.CONTROL_MODULE),
        e("P0670", "Glow plug control module fault", DtcFamily.GLOW_PLUG),
        e("P0671", "Glow plug circuit fault, cylinder 1", DtcFamily.GLOW_PLUG),
        e("P0672", "Glow plug circuit fault, cylinder 2", DtcFamily.GLOW_PLUG),
        e("P0673", "Glow plug circuit fault, cylinder 3", DtcFamily.GLOW_PLUG),
        e("P0674", "Glow plug circuit fault, cylinder 4", DtcFamily.GLOW_PLUG),
        e("U0100", "Lost communication with the engine control module", DtcFamily.NETWORK),
        e("U0121", "Lost communication with the anti-lock brake control module", DtcFamily.NETWORK),
        e("U0155", "Lost communication with the instrument cluster control module", DtcFamily.NETWORK)
    )

    private val byCode = entries.associateBy { it.code }

    val size: Int get() = entries.size
    fun all(): List<DtcInfo> = entries

    fun lookup(code: String): DtcInfo? = byCode[code.trim().uppercase()]
}

/** Everything the app may say about one reported code, with ECU facts kept apart from app interpretation. */
internal data class DtcPresentation(
    val code: String,
    /** Category as reported by the ECU service that returned the code (STORED, PENDING, PERMANENT). */
    val ecuCategory: String,
    /** Meaning of the category, taken from the OBD-II service definition, not from the app's opinion about the fault. */
    val categoryMeaning: String,
    val title: String?,
    val titleSource: String?,
    val structure: List<String>,
    val possibleCauses: List<String>,
    val nextChecks: List<String>
) {
    val descriptionAvailable: Boolean get() = title != null

    /** Plain lines for a report. ECU-reported facts first, then a clearly labelled interpretation. */
    fun lines(): List<String> = buildList {
        add("$code  [$ecuCategory, reported by engine ECU]")
        add("  Meaning of category: $categoryMeaning")
        add("  Description: " + (title ?: "not available in this app's catalogue; look the code up in the service manual"))
        structure.forEach { add("  $it") }
        if (possibleCauses.isNotEmpty()) {
            add("  Possible causes (possibilities, not confirmed findings):")
            possibleCauses.forEach { add("    - $it") }
        }
        if (nextChecks.isNotEmpty()) {
            add("  Sensible next checks:")
            nextChecks.forEach { add("    - $it") }
        }
    }

    companion object {
        fun categoryMeaning(category: String): String = when (category.uppercase()) {
            "STORED" -> "confirmed code stored by the ECU (service 03); not proof that the fault is present at this moment"
            "PENDING" -> "seen once on the current or last drive cycle, not yet confirmed (service 07); may clear itself"
            "PERMANENT" -> "emissions-related code that stays until the ECU itself confirms the repair (service 0A)"
            else -> "category as reported by the ECU service"
        }

        fun of(code: String, category: String): DtcPresentation {
            val info = DtcCatalogue.lookup(code)
            val x = DtcExplainer.explain(code)
            return DtcPresentation(
                code = code.trim().uppercase(), ecuCategory = category.uppercase(), categoryMeaning = categoryMeaning(category),
                title = info?.title, titleSource = info?.let { DtcCatalogue.SOURCE },
                structure = x?.lines().orEmpty(),
                possibleCauses = info?.family?.possibleCauses.orEmpty(), nextChecks = info?.family?.nextChecks.orEmpty()
            )
        }
    }
}
