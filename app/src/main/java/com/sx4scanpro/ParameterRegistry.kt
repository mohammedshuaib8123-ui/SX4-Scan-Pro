package com.sx4scanpro

/** What is known about one diagnostic parameter. The states are deliberately separate: advertised is not verified, verified is not decoded. */
internal enum class ParamState { UNTESTED, ADVERTISED, VERIFIED, DECODED, UNSUPPORTED }

internal enum class ParamDataType { NUMBER, TEXT, BITMAP, LIST, UNKNOWN }

internal enum class EvidenceKind {
    BITMAP_ADVERTISED, BITMAP_NOT_ADVERTISED, POSITIVE_RESPONSE, DECODED_OK, DECODE_FAILED, NEGATIVE_RESPONSE, NO_DATA, TIMEOUT, RESET
}

/** Why a state is what it is. [rawSeq] points at the CaptureStore exchange; no sensor values are ever stored here. */
internal data class Evidence(val tMs: Long, val kind: EvidenceKind, val note: String = "", val rawSeq: Long? = null)

internal data class ParamDef(
    val id: String, val service: String, val identifier: String, val name: String, val description: String,
    val dataType: ParamDataType, val unit: String = "", val hasDecoder: Boolean = false, val source: String = ""
)

internal data class ParamRecord(
    val id: String, val state: ParamState = ParamState.UNTESTED, val lastVerifiedMs: Long? = null,
    val lastChangeMs: Long? = null, val evidence: List<Evidence> = emptyList()
)

internal data class Transition(val applied: Boolean, val from: ParamState, val to: ParamState, val reason: String)

/**
 * Central, in-memory registry of parameters and their capability state. State only changes through the event methods
 * below, which enforce the transition rules; nothing here talks to the adapter. Persistence is in [RegistryStore].
 */
internal class ParameterRegistry {
    private val defs = LinkedHashMap<String, ParamDef>()
    private val records = HashMap<String, ParamRecord>()

    companion object {
        const val MAX_EVIDENCE = 20
        fun idOf(service: String, identifier: String) = service.uppercase() + ":" + identifier.uppercase()

        /** Mode 09 items this app can already decode (see Mode09Decoder). Names are the SAE J1979 item names. */
        private val mode09 = listOf(
            Triple("00", "Supported Mode 09 items", ParamDataType.BITMAP), Triple("02", "Vehicle Identification Number", ParamDataType.TEXT),
            Triple("04", "Calibration IDs", ParamDataType.LIST), Triple("06", "Calibration Verification Numbers", ParamDataType.LIST),
            Triple("0A", "ECU name", ParamDataType.TEXT)
        )

        /** A registry holding every definition the app already has (the PidCatalog and the Mode 09 decoders), all UNTESTED. */
        fun withBuiltInDefinitions(): ParameterRegistry = ParameterRegistry().also { r ->
            PidCatalog.all.forEach { s ->
                r.register(ParamDef(idOf("01", "%02X".format(s.pid)), "01", "%02X".format(s.pid), s.name, "SAE J1979 Mode 01 PID %02X".format(s.pid),
                    ParamDataType.NUMBER, s.unit, hasDecoder = true, source = "PidCatalog"))
            }
            mode09.forEach { (id, name, type) ->
                r.register(ParamDef(idOf("09", id), "09", id, name, "SAE J1979 Mode 09 InfoType $id", type, hasDecoder = true, source = "Mode09Decoder"))
            }
        }
    }

    /** Adds a definition. Re-registering an identical one is harmless; a different definition for the same id is refused. */
    fun register(def: ParamDef): Boolean {
        val existing = defs[def.id]
        if (existing != null) return existing == def
        if (def.id != idOf(def.service, def.identifier)) return false
        defs[def.id] = def
        return true
    }

    /**
     * Definition for a parameter the ECU advertised that the app has no entry for. It carries no invented name and no
     * decoder, so it stays raw until a verified decoder exists.
     */
    fun ensure(service: String, identifier: String): ParamDef {
        val id = idOf(service, identifier)
        return defs[id] ?: ParamDef(id, service.uppercase(), identifier.uppercase(), "Service ${service.uppercase()} identifier ${identifier.uppercase()}",
            "Reported by the ECU; no catalog entry and no decoder in this app", ParamDataType.UNKNOWN, source = "ecu-advertised").also { defs[id] = it }
    }

    fun def(id: String): ParamDef? = defs[id]
    fun all(): List<ParamDef> = defs.values.toList()
    fun record(id: String): ParamRecord? = if (id in defs) records[id] ?: ParamRecord(id) else null
    fun state(id: String): ParamState? = record(id)?.state
    fun byState(s: ParamState): List<ParamDef> = defs.values.filter { state(it.id) == s }
    internal fun records(): Map<String, ParamRecord> = records.toMap()
    internal fun restore(rec: ParamRecord): Boolean { if (rec.id !in defs) return false; records[rec.id] = rec.copy(evidence = rec.evidence.takeLast(MAX_EVIDENCE)); return true }

