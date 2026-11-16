package com.sx4scanpro

/** How far the saved validation data can be trusted for export. Only [OK] and [NONE_ENTERED] describe a fully readable record. */
internal enum class ValidationDataState(val explanation: String) {
    OK("Saved results were read and every saved line was understood."),
    NONE_ENTERED("Nothing has been saved: the owner has entered no results, so every check is untested."),
    PARTIALLY_UNREADABLE("Some saved lines could not be read. Results that could be read are shown; others may be missing, so a check shown as UNTESTED may have had a result among the unreadable lines."),
    UNREADABLE("Saved validation data exists but none of it could be read. No check status is known."),
    UNAVAILABLE("The saved validation data could not be obtained, so no check status is known.");

    val fullyReadable: Boolean get() = this == OK || this == NONE_ENTERED
}

/**
 * An immutable copy of what is saved on the tablet, taken once so one export describes one moment. Built only from the saved text:
 * nothing is added, defaulted or repaired, and [records] never contains a line that could not be read.
 */
internal class ValidationSnapshot private constructor(
    val state: ValidationDataState,
    /** Readable records in saved order. When one check appears twice the last one counts, as everywhere else in the app. */
    val records: List<ValidationRecord>,
    val unreadableLines: Int,
    /** Known checks whose saved line was damaged. */
    val unreadableIds: Set<String>,
    val reason: String
) {
    companion object {
        /** [text] is the saved string exactly as stored (null or blank means nothing was ever saved). */
        fun fromSavedText(text: String?): ValidationSnapshot {
            if (text.isNullOrBlank()) return ValidationSnapshot(ValidationDataState.NONE_ENTERED, emptyList(), 0, emptySet(), "")
            val d = ValidationCodec.decodeDetailed(text)
            val state = when {
                d.unreadableLines == 0 -> ValidationDataState.OK
                d.records.isNotEmpty() -> ValidationDataState.PARTIALLY_UNREADABLE
                else -> ValidationDataState.UNREADABLE
            }
            return ValidationSnapshot(state, d.records, d.unreadableLines, d.unreadableIds, "")
        }

        /** The saved data could not be read at all (for example a storage error). */
        fun unavailable(reason: String): ValidationSnapshot =
            ValidationSnapshot(ValidationDataState.UNAVAILABLE, emptyList(), 0, emptySet(), reason.ifBlank { "unknown error" })
    }
}

/** Builds the body of validation_results.json for the existing session export ZIP. Pure: no I/O, no vehicle communication. */
internal object ValidationExport {
    const val FILE = "validation_results.json"
    const val FORMAT = "sx4-validation-results"
    const val FORMAT_VERSION = 1L
    const val NOTICE = "PASSED and FAILED entries were typed in by hand by the owner and are NOT independently verified. " +
        "The app never marks a check itself. UNTESTED means no result was entered. UNKNOWN means the saved data for that check could not be read."

    private fun entry(item: ValidationItem, s: ValidationSnapshot?): Map<String, Any?> {
        val rec = s?.records?.lastOrNull { it.id == item.id }
        val known = s != null && s.state != ValidationDataState.UNAVAILABLE && s.state != ValidationDataState.UNREADABLE && item.id !in s.unreadableIds
        val status: String
        val meaning: String
        var at: Long? = null
        var note: String? = null
        when {
            !known -> { status = "UNKNOWN"; meaning = "Unknown: the saved data for this check could not be read" }
            rec != null && rec.status != ValidationStatus.UNTESTED -> {
                status = rec.status.name; meaning = rec.status.text
                at = rec.atMs; note = rec.note.takeIf { it.isNotBlank() }
            }
            else -> { status = ValidationStatus.UNTESTED.name; meaning = ValidationStatus.UNTESTED.text }
        }
        return linkedMapOf(
            "id" to item.id, "group" to item.group, "title" to item.title,
            "status" to status, "meaning" to meaning,
            "manuallyEnteredByOwner" to (at != null),
            "enteredAtMs" to at,
            "note" to note
        )
    }

    fun body(s: ValidationSnapshot?): Map<String, Any?> {
        val state = s?.state ?: ValidationDataState.UNAVAILABLE
        val checks = ValidationPlan.items.map { entry(it, s) }
        fun n(status: String) = checks.count { it["status"] == status }.toLong()
        val dups = s?.records?.groupBy { it.id }?.filter { it.value.size > 1 }?.keys?.toList().orEmpty()
        val storedUntested = s?.records?.count { it.status == ValidationStatus.UNTESTED } ?: 0
        val notes = ArrayList<String>()
        if (dups.isNotEmpty()) notes += "More than one saved record exists for: " + dups.joinToString(", ") + ". The last one was used."
        if (storedUntested > 0) notes += "$storedUntested saved record(s) carried the status UNTESTED; they are reported as untested, without a time."
        return linkedMapOf(
            "format" to FORMAT, "formatVersion" to FORMAT_VERSION,
            "notice" to NOTICE,
            "dataState" to state.name,
            "dataStateNote" to state.explanation + (if (s == null) " The export was given no validation data." else if (s.reason.isNotBlank()) " Reason: ${s.reason}." else ""),
            "fullyReadable" to state.fullyReadable,
            "unreadableSavedLines" to (s?.unreadableLines ?: 0).toLong(),
            "unreadableCheckIds" to (s?.unreadableIds?.toList() ?: emptyList<String>()),
            "notes" to notes,
            "counts" to linkedMapOf("total" to checks.size.toLong(), "passed" to n("PASSED"), "failed" to n("FAILED"), "untested" to n("UNTESTED"), "unknown" to n("UNKNOWN")),
            "checks" to checks
        )
    }

    /** Completeness problems for the manifest. Empty only when the saved data was fully readable. */
    fun issues(s: ValidationSnapshot?): List<String> {
        if (s == null) return listOf("$FILE: no validation data was supplied to the export; every check is reported as UNKNOWN")
        return when (s.state) {
            ValidationDataState.OK, ValidationDataState.NONE_ENTERED -> emptyList()
            ValidationDataState.PARTIALLY_UNREADABLE -> listOf("$FILE: ${s.unreadableLines} saved line(s) could not be read; results may be missing")
            ValidationDataState.UNREADABLE -> listOf("$FILE: saved validation data could not be read (${s.unreadableLines} unreadable line(s)); every check is reported as UNKNOWN")
            ValidationDataState.UNAVAILABLE -> listOf("$FILE: validation data unavailable (${s.reason}); every check is reported as UNKNOWN")
        }
    }
}
