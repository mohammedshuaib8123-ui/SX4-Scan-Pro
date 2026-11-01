package com.sx4scanpro

/** Result of bringing the adapter up and finding out which standard PIDs the ECU supports. */
internal data class NegotiationResult(
    val supportedPids: Set<Int>,
    /** ELM protocol number that worked ("0" = automatic), or null when the ECU never answered. */
    val protocol: String?,
    /** Human-readable reason when [supportedPids] is empty. Never blank in that case. */
    val failure: String
)

/**
 * Brings the ECU link up over an ELM327 and discovers supported PIDs.
 *
 * The transport is injected so this logic runs on the JVM against a scripted adapter:
 * [send] must return the compact, upper-case reply of one command (or throw IOException).
 *
 * Why this exists: with ATSP0 an Indian-market SX4 ZDi (K-line) can take a long time to be
 * found, and an adapter that is paired but whose ECU does not answer replies "UNABLE TO CONNECT"
 * as a perfectly well-formed reply. Without this check the app would show "ECU LINK ACTIVE"
 * with every gauge empty.
 */
internal class EcuNegotiation(private val send: (command: String, timeoutMs: Long) -> String) {

    fun run(): NegotiationResult {
        // 1. Automatic protocol search. K-line slow-init is slow, so allow a long first answer.
        val auto = discover(FIRST_ANSWER_MS)
        if (auto.isNotEmpty()) return NegotiationResult(auto, "0", "")
        val autoFailure = describe(lastReply)

        // 2. Fix each protocol an SX4 ZDi could plausibly speak, most likely first.
        for (protocol in FALLBACK_PROTOCOLS) {
            val accepted = send("ATSP$protocol", COMMAND_MS)
            if (!accepted.contains("OK")) continue
            val pids = discover(FIRST_ANSWER_MS)
            if (pids.isNotEmpty()) return NegotiationResult(pids, protocol, "")
        }

        // 3. Leave the adapter in automatic mode for the next attempt.
        send("ATSP0", COMMAND_MS)
        return NegotiationResult(emptySet(), null, autoFailure)
    }

    private var lastReply: String = ""

    /** 0100, then 0120 and 0140 only when the previous block advertises them. */
    private fun discover(firstTimeoutMs: Long): Set<Int> {
        val first = send("0100", firstTimeoutMs)
        lastReply = first
        val firstSet = ObdPidSupport.decode(first)
        if (firstSet.isEmpty()) return emptySet()
        val second = if (32 in firstSet) send("0120", PID_BLOCK_MS) else ""
        val through64 = ObdPidSupport.decode(first + second)
        val third = if (64 in through64) send("0140", PID_BLOCK_MS) else ""
        return ObdPidSupport.decode(first + second + third)
    }

    companion object {
        /** SX4 ZDi is K-line: KWP2000 fast init, KWP2000 5-baud, ISO 9141-2, then CAN as a last resort. */
        val FALLBACK_PROTOCOLS = listOf("5", "4", "3", "6")
        const val FIRST_ANSWER_MS = 25_000L
        const val PID_BLOCK_MS = 12_000L
        const val COMMAND_MS = 5_000L

        /** Turns the adapter's reply to 0100 into a message the driver can act on. */
        fun describe(reply: String): String {
            val r = reply.uppercase().filterNot { it.isWhitespace() }
            return when {
                r.contains("UNABLETOCONNECT") ->
                    "Adapter is connected, but it could not reach the ECU. Turn the ignition ON (engine off is fine) and try again."
                r.contains("BUSINIT") && r.contains("ERROR") ->
                    "The ECU did not complete its K-line start-up handshake. Check the ignition is ON and the adapter is fully seated in the OBD port."
                r.contains("CANERROR") || r.contains("BUSERROR") || r.contains("BUSBUSY") ->
                    "The OBD bus reported an error. Check the adapter is fully seated and try again."
                r.contains("NODATA") ->
                    "Adapter is connected, but the ECU did not answer. Turn the ignition ON and try again."
                r.isBlank() ->
                    "Adapter is connected, but it returned nothing for the ECU request."
                else ->
                    "Adapter is connected, but the ECU's answer could not be understood."
            }
        }

        private val KNOWN_NAME_PARTS = listOf(
            "vgate", "icar", "obd", "elm", "vlink", "v-link", "vlinker", "veepeak", "konnwei", "viecar"
        )

        /** True when a paired device's name looks like an ELM327-style adapter. */
        fun looksLikeObdAdapter(name: String?): Boolean {
            val n = name.orEmpty().lowercase()
            return n.isNotBlank() && KNOWN_NAME_PARTS.any { n.contains(it) }
        }
    }
}
