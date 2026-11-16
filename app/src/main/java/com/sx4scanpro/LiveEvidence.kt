package com.sx4scanpro

/**
 * Feeds what live polling really sees into the [ParameterRegistry].
 *
 * Why this exists: the registry had the transitions (VERIFIED, DECODED) but nothing called them outside tests, so a 4.7 h real-car capture
 * with thousands of valid replies still ended with VERIFIED 0 / DECODED 0. Only real adapter results are used: a decoded reply is a
 * positive response plus a successful decode; NO DATA and malformed replies are kept as inconclusive evidence and never change a state
 * to "unsupported". No sensor values are stored, only evidence kinds.
 *
 * Evidence is throttled so a 3.5 requests/s loop cannot flood the 20-entry history or the disk: a state change is always recorded, an
 * unchanged positive result at most every [REFRESH_MS], and a repeated failure of the same kind at most every [REFRESH_MS].
 */
internal class LiveRegistryFeed(
    private val registry: () -> ParameterRegistry,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object { const val REFRESH_MS = 60_000L }

    /** True when something changed that is worth saving (a state moved). Cleared by [takeDirty]. */
    @Volatile private var dirty = false
    fun takeDirty(): Boolean { val d = dirty; dirty = false; return d }

    /** [reading] is null when the reply could not be decoded; [kind] is the adapter classification of that reply. */
    fun onPolled(pid: Int, reading: Reading?, kind: ElmKind, rawSeq: Long? = null) {
        val reg = registry()
        val id = ParameterRegistry.idOf("01", "%02X".format(pid))
        val rec = reg.record(id) ?: return
        val now = clock()
        val newest = rec.evidence.lastOrNull()
        if (reading != null) {
            val lastWasPositive = newest != null && (newest.kind == EvidenceKind.POSITIVE_RESPONSE || newest.kind == EvidenceKind.DECODED_OK)
            val due = !lastWasPositive || rec.state == ParamState.UNTESTED || rec.state == ParamState.ADVERTISED || rec.state == ParamState.UNSUPPORTED ||
                now - (rec.lastVerifiedMs ?: 0L) >= REFRESH_MS
            if (!due) return
            val a = reg.onPositiveResponse(id, Evidence(now, EvidenceKind.POSITIVE_RESPONSE, "live poll", rawSeq))
            val b = reg.onDecoded(id, Evidence(now, EvidenceKind.DECODED_OK, "live poll decoded", rawSeq))
            if (a.from != a.to || b.from != b.to) dirty = true
            return
        }
        val evKind = when (kind) {
            ElmKind.NO_DATA -> EvidenceKind.NO_DATA
            ElmKind.DATA -> EvidenceKind.DECODE_FAILED          // answered with data the decoder refused (malformed or wrong PID)
            else -> return                                        // adapter-level conditions say nothing about this parameter
        }
        if (newest != null && newest.kind == evKind && now - newest.tMs < REFRESH_MS) return
        val note = if (evKind == EvidenceKind.NO_DATA) "live poll: NO DATA" else "live poll: reply could not be decoded"
        if (evKind == EvidenceKind.NO_DATA) reg.onInconclusive(id, Evidence(now, evKind, note, rawSeq))
        else reg.onDecodeFailed(id, Evidence(now, evKind, note, rawSeq))
    }
}

/** Decides when the registry file is written: soon after a state change, otherwise rarely; never per poll. */
internal class RegistrySaveGate(private val minGapMs: Long = 30_000L) {
    private var lastSaveMs = Long.MIN_VALUE / 2
    private var pending = false

    fun markDirty() { pending = true }
    fun shouldSave(nowMs: Long): Boolean = pending && nowMs - lastSaveMs >= minGapMs
    fun saved(nowMs: Long) { pending = false; lastSaveMs = nowMs }
    fun hasPending(): Boolean = pending
}
