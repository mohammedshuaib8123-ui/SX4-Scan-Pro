package com.sx4scanpro

/**
 * Where a decoding formula is documented. [SPEC] is the standard itself (SAE J1979 / ISO 15031-5, Mode 01); the URLs are the
 * public tables each formula was cross-checked against on 2026-11-10 (formula, unit and byte count all matched).
 */
internal object DecoderSources {
    const val SPEC = "SAE J1979 / ISO 15031-5, Mode 01 (service 01) PID table"
    const val WIKIPEDIA = "https://en.wikipedia.org/wiki/OBD-II_PIDs"
    const val CSS_ELECTRONICS = "https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979"
}

/** One verified decoder. Numbers decode to a [Double]; [integer] says whether the value is whole by definition (no fractional digits shown). */
internal data class DecoderDef(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    /** The documented formula in terms of the data bytes A (first) and B (second). */
    val formula: String,
    val integer: Boolean,
    val sources: List<String>
)

/** What became of one ECU reply. The verbatim reply is always kept in [raw] so a decoded value can be traced back to it. */
internal sealed class Decoding {
    abstract val raw: String

    data class Decoded(override val raw: String, val def: DecoderDef, val value: Double, val text: String) : Decoding()

    /** A well-formed Mode 01 reply for a PID with no verified decoder: kept as data bytes, never interpreted. */
    data class Raw(override val raw: String, val pid: Int, val payloadHex: String) : Decoding()

    data class Invalid(override val raw: String, val pid: Int?, val reason: InvalidReason) : Decoding()
}

internal enum class InvalidReason { EMPTY, NEGATIVE_RESPONSE, NOT_A_MODE01_RESPONSE, PID_MISMATCH, NON_HEX, WRONG_LENGTH }

/**
 * Decodes Mode 01 replies with documented, cross-checked formulas only. The arithmetic lives in [PidCatalog] (one copy, shared with
 * the dials); this registry adds the documentation, rejects anything malformed, and keeps the raw reply. A PID without a documented
 * formula here is never decoded: it comes back as [Decoding.Raw]. No Suzuki- or diesel-specific formula is ever guessed.
 */
internal object DecoderRegistry {
    private const val W = DecoderSources.WIKIPEDIA
    private const val C = DecoderSources.CSS_ELECTRONICS

    /** PID -> (documented formula, public tables it was checked against). A catalog PID missing from this table stays RAW. */
    private val documented: Map<Int, Pair<String, List<String>>> = mapOf(
        0x04 to ("A × 100 / 255" to listOf(W)),
        0x05 to ("A − 40" to listOf(W)),
        0x06 to ("A × 100 / 128 − 100" to listOf(W)),
        0x07 to ("A × 100 / 128 − 100" to listOf(C)),
        0x0A to ("A × 3" to listOf(W)),
        0x0B to ("A" to listOf(W)),
        0x0C to ("(256 × A + B) / 4" to listOf(W)),
        0x0D to ("A" to listOf(W, C)),
        0x0E to ("A / 2 − 64" to listOf(W)),
        0x0F to ("A − 40" to listOf(W)),
        0x10 to ("(256 × A + B) / 100" to listOf(W)),
        0x11 to ("A × 100 / 255" to listOf(W)),
        0x1F to ("256 × A + B" to listOf(W)),
        0x21 to ("256 × A + B" to listOf(W)),
        0x22 to ("0.079 × (256 × A + B)" to listOf(W)),
        0x23 to ("10 × (256 × A + B)" to listOf(W)),
        0x2C to ("A × 100 / 255" to listOf(W)),
        0x2D to ("A × 100 / 128 − 100" to listOf(W)),
        0x2F to ("A × 100 / 255" to listOf(W)),
        0x30 to ("A" to listOf(W)),
        0x31 to ("256 × A + B" to listOf(W)),
        0x33 to ("A" to listOf(W)),
        0x3C to ("(256 × A + B) / 10 − 40" to listOf(W)),
        0x42 to ("(256 × A + B) / 1000" to listOf(W)),
        0x43 to ("(256 × A + B) × 100 / 255" to listOf(W)),
        0x44 to ("(256 × A + B) × 2 / 65536" to listOf(W)),
        0x45 to ("A × 100 / 255" to listOf(W)),
        0x46 to ("A − 40" to listOf(W)),
        0x47 to ("A × 100 / 255" to listOf(W)),
        0x49 to ("A × 100 / 255" to listOf(C)),
        0x4A to ("A × 100 / 255" to listOf(C)),
        0x4C to ("A × 100 / 255" to listOf(C)),
        0x4D to ("256 × A + B" to listOf(W)),
        0x4E to ("256 × A + B" to listOf(C)),
        0x5A to ("A × 100 / 255" to listOf(W)),
        0x5C to ("A − 40" to listOf(W)),
        0x5D to ("(256 × A + B) / 128 − 210" to listOf(W)),
        0x5E to ("(256 × A + B) / 20" to listOf(W)),
        0x61 to ("A − 125" to listOf(W)),
        0x62 to ("A − 125" to listOf(W)),
        0x63 to ("256 × A + B" to listOf(W))
    )

    private val byPid: Map<Int, DecoderDef> = PidCatalog.all.mapNotNull { s ->
        val doc = documented[s.pid] ?: return@mapNotNull null
        DecoderDef(s.pid, s.name, s.unit, s.bytes, doc.first, s.decimals == 0, listOf(DecoderSources.SPEC + " PID %02X".format(s.pid)) + doc.second)
    }.associateBy { it.pid }

    val definitions: List<DecoderDef> get() = byPid.values.sortedBy { it.pid }
    fun definition(pid: Int): DecoderDef? = byPid[pid]
    fun isDecodable(pid: Int): Boolean = pid in byPid

    /**
     * Decodes one reply such as "410C1F40". When [expectedPid] is given, a reply for any other PID is rejected, so a late answer to an
     * earlier request can never be shown as the wrong parameter. Nothing is guessed: any doubt gives [Decoding.Invalid].
     */
    fun decode(raw: String, expectedPid: Int? = null): Decoding {
        val c = ElmReply.compact(ElmReply.stripBanners(raw))
        if (c.isEmpty()) return Decoding.Invalid(raw, null, InvalidReason.EMPTY)
        if (c.startsWith("7F")) return Decoding.Invalid(raw, null, InvalidReason.NEGATIVE_RESPONSE)
        val pid = if (c.length >= 4 && c.startsWith("41")) c.substring(2, 4).toIntOrNull(16) else null
        if (pid == null) return Decoding.Invalid(raw, null, InvalidReason.NOT_A_MODE01_RESPONSE)
        if (expectedPid != null && pid != expectedPid) return Decoding.Invalid(raw, pid, InvalidReason.PID_MISMATCH)
        val payload = c.substring(4)
        if (payload.any { it !in "0123456789ABCDEF" }) return Decoding.Invalid(raw, pid, InvalidReason.NON_HEX)
        val def = byPid[pid] ?: return if (payload.isEmpty()) Decoding.Invalid(raw, pid, InvalidReason.WRONG_LENGTH) else Decoding.Raw(raw, pid, payload)
        if (payload.length != def.bytes * 2) return Decoding.Invalid(raw, pid, InvalidReason.WRONG_LENGTH)
        val spec = PidCatalog.forPid(pid) ?: return Decoding.Raw(raw, pid, payload)
        val value = spec.decode(payload.substring(0, 2).toInt(16), if (def.bytes == 2) payload.substring(2, 4).toInt(16) else 0)
        return Decoding.Decoded(raw, def, value, PidCatalog.format(spec, value))
    }
}
