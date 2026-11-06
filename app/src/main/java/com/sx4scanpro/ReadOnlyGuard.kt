package com.sx4scanpro

internal sealed class GuardVerdict {
    object Allowed : GuardVerdict()
    data class Blocked(val reason: String) : GuardVerdict()
}

/** Thrown before anything is written to the adapter when a command is not on the read-only allowlist. The link is left untouched. */
internal class BlockedCommandException(val command: String, val reason: String) : RuntimeException("Blocked non-read-only command $command: $reason")

/**
 * Sourced read-only extended requests (KWP `1A xx`, UDS `22 xxxx`). Each entry must carry a citation saying where the
 * identifier is documented; nothing is guessed. Any other service is refused at registration time.
 */
internal class ExtendedAllowlist private constructor(val entries: Map<String, String>) {
    companion object {
        val EMPTY = ExtendedAllowlist(emptyMap())
        private val kwp = Regex("^1A[0-9A-F]{2}$")
        private val uds = Regex("^22[0-9A-F]{4}$")

        fun of(entries: Map<String, String>): ExtendedAllowlist {
            val clean = LinkedHashMap<String, String>()
            for ((req, source) in entries) {
                val r = ReadOnlyGuard.normalize(req)
                require(kwp.matches(r) || uds.matches(r)) { "Only KWP 1A xx and UDS 22 xxxx reads can be allowlisted, not $req" }
                require(source.isNotBlank()) { "Allowlist entry $req needs a source citation" }
                clean[r] = source
            }
            return ExtendedAllowlist(clean)
        }
    }
}

/**
 * Default-deny policy for everything that reaches the adapter. Allowed: the AT commands the app needs to initialise and
 * read the adapter, SAE J1979 read services (01, 02, 03, 06, 07, 09, 0A) and the sourced extended reads above.
 * Refused: clearing, resets, actuator and routine control, session control, security access, writes, coding, programming,
 * and the adapter commands that rewrite its stored settings or sleep it.
 */
internal object ReadOnlyGuard {
    fun normalize(cmd: String): String = cmd.uppercase().filterNot { it.isWhitespace() }

    private val hex2 = "[0-9A-F]{2}"
    private val allowedAt = listOf(
        "ATZ", "ATWS", "ATE[01]", "ATL[01]", "ATS[01]", "ATH[01]", "ATAT[012]", "ATSP[0-9A-C]", "ATPC", "ATI", "AT@1", "ATRV",
        "ATDP", "ATDPN", "ATIGN", "ATST$hex2", "ATSH[0-9A-F]{3}", "ATSH[0-9A-F]{6}", "ATSH[0-9A-F]{8}", "ATCRA[0-9A-F]{0,8}",
        "ATCF[0-9A-F]{3,8}", "ATCM[0-9A-F]{3,8}", "ATCAF[01]", "ATAL", "ATNL", "ATMA", "ATMR$hex2"
    ).map { Regex("^$it$") }

    private val allowedObd = listOf(
        Regex("^01$hex2$"),          // current data, one PID
        Regex("^02${hex2}00$"),      // freeze frame, one PID, frame 0
        Regex("^(03|07|0A)$"),       // stored / pending / permanent DTCs
        Regex("^06$hex2$"),          // on-board monitoring test results
        Regex("^09$hex2$")           // vehicle information
    )

    private val blockedServices = mapOf(
        "04" to "clears DTCs and emissions data", "08" to "actuator / system control", "10" to "diagnostic session control",
        "11" to "ECU reset", "14" to "clears DTCs", "27" to "security access", "2E" to "writes data", "2F" to "I/O control",
        "31" to "routine control", "34" to "programming", "35" to "programming", "36" to "programming", "37" to "programming",
        "3B" to "writes data", "3D" to "writes memory", "85" to "changes DTC setting", "28" to "changes communication",
        "2C" to "defines data", "2A" to "periodic data", "3E" to "tester present (session handling)"
    )

    fun check(raw: String, extended: ExtendedAllowlist = ExtendedAllowlist.EMPTY): GuardVerdict {
        val c = normalize(raw)
        if (c.isEmpty()) return GuardVerdict.Blocked("empty command")
        if (!c.all { it in "0123456789ABCDEF@" || it in 'G'..'Z' }) return GuardVerdict.Blocked("unexpected characters")
        if (c.startsWith("AT")) {
            return if (allowedAt.any { it.matches(c) }) GuardVerdict.Allowed
            else GuardVerdict.Blocked("adapter command not on the allowlist")
        }
        if (allowedObd.any { it.matches(c) }) return GuardVerdict.Allowed
        if (c in extended.entries) return GuardVerdict.Allowed
        val service = c.take(2)
        blockedServices[service]?.let { return GuardVerdict.Blocked("service $service $it") }
        return GuardVerdict.Blocked("not on the read-only allowlist")
    }

    /** Throws [BlockedCommandException] unless the command is allowed. */
    fun require(raw: String, extended: ExtendedAllowlist = ExtendedAllowlist.EMPTY) {
        val v = check(raw, extended)
        if (v is GuardVerdict.Blocked) throw BlockedCommandException(raw, v.reason)
    }
}
