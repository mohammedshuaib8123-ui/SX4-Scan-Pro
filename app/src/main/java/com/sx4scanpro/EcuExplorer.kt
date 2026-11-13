package com.sx4scanpro

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Modules the explorer will eventually cover. Only the Mode 01 responder has ever been talked to; the others are groundwork. */
internal enum class ModuleId(val label: String) {
    ENGINE("Engine (OBD-II Mode 01 responder)"), BCM("BCM"), EPS("EPS"), ABS("ABS/ESP"), CLUSTER("Instrument cluster"), AIRBAG("SRS / Airbag")
}

/**
 * How far the evidence for a module goes, weakest to strongest (UNAVAILABLE stands apart). It is derived only from stored evidence.
 * UNAVAILABLE is part of the vocabulary but is never produced yet: not answering is not proof that a module is absent.
 */
internal enum class ModuleStatus { NOT_SCANNED, ATTEMPTED, ADVERTISED, REACHABLE, DECODED, VERIFIED, UNAVAILABLE }

/** Where the evidence behind a status comes from. Stored registry evidence has no session or ECU id, so it is only trusted when it is newer than this connection. */
internal enum class EvidenceScope { NONE, HISTORICAL, CURRENT }

internal data class ModuleEntry(
    val id: ModuleId, val status: ModuleStatus, val address: String?, val note: String,
    val scope: EvidenceScope = EvidenceScope.NONE
)

internal object ModuleStatusModel {
    /**
     * Statuses are built from evidence of the CURRENT connection only ([sinceMs] = when it started; null = not connected, so everything
     * stored is historical). The registry and the ECU profile are persisted across sessions and carry no session or ECU identity, so
     * old evidence is never combined with new evidence: VERIFIED needs a decoded answer AND an ECU identity item (VIN, calibration ID or
     * CVN) that were both recorded in this connection. Earlier-session evidence is reported as such and does not raise the status.
     * ENGINE ladder: ATTEMPTED (a discovery/scan ran in this connection) < ADVERTISED < REACHABLE (positive answer) < DECODED < VERIFIED.
     * Every other module stays NOT_SCANNED. UNAVAILABLE is never produced: silence is not proof of absence.
     */
    fun derive(discovery: DiscoveryOutcome?, profile: EcuProfile?, registry: ParameterRegistry?, sinceMs: Long? = null): List<ModuleEntry> {
        fun current(t: Long) = sinceMs != null && t >= sinceMs
        val recs = registry?.all()?.mapNotNull { registry.record(it.id) } ?: emptyList()
        fun has(r: ParamRecord, vararg kinds: EvidenceKind) = r.evidence.any { it.kind in kinds && current(it.tMs) }
        val decodedNow = recs.any { it.state == ParamState.DECODED && has(it, EvidenceKind.DECODED_OK) }
        val positiveNow = recs.any { (it.state == ParamState.VERIFIED || it.state == ParamState.DECODED) && has(it, EvidenceKind.POSITIVE_RESPONSE, EvidenceKind.DECODED_OK) }
        val advertisedNow = recs.any { it.state != ParamState.UNTESTED && it.state != ParamState.UNSUPPORTED && has(it, EvidenceKind.BITMAP_ADVERTISED) } ||
            (sinceMs != null && discovery?.run?.advertised?.isNotEmpty() == true)
        val profileNow = profile != null && current(profile.capturedAtMs)
        val identityNow = profileNow && listOf(profile!!.vin, profile.calIds, profile.cvns).any { it.status == ScanStatus.READ && it.items.isNotEmpty() }
        val profilePositiveNow = profileNow && listOf(profile!!.mode09Supported, profile.vin, profile.calIds, profile.cvns, profile.ecuName).any { it.status == ScanStatus.READ }
        val attemptedNow = sinceMs != null && ((discovery != null && discovery.status != DiscoveryStatus.REFUSED) || profileNow)
        val anyStored = recs.any { it.state != ParamState.UNTESTED } || profile != null
        val anyNow = decodedNow || positiveNow || advertisedNow || profileNow || attemptedNow

        val status = when {
            decodedNow && identityNow -> ModuleStatus.VERIFIED
            decodedNow -> ModuleStatus.DECODED
            positiveNow || profilePositiveNow -> ModuleStatus.REACHABLE
            advertisedNow -> ModuleStatus.ADVERTISED
            attemptedNow -> ModuleStatus.ATTEMPTED
            else -> ModuleStatus.NOT_SCANNED
        }
        val scope = when { anyNow -> EvidenceScope.CURRENT; anyStored -> EvidenceScope.HISTORICAL; else -> EvidenceScope.NONE }
        val note = buildString {
            when (status) {
                ModuleStatus.NOT_SCANNED -> append(if (anyStored) "Nothing recorded in this connection yet." else "Not scanned in this app yet.")
                ModuleStatus.ATTEMPTED -> append("A scan was attempted in this connection; no usable answer is recorded. This does not mean the ECU is absent.")
                else -> append("From this connection's evidence. It answers OBD-II Mode 01 requests; that it is the engine ECU is assumed, not verified.")
            }
            if (status != ModuleStatus.VERIFIED && decodedNow) append(" Verification unavailable: no ECU identity (VIN, calibration ID or CVN) was read in this connection.")
            if (status != ModuleStatus.VERIFIED && !decodedNow && identityNow) append(" Verification unavailable: no decoded reply was recorded in this connection.")
            if (anyStored && !(decodedNow && identityNow)) {
                val oldDecoded = recs.any { it.state == ParamState.DECODED && !has(it, EvidenceKind.DECODED_OK) }
                val oldIdentity = profile != null && !profileNow
                if (oldDecoded || oldIdentity || sinceMs == null) append(" Stored evidence from an earlier session exists and is not combined with this connection.")
            }
        }
        val engine = ModuleEntry(ModuleId.ENGINE, status, if (sinceMs != null) discovery?.run?.ecuAddress else null, note, scope)
        val others = listOf(ModuleId.BCM, ModuleId.EPS, ModuleId.ABS, ModuleId.CLUSTER, ModuleId.AIRBAG).map {
            ModuleEntry(it, ModuleStatus.NOT_SCANNED, null, "Not scanned. This version sends no requests to this module; it may or may not exist on this car. " + ModuleCatalog.summary(it) + " Needs vehicle confirmation.")
        }
        return listOf(engine) + others
    }
}