    private fun move(id: String, to: ParamState?, ev: Evidence, verified: Boolean = false, reason: String): Transition {
        val cur = record(id) ?: return Transition(false, ParamState.UNTESTED, ParamState.UNTESTED, "unknown parameter $id")
        val target = to ?: cur.state
        records[id] = cur.copy(
            state = target, lastVerifiedMs = if (verified) ev.tMs else cur.lastVerifiedMs,
            lastChangeMs = if (target != cur.state) ev.tMs else cur.lastChangeMs, evidence = (cur.evidence + ev).takeLast(MAX_EVIDENCE)
        )
        return Transition(true, cur.state, target, reason)
    }

    private fun rejected(id: String, ev: Evidence, why: String): Transition {
        val cur = record(id) ?: return Transition(false, ParamState.UNTESTED, ParamState.UNTESTED, "unknown parameter $id")
        // The attempt is kept as evidence (state untouched) so the history explains why nothing changed.
        records[id] = cur.copy(evidence = (cur.evidence + ev.copy(note = (ev.note + " [not applied: $why]").trim())).takeLast(MAX_EVIDENCE))
        return Transition(false, cur.state, cur.state, why)
    }

    /** A support bitmap (or other valid source) says the ECU supports it. UNTESTED becomes ADVERTISED; later states are kept. */
    fun onAdvertised(id: String, ev: Evidence): Transition {
        val cur = record(id) ?: return Transition(false, ParamState.UNTESTED, ParamState.UNTESTED, "unknown parameter $id")
        return when (cur.state) {
            ParamState.UNTESTED -> move(id, ParamState.ADVERTISED, ev, reason = "advertised by ECU support information")
            ParamState.ADVERTISED, ParamState.VERIFIED, ParamState.DECODED -> move(id, null, ev, reason = "already ${cur.state}; evidence added")
            ParamState.UNSUPPORTED -> rejected(id, ev, "marked unsupported by earlier evidence; reset first if the ECU has changed")
        }
    }

    /** The ECU answered a real request with a valid positive response. */
    fun onPositiveResponse(id: String, ev: Evidence): Transition {
        val cur = record(id) ?: return Transition(false, ParamState.UNTESTED, ParamState.UNTESTED, "unknown parameter $id")
        return when (cur.state) {
            ParamState.DECODED, ParamState.VERIFIED -> move(id, null, ev, verified = true, reason = "already ${cur.state}; verification time updated")
            else -> move(id, ParamState.VERIFIED, ev, verified = true, reason = "valid positive ECU response")
        }
    }

    /** A validated decoder interpreted a verified response. Needs a decoder and a prior positive response. */
    fun onDecoded(id: String, ev: Evidence): Transition {
        val cur = record(id) ?: return Transition(false, ParamState.UNTESTED, ParamState.UNTESTED, "unknown parameter $id")
        if (defs[id]?.hasDecoder != true) return rejected(id, ev, "no decoder exists for this parameter")
        return when (cur.state) {
            ParamState.VERIFIED -> move(id, ParamState.DECODED, ev, reason = "validated decoder interpreted the response")
            ParamState.DECODED -> move(id, null, ev, reason = "already DECODED; evidence added")
            else -> rejected(id, ev, "cannot be decoded before a positive ECU response (state ${cur.state})")
        }
    }

    /** A decoder could not interpret a response. This is evidence about the decoder, never a reason to call the parameter unsupported. */
    fun onDecodeFailed(id: String, ev: Evidence): Transition = move(id, null, ev, reason = "decode failure recorded; state unchanged")

    /** Inconclusive results (NO DATA, timeouts) are kept as evidence only. */
    fun onInconclusive(id: String, ev: Evidence): Transition = move(id, null, ev, reason = "inconclusive result recorded; state unchanged")

    /** UNSUPPORTED needs explicit evidence (a negative response or a support bitmap that does not list it) and no earlier positive response. */
    fun onUnsupported(id: String, ev: Evidence): Transition {
        val cur = record(id) ?: return Transition(false, ParamState.UNTESTED, ParamState.UNTESTED, "unknown parameter $id")
        if (ev.kind != EvidenceKind.NEGATIVE_RESPONSE && ev.kind != EvidenceKind.BITMAP_NOT_ADVERTISED)
            return rejected(id, ev, "${ev.kind} is not explicit evidence of non-support")
        return when (cur.state) {
            ParamState.UNTESTED, ParamState.ADVERTISED -> move(id, ParamState.UNSUPPORTED, ev, reason = "explicit evidence: ${ev.kind}")
            ParamState.UNSUPPORTED -> move(id, null, ev, reason = "already UNSUPPORTED; evidence added")
            else -> rejected(id, ev, "the ECU has already answered this parameter (state ${cur.state})")
        }
    }

    /** Back to UNTESTED (for example when the ECU identity changed). Evidence history is kept. */
    fun reset(id: String, reason: String, nowMs: Long): Transition {
        val cur = record(id) ?: return Transition(false, ParamState.UNTESTED, ParamState.UNTESTED, "unknown parameter $id")
        records[id] = cur.copy(state = ParamState.UNTESTED, lastVerifiedMs = null, lastChangeMs = nowMs,
            evidence = (cur.evidence + Evidence(nowMs, EvidenceKind.RESET, reason)).takeLast(MAX_EVIDENCE))
        return Transition(true, cur.state, ParamState.UNTESTED, reason)
    }
}
