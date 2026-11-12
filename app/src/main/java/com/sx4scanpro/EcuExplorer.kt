package com.sx4scanpro

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Modules the explorer will eventually cover. Only the Mode 01 responder has ever been talked to; the others are groundwork. */
internal enum class ModuleId(val label: String) {
    ENGINE("Engine (OBD-II Mode 01 responder)"), BCM("BCM"), EPS("EPS"), ABS("ABS"), AIRBAG("Airbag")
}

/**
 * How far the evidence for a module goes, weakest to strongest (UNAVAILABLE stands apart). It is derived only from stored evidence.
 * UNAVAILABLE is part of the vocabulary but is never produced yet: not answering is not proof that a module is absent.
 */
internal enum class ModuleStatus { NOT_SCANNED, ATTEMPTED, ADVERTISED, REACHABLE, DECODED, VERIFIED, UNAVAILABLE }

internal data class ModuleEntry(val id: ModuleId, val status: ModuleStatus, val address: String?, val note: String)

internal object ModuleStatusModel {
    /**
     * ENGINE: ATTEMPTED once a discovery or scan ran; ADVERTISED when the ECU listed parameters; REACHABLE once a request got a positive
     * answer; DECODED once an answer was decoded; VERIFIED when a decoded answer AND an ECU identity item (VIN, calibration ID or CVN)
     * were both read from the ECU. Every other module stays NOT_SCANNED: nothing in this app talks to them.
     */
    fun derive(discovery: DiscoveryOutcome?, profile: EcuProfile?, registry: ParameterRegistry?): List<ModuleEntry> {
        val states = registry?.all()?.mapNotNull { registry.state(it.id) } ?: emptyList()
        val attempted = (discovery != null && discovery.status != DiscoveryStatus.REFUSED) || profile != null
        val decoded = states.any { it == ParamState.DECODED }
        val identity = profile != null && listOf(profile.vin, profile.calIds, profile.cvns).any { it.status == ScanStatus.READ && it.items.isNotEmpty() }
        val positive = states.any { it == ParamState.VERIFIED || it == ParamState.DECODED } ||
            (profile != null && listOf(profile.mode09Supported, profile.vin, profile.calIds, profile.cvns, profile.ecuName).any { it.status == ScanStatus.READ })
        val advertised = states.any { it == ParamState.ADVERTISED || it == ParamState.VERIFIED || it == ParamState.DECODED } || (discovery?.run?.advertised?.isNotEmpty() == true)
        val status = when {
            decoded && identity -> ModuleStatus.VERIFIED
            decoded -> ModuleStatus.DECODED
            positive -> ModuleStatus.REACHABLE
            advertised -> ModuleStatus.ADVERTISED
            attempted -> ModuleStatus.ATTEMPTED
            else -> ModuleStatus.NOT_SCANNED
        }
        val engineNote = when (status) {
            ModuleStatus.NOT_SCANNED -> "Not scanned in this app yet."
            ModuleStatus.ATTEMPTED -> "A scan was attempted; no usable answer is recorded. This does not mean the ECU is absent."
            else -> "From stored evidence. It answers OBD-II Mode 01 requests; that it is the engine ECU is assumed, not verified."
        }
        val engine = ModuleEntry(ModuleId.ENGINE, status, discovery?.run?.ecuAddress, engineNote)
        val others = listOf(ModuleId.BCM, ModuleId.EPS, ModuleId.ABS, ModuleId.AIRBAG).map {
            ModuleEntry(it, ModuleStatus.NOT_SCANNED, null, "Not scanned. This version sends no requests to this module; it may or may not exist on this car.")
        }
        return listOf(engine) + others
    }
}

internal data class ParamRow(
    val id: String, val name: String, val state: ParamState, val meaning: String,
    val advertised: Boolean, val requested: Boolean, val decoded: Boolean, val unsupported: Boolean, val evidence: List<String>
)

internal data class ExplorerView(
    val identity: List<Pair<String, String>>,
    val modules: List<ModuleEntry>,
    val counts: Map<ParamState, Int>,
    val params: List<ParamRow>,
    /** Parameters with a recorded state that are not listed because of the display cap. */
    val hidden: Int
)

