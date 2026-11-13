package com.sx4scanpro

/**
 * P10 capability catalogue. It answers one question per parameter, with two deliberately separate answers:
 *  - [documented]: does this app carry a documented definition/decoder for it (documentation evidence only);
 *  - [observed]: what the vehicle itself has shown, taken ONLY from the registry's recorded evidence.
 * Documentation never raises [observed]. A parameter nobody has seen the car answer stays UNVERIFIED however well documented.
 * Nothing here talks to the adapter.
 */
internal enum class ObservedLevel {
    /** No vehicle evidence at all. */
    UNVERIFIED,
    /** The ECU's support bitmap lists it; it has not been requested successfully. */
    ADVERTISED,
    /** The ECU answered a real request; the reply is not decoded. */
    TESTED,
    /** A validated decoder interpreted a real reply. */
    DECODED,
    /** It was requested and the newest evidence is NO DATA or a timeout: not usable now, but not proof of non-support. */
    UNAVAILABLE,
    /** Explicit evidence of non-support (negative response, or not in the ECU's bitmap). */
    UNSUPPORTED
}

internal data class Capability(
    val id: String, val name: String,
    val documented: Boolean, val documentationSource: String,
    val observed: ObservedLevel, val scope: EvidenceScope, val lastEvidenceMs: Long?
)

/** What the last identity scan produced for one identity item. [available] is true only when real decoded values exist. */
internal data class IdentityEvidence(
    val item: String, val status: ScanStatus, val available: Boolean, val rawCaptured: Boolean,
    val scannedAtMs: Long?, val scope: EvidenceScope, val valueCount: Int
)

internal data class CapabilitySummary(
    val capabilities: List<Capability>, val identity: List<IdentityEvidence>,
    val counts: Map<ObservedLevel, Int>, val documentedCount: Int,
    /** Documented parameters the vehicle has not been seen to answer. */
    val documentedButUnverified: Int
)

internal object CapabilityCatalogue {
    private val inconclusive = setOf(EvidenceKind.NO_DATA, EvidenceKind.TIMEOUT)

    fun classify(state: ParamState, evidence: List<Evidence>): ObservedLevel {
        val newest = evidence.lastOrNull()
        val failedLast = newest != null && newest.kind in inconclusive
        // The newest evidence decides availability: a later NO DATA/timeout reports UNAVAILABLE even if the registry state is
        // VERIFIED/DECODED. The registry state and its earlier positive evidence are untouched; a later success restores the level.
        return when (state) {
            ParamState.UNSUPPORTED -> ObservedLevel.UNSUPPORTED
            ParamState.DECODED -> if (failedLast) ObservedLevel.UNAVAILABLE else ObservedLevel.DECODED
            ParamState.VERIFIED -> if (failedLast) ObservedLevel.UNAVAILABLE else ObservedLevel.TESTED
            ParamState.ADVERTISED -> if (failedLast) ObservedLevel.UNAVAILABLE else ObservedLevel.ADVERTISED
            ParamState.UNTESTED -> if (failedLast) ObservedLevel.UNAVAILABLE else ObservedLevel.UNVERIFIED
        }
    }

    fun capability(reg: ParameterRegistry, d: ParamDef, sinceMs: Long?): Capability {
        val r = reg.record(d.id) ?: ParamRecord(d.id)
        val last = r.evidence.maxOfOrNull { it.tMs }
        val level = classify(r.state, r.evidence)
        val scope = when {
            last == null -> EvidenceScope.NONE
            sinceMs != null && last >= sinceMs -> EvidenceScope.CURRENT
            else -> EvidenceScope.HISTORICAL
        }
        return Capability(d.id, d.name, d.hasDecoder, d.source.ifBlank { "none" }, level, scope, last)
    }

    private fun identity(item: String, f: ProfileField?, at: Long?, sinceMs: Long?): IdentityEvidence {
        if (f == null || at == null) return IdentityEvidence(item, ScanStatus.NOT_ATTEMPTED, false, false, null, EvidenceScope.NONE, 0)
        val scope = if (sinceMs != null && at >= sinceMs) EvidenceScope.CURRENT else EvidenceScope.HISTORICAL
        val values = f.items.filter { it.isNotBlank() }
        return IdentityEvidence(item, f.status, f.status == ScanStatus.READ && values.isNotEmpty(), f.rawHex.isNotBlank(), at, scope, values.size)
    }

    fun identityEvidence(profile: EcuProfile?, sinceMs: Long?): List<IdentityEvidence> {
        val at = profile?.capturedAtMs
        return listOf(
            identity("VIN (09/02)", profile?.vin, at, sinceMs),
            identity("Calibration ID (09/04)", profile?.calIds, at, sinceMs),
            identity("CVN (09/06)", profile?.cvns, at, sinceMs)
        )
    }

    fun summarize(reg: ParameterRegistry?, profile: EcuProfile?, sinceMs: Long?): CapabilitySummary {
        val caps = reg?.all()?.map { capability(reg, it, sinceMs) } ?: emptyList()
        val counts = ObservedLevel.values().associateWith { l -> caps.count { it.observed == l } }
        return CapabilitySummary(
            caps, identityEvidence(profile, sinceMs), counts,
            documentedCount = caps.count { it.documented },
            documentedButUnverified = caps.count { it.documented && it.observed == ObservedLevel.UNVERIFIED }
        )
    }
}
