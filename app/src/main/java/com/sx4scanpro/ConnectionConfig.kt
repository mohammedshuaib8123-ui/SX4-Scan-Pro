package com.sx4scanpro

/** One ELM327 protocol choice, as in Torque's "OBD protocol" setting. */
internal enum class ObdProtocol(val elmNumber: String, val label: String) {
    AUTO("0", "Automatic"),
    ISO_9141_2("3", "ISO 9141-2 (K-line, 5 baud)"),
    KWP_SLOW("4", "ISO 14230-4 KWP (5 baud init)"),
    KWP_FAST("5", "ISO 14230-4 KWP (fast init)"),
    CAN_11_500("6", "ISO 15765-4 CAN (11 bit, 500 kbit)"),
    CAN_29_500("7", "ISO 15765-4 CAN (29 bit, 500 kbit)"),
    CAN_11_250("8", "ISO 15765-4 CAN (11 bit, 250 kbit)"),
    CAN_29_250("9", "ISO 15765-4 CAN (29 bit, 250 kbit)")
}

/** Adapter start-up options saved in Settings. */
internal data class ConnectionConfig(
    val protocol: ObdProtocol = ObdProtocol.AUTO,
    /** Try the insecure RFCOMM socket first. Many ELM327 clones only accept it. */
    val insecureFirst: Boolean = false,
    /** ATAT1: let the adapter adapt its reply timeout. Helps slow K-line ECUs. */
    val adaptiveTiming: Boolean = true,
    /** ATST: reply timeout in units of 4 ms (hex byte). null = adapter default. */
    val timeoutByte: Int? = null
) {
    /** The AT commands sent once after the reset, in order. */
    fun initCommands(): List<String> {
        val list = mutableListOf("ATE0", "ATL0", "ATS0", "ATH0")
        if (adaptiveTiming) list += "ATAT1"
        timeoutByte?.let { list += "ATST" + String.format("%02X", it.coerceIn(1, 255)) }
        list += "ATSP0"
        return list
    }

    companion object {
        val PROTOCOL_NUMBERS: Set<String> = ObdProtocol.values().map { it.elmNumber }.toSet()

        fun protocolOf(name: String?): ObdProtocol = ObdProtocol.values().firstOrNull { it.name == name } ?: ObdProtocol.AUTO
    }
}