internal data class ParamRow(
    val id: String, val name: String, val state: ParamState, val meaning: String,
    val advertised: Boolean, val requested: Boolean, val decoded: Boolean, val unsupported: Boolean, val evidence: List<String>,
    /** CURRENT only when its newest evidence is from this connection; otherwise HISTORICAL (an earlier session or app run). */
    val scope: EvidenceScope = EvidenceScope.HISTORICAL
)

internal data class ExplorerView(
    val identity: List<Pair<String, String>>,
    val modules: List<ModuleEntry>,
    val counts: Map<ParamState, Int>,
    val params: List<ParamRow>,
    /** Parameters with a recorded state that are not listed because of the display cap. */
    val hidden: Int,
    /** Plain statement of where the shown evidence comes from; never blank. */
    val provenance: String = "",
    val historicalCount: Int = 0,
    val capabilities: CapabilitySummary? = null
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

    fun row(reg: ParameterRegistry, d: ParamDef, sinceMs: Long? = null): ParamRow {
        val r = reg.record(d.id) ?: ParamRecord(d.id)
        val st = r.state
        return ParamRow(
            d.id, d.name, st, meaning(st, r.evidence),
            advertised = st == ParamState.ADVERTISED || st == ParamState.VERIFIED || st == ParamState.DECODED || r.evidence.any { it.kind == EvidenceKind.BITMAP_ADVERTISED },
            requested = st == ParamState.VERIFIED || st == ParamState.DECODED,
            decoded = st == ParamState.DECODED,
            unsupported = st == ParamState.UNSUPPORTED,
            evidence = r.evidence.takeLast(3).map(::evidenceText),
            scope = if (sinceMs != null && (r.evidence.maxOfOrNull { it.tMs } ?: -1L) >= sinceMs) EvidenceScope.CURRENT else EvidenceScope.HISTORICAL
        )
    }

    private val order = listOf(ParamState.DECODED, ParamState.VERIFIED, ParamState.ADVERTISED, ParamState.UNSUPPORTED)

    fun build(reg: ParameterRegistry?, discovery: DiscoveryOutcome?, profile: EcuProfile?, liveProtocol: String, sinceMs: Long? = null): ExplorerView {
        val counts = ParamState.values().associateWith { s -> reg?.byState(s)?.size ?: 0 }
        val rows = reg?.all()?.map { row(reg, it, sinceMs) }?.filter { it.state != ParamState.UNTESTED }
            ?.sortedWith(compareBy({ order.indexOf(it.state) }, { it.id })) ?: emptyList()
        val address = discovery?.run?.ecuAddress
        val identity = listOf(
            "ECU address (as captured)" to (address ?: "not captured yet"),
            "Protocol" to (liveProtocol.ifBlank { profile?.protocol.orEmpty() }.ifBlank { "not reported" }),
            "Mode 01 discovery" to (discovery?.let { it.status.name.lowercase() + ": " + it.message } ?: "not run in this session"),
            "Identity scan" to (profile?.let { time(it.capturedAtMs) + (if (sinceMs != null && it.capturedAtMs >= sinceMs) " (this connection)" else " (earlier session, not this connection)") } ?: "not run")
        )
        val historical = rows.count { it.scope == EvidenceScope.HISTORICAL }
        val provenance = when {
            rows.isEmpty() -> "Nothing recorded yet."
            sinceMs == null -> "Not connected: everything below is stored evidence from earlier sessions."
            historical == 0 -> "All entries below were recorded in this connection."
            historical == rows.size -> "Connected, but every entry below is from an earlier session and is not confirmed in this connection yet."
            else -> "$historical of ${rows.size} entries below are from earlier sessions (marked); the rest were recorded in this connection."
        }
        return ExplorerView(identity, ModuleStatusModel.derive(discovery, profile, reg, sinceMs), counts, rows.take(MAX_ROWS), (rows.size - MAX_ROWS).coerceAtLeast(0), provenance, historical, CapabilityCatalogue.summarize(reg, profile, sinceMs))
    }
}