/** Read-only view of what the existing [ParameterRegistry] and discovery already recorded. Nothing here talks to the adapter. */
internal object EcuExplorerView {
    const val MAX_ROWS = 120

    private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun evidenceText(e: Evidence): String = time(e.tMs) + " · " + when (e.kind) {
        EvidenceKind.BITMAP_ADVERTISED -> "ECU advertised it"
        EvidenceKind.BITMAP_NOT_ADVERTISED -> "ECU did not advertise it"
        EvidenceKind.POSITIVE_RESPONSE -> "request answered"
        EvidenceKind.DECODED_OK -> "reply decoded"
        EvidenceKind.DECODE_FAILED -> "reply could not be decoded"
        EvidenceKind.NEGATIVE_RESPONSE -> "ECU rejected the request"
        EvidenceKind.NO_DATA -> "NO DATA"
        EvidenceKind.TIMEOUT -> "timed out"
        EvidenceKind.RESET -> "reset"
    } + (if (e.note.isNotBlank()) " (" + e.note + ")" else "") + (e.rawSeq?.let { " [capture #$it]" } ?: "")

    fun meaning(state: ParamState, evidence: List<Evidence>): String = when (state) {
        ParamState.UNTESTED -> "Not tested yet"
        ParamState.ADVERTISED -> "ECU advertised it; not requested successfully yet"
        ParamState.VERIFIED -> "Requested successfully; reply not decoded"
        ParamState.DECODED -> "Reply decoded successfully"
        ParamState.UNSUPPORTED -> when {
            evidence.any { it.kind == EvidenceKind.NEGATIVE_RESPONSE } -> "Unsupported: the ECU rejected the request"
            evidence.any { it.kind == EvidenceKind.BITMAP_NOT_ADVERTISED } -> "Unsupported: the ECU did not advertise it"
            else -> "Unsupported"
        }
    }

    fun row(reg: ParameterRegistry, d: ParamDef): ParamRow {
        val r = reg.record(d.id) ?: ParamRecord(d.id)
        val st = r.state
        return ParamRow(
            d.id, d.name, st, meaning(st, r.evidence),
            advertised = st == ParamState.ADVERTISED || st == ParamState.VERIFIED || st == ParamState.DECODED || r.evidence.any { it.kind == EvidenceKind.BITMAP_ADVERTISED },
            requested = st == ParamState.VERIFIED || st == ParamState.DECODED,
            decoded = st == ParamState.DECODED,
            unsupported = st == ParamState.UNSUPPORTED,
            evidence = r.evidence.takeLast(3).map(::evidenceText)
        )
    }

    private val order = listOf(ParamState.DECODED, ParamState.VERIFIED, ParamState.ADVERTISED, ParamState.UNSUPPORTED)

    fun build(reg: ParameterRegistry?, discovery: DiscoveryOutcome?, profile: EcuProfile?, liveProtocol: String): ExplorerView {
        val counts = ParamState.values().associateWith { s -> reg?.byState(s)?.size ?: 0 }
        val rows = reg?.all()?.map { row(reg, it) }?.filter { it.state != ParamState.UNTESTED }
            ?.sortedWith(compareBy({ order.indexOf(it.state) }, { it.id })) ?: emptyList()
        val address = discovery?.run?.ecuAddress
        val identity = listOf(
            "ECU address (as captured)" to (address ?: "not captured yet"),
            "Protocol" to (liveProtocol.ifBlank { profile?.protocol.orEmpty() }.ifBlank { "not reported" }),
            "Mode 01 discovery" to (discovery?.let { it.status.name.lowercase() + ": " + it.message } ?: "not run in this session"),
            "Identity scan" to (profile?.let { time(it.capturedAtMs) } ?: "not run")
        )
        return ExplorerView(identity, ModuleStatusModel.derive(discovery, profile, reg), counts, rows.take(MAX_ROWS), (rows.size - MAX_ROWS).coerceAtLeast(0))
    }
}
