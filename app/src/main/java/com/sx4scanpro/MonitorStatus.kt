package com.sx4scanpro

/** What the one-off Mode 01 PID 01 request ("monitor status since DTCs cleared") really delivered. Kept apart so a failure never reads as "ready" or "lamp off". */
internal enum class MonitorReadStatus { NOT_ATTEMPTED, READ, NO_DATA, UNSUPPORTED, MALFORMED, TRANSPORT_FAILURE }

/** One readiness monitor as the ECU reports it. [complete] is null when the monitor is not supported. */
internal data class MonitorItem(val name: String, val supported: Boolean, val complete: Boolean?)

/**
 * Mode 01 PID 01 reply: lamp (MIL) state, the ECU's emission-code count and the readiness monitors. Decoded only from a verbatim reply;
 * anything else keeps its own status and no values. Layout from SAE J1979 / ISO 15031-5 (standard, not Suzuki-specific): byte A bit 7 = MIL,
 * bits 0-6 = code count; byte B bit 3 = ignition type, bits 0-2 = continuous monitors supported, bits 4-6 = those not complete; bytes C and D =
 * supported / not-complete flags of the other monitors. Their names are decoded for compression ignition (diesel) only.
 */
internal data class MonitorStatus(
    val status: MonitorReadStatus,
    val raw: String = "",
    val detail: String = "",
    val milOn: Boolean? = null,
    val dtcCount: Int? = null,
    val compressionIgnition: Boolean? = null,
    val monitors: List<MonitorItem> = emptyList(),
    /** Data bytes A B C D as hex, kept as evidence when the type is not decoded. */
    val dataHex: String = ""
) {
    val read: Boolean get() = status == MonitorReadStatus.READ

    companion object {
        private val CONTINUOUS = listOf("Misfire", "Fuel system", "Components")
        private val DIESEL = listOf(0 to "NMHC catalyst", 1 to "NOx / SCR aftertreatment", 3 to "Boost pressure", 5 to "Exhaust gas sensor", 6 to "PM filter", 7 to "EGR / VVT")
        private val FRAME = Regex("^4101([0-9A-F]{8})$")

        fun notAttempted(reason: String) = MonitorStatus(MonitorReadStatus.NOT_ATTEMPTED, detail = reason)
        fun failure(reason: String) = MonitorStatus(MonitorReadStatus.TRANSPORT_FAILURE, detail = reason)

        fun parse(raw: String): MonitorStatus {
            val lines = FrameAssembler.lines(raw)
            val frames = lines.mapNotNull { FRAME.matchEntire(it)?.groupValues?.get(1) }.distinct()
            return when {
                frames.size == 1 -> decode(frames[0], raw)
                frames.size > 1 -> MonitorStatus(MonitorReadStatus.MALFORMED, raw, "more than one reply with different values, so no single answer was chosen")
                lines.any { it.startsWith("7F01") } -> MonitorStatus(MonitorReadStatus.UNSUPPORTED, raw, "the ECU answered with a negative response")
                ElmReply.classify(raw) == ElmKind.NO_DATA -> MonitorStatus(MonitorReadStatus.NO_DATA, raw, "NO DATA")
                else -> MonitorStatus(MonitorReadStatus.MALFORMED, raw, if (raw.isBlank()) "empty reply" else "reply could not be interpreted")
            }
        }

        private fun decode(hex: String, raw: String): MonitorStatus {
            val b = (0..3).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            val (a, bb, c, d) = b
            val diesel = (bb and 0x08) != 0
            val items = ArrayList<MonitorItem>()
            CONTINUOUS.forEachIndexed { i, n ->
                val sup = (bb shr i) and 1 == 1
                items += MonitorItem(n, sup, if (sup) (bb shr (4 + i)) and 1 == 0 else null)
            }
            if (diesel) DIESEL.forEach { (bit, n) ->
                val sup = (c shr bit) and 1 == 1
                items += MonitorItem(n, sup, if (sup) (d shr bit) and 1 == 0 else null)
            }
            return MonitorStatus(MonitorReadStatus.READ, raw, "", (a and 0x80) != 0, a and 0x7F, diesel, items, hex)
        }
    }
}

/** Report wording for [MonitorStatus]. */
internal object MonitorStatusText {
    private fun state(i: MonitorItem) = when {
        !i.supported -> "not supported by the ECU"
        i.complete == true -> "test complete"
        else -> "test not complete"
    }

    fun lines(m: MonitorStatus?, modeThreeCodes: Int? = null): List<String> = buildList {
        if (m == null || m.status == MonitorReadStatus.NOT_ATTEMPTED) {
            add("Lamp and readiness (Mode 01 PID 01): not read" + (m?.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ".")
            return@buildList
        }
        if (!m.read) {
            add("Lamp and readiness (Mode 01 PID 01): not obtained - " + (m.detail.ifBlank { m.status.name }) + ". Nothing is assumed about the lamp or the monitors.")
            return@buildList
        }
        add("Lamp (MIL) as reported by the ECU: " + if (m.milOn == true) "ON" else "OFF")
        add("Emission-related code count reported by the ECU: ${m.dtcCount}" +
            if (modeThreeCodes != null && modeThreeCodes != m.dtcCount) " (differs from the ${modeThreeCodes} stored code(s) returned by service 03; both are shown as received)" else "")
        if (m.compressionIgnition == true) {
            add("Readiness monitors (diesel layout):")
            m.monitors.forEach { add("  ${it.name}: ${state(it)}") }
            add("  \"Test complete\" means the ECU has finished that self-test since codes were last cleared; it is not a pass or fail of the system.")
        } else {
            add("Readiness monitors: the ECU reports a spark-ignition layout, which this app does not decode. Raw bytes: ${m.dataHex}")
        }
    }
}
