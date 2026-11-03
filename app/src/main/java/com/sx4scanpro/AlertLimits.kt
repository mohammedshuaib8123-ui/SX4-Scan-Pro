package com.sx4scanpro

/**
 * Warning limits chosen by the owner. There is no verified SX4 ZDi limit in this app, so every
 * limit is off until the owner turns it on and sets the number.
 */
internal data class AlertLimits(
    val coolantMaxC: Int? = null,
    val rpmMax: Int? = null,
    val boostMaxKpa: Int? = null
) {
    fun rules(): List<WarningRule> = buildList {
        coolantMaxC?.let { add(WarningRule("coolant-limit", "coolant", WarningSeverity.CAUTION, it.toDouble(), it - 5.0)) }
        rpmMax?.let { add(WarningRule("rpm-limit", "rpm", WarningSeverity.CAUTION, it.toDouble(), it - 200.0)) }
        boostMaxKpa?.let { add(WarningRule("boost-limit", "boost", WarningSeverity.CAUTION, it.toDouble(), it - 10.0)) }
    }

    companion object {
        val COOLANT = 80..130
        val RPM = 2000..6000
        val BOOST = 50..300
        const val COOLANT_START = 100
        const val RPM_START = 4000
        const val BOOST_START = 150

        fun parse(value: Int, range: IntRange): Int? = if (value in range) value else null
    }
}
