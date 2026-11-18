package com.sx4scanpro

/**
 * What one DTC service (03 stored, 07 pending, 0A permanent) really delivered. Every state is kept apart so the app can
 * never turn "the ECU did not answer" into "no faults".
 */
internal enum class DtcModeStatus {
    /** Valid positive reply carrying at least one code. */
    CODES_REPORTED,
    /** Valid positive reply that lists no codes (the only state that means "the ECU reports none"). */
    VALID_EMPTY,
    /** Some reply lines were valid and some could not be read: the code list may be incomplete. */
    PARTIAL,
    /** The adapter answered NO DATA: the ECU gave no information for this service. */
    NO_DATA,
    /** The ECU answered with a negative response (7F): the service is not supported. */
    UNSUPPORTED,
    /** A reply arrived but nothing in it could be interpreted (or it was empty). */
    MALFORMED,
    /** Timeout, adapter error, lost link or blocked request: nothing was received. */
    TRANSPORT_FAILURE;

    /** The ECU delivered a usable positive answer. */
    val answered: Boolean get() = this == CODES_REPORTED || this == VALID_EMPTY || this == PARTIAL
}

/** One DTC as reported by the ECU, tied to the service that returned it. */
internal data class ReportedDtc(val category: String, val code: String)

/** Result of one service request with its raw evidence. [raw] is the verbatim reply (or the error text for a transport failure). */
internal data class DtcModeScan(
    val mode: String,
    val category: String,
    val status: DtcModeStatus,
    val codes: List<String>,
    val unreadable: List<String>,
    val raw: String,
    val detail: String = ""
) {
    val label: String get() = "Mode $mode ($category)"
}

/** A complete engine-ECU DTC scan: three services, each with its own result. */
internal data class DtcScanReport(
    val capturedAtMs: Long,
    val protocol: String,
    val modes: List<DtcModeScan>,
    /** One-off Mode 01 PID 01 read taken with the scan (null = not part of this scan). */
    val monitor: MonitorStatus? = null
) {
    /** Every code the ECU returned, per service, in service order, without duplicates inside a service. */
    val codes: List<ReportedDtc> get() = modes.flatMap { m -> m.codes.distinct().map { ReportedDtc(m.category, it) } }

    val answeredModes: Int get() = modes.count { it.status.answered }

    /** All three services answered and every reply line was readable. */
    val isComplete: Boolean get() = modes.isNotEmpty() && modes.all { it.status.answered && it.status != DtcModeStatus.PARTIAL }

    /** At least one service gave a usable answer, so the scan carries information. */
    val hasAnyAnswer: Boolean get() = answeredModes > 0

    /**
     * True only when every service answered, every line was readable and no code was returned. Even then it covers the
     * engine ECU's generic OBD-II services only, never the whole vehicle.
     */
    val engineEcuReportsNoCodes: Boolean get() = isComplete && codes.isEmpty()

    fun mode(mode: String): DtcModeScan? = modes.firstOrNull { it.mode == mode }

    /** One-line headline for the scan screen. Never claims whole-vehicle coverage. */
    fun headline(): String = when {
        !hasAnyAnswer ->
            "The engine ECU gave no valid answer to the fault-code requests (03 / 07 / 0A). This scan tells you nothing about stored faults."
        engineEcuReportsNoCodes ->
            "The engine ECU answered all three fault-code requests and reported no stored, pending or permanent codes. Other modules were not scanned."
        codes.isNotEmpty() && isComplete ->
            "${codes.size} code(s) reported by the engine ECU. Other modules were not scanned."
        codes.isNotEmpty() ->
            "${codes.size} code(s) reported, but the scan is incomplete (see each service below). Other modules were not scanned."
        else ->
            "Incomplete scan: ${answeredModes} of ${modes.size} fault-code services answered with no codes, so absence of codes is NOT confirmed. Other modules were not scanned."
    }

    companion object {
        const val SCOPE = "Engine ECU only, generic OBD-II services 03 (stored), 07 (pending) and 0A (permanent). BCM, ABS, airbag, immobilizer and other modules were not queried."
        val SERVICES = listOf("03" to "STORED", "07" to "PENDING", "0A" to "PERMANENT")
    }
}

internal object DtcScanClassifier {
    /** Turns one verbatim reply into a [DtcModeScan]. Pure: the same bytes always give the same state. */
    fun classify(mode: String, category: String, raw: String, framing: Framing): DtcModeScan {
        val read = ObdTroubleCodeDecoder.decodeResponse(raw, mode, framing)
        val status = when {
            read.validFrames > 0 && read.unreadable.isNotEmpty() -> DtcModeStatus.PARTIAL
            read.validFrames > 0 && read.codes.isNotEmpty() -> DtcModeStatus.CODES_REPORTED
            read.validFrames > 0 -> DtcModeStatus.VALID_EMPTY
            read.unsupported -> DtcModeStatus.UNSUPPORTED
            read.noData && read.unreadable.isEmpty() -> DtcModeStatus.NO_DATA
            else -> DtcModeStatus.MALFORMED
        }
        val detail = when (status) {
            DtcModeStatus.PARTIAL -> "${read.unreadable.size} reply line(s) could not be read"
            DtcModeStatus.MALFORMED -> if (raw.isBlank()) "empty reply" else "reply could not be interpreted"
            else -> ""
        }
        return DtcModeScan(mode, category, status, read.codes, read.unreadable, raw, detail)
    }

    fun failure(mode: String, category: String, reason: String): DtcModeScan =
        DtcModeScan(mode, category, DtcModeStatus.TRANSPORT_FAILURE, emptyList(), emptyList(), "", reason)
}

/** Wording for each state, shared by the screen and the report. */
internal object DtcScanText {
    fun status(s: DtcModeStatus): String = when (s) {
        DtcModeStatus.CODES_REPORTED -> "Codes reported"
        DtcModeStatus.VALID_EMPTY -> "Valid reply, no codes"
        DtcModeStatus.PARTIAL -> "Partly readable, list may be incomplete"
        DtcModeStatus.NO_DATA -> "No reply from ECU (NO DATA) - no information"
        DtcModeStatus.UNSUPPORTED -> "Service not supported by the ECU"
        DtcModeStatus.MALFORMED -> "Reply could not be read"
        DtcModeStatus.TRANSPORT_FAILURE -> "Adapter or link failure - nothing received"
    }

    fun modeLine(m: DtcModeScan): String =
        m.label + ": " + status(m.status) + (if (m.status == DtcModeStatus.CODES_REPORTED || m.status == DtcModeStatus.PARTIAL) " (${m.codes.size})" else "") +
            (if (m.detail.isNotBlank()) " - ${m.detail}" else "")
}
