package com.sx4scanpro

/** One real-car check the owner runs and records. The app never marks one passed by itself: only a person with the car can. */
internal data class ValidationItem(val id: String, val group: String, val title: String, val how: String)

internal enum class ValidationStatus(val text: String) { UNTESTED("Not yet tested on the car"), PASSED("Passed on the car"), FAILED("Failed on the car") }

internal data class ValidationRecord(val id: String, val status: ValidationStatus, val atMs: Long, val note: String = "")

/** Operating conditions the owner can tag in the session log so exported evidence says what the car was doing. */
internal enum class ValidationPhase(val label: String) {
    COLD_START("Cold start"), IDLE("Idle"), WARM_UP("Warm-up"), STEADY("Steady RPM/load"), ACCELERATION("Acceleration"), IGNITION_OFF_ON("Ignition OFF/ON"), ENDED("End of test")
}

internal object ValidationPlan {
    val items: List<ValidationItem> = listOf(
        ValidationItem("LIVE_DATA", "Connection and live data", "Connect and watch live gauges", "Connect; RPM, speed, coolant and boost update and show -- when the engine is off."),
        ValidationItem("IGN_OFF_ON", "Connection and live data", "Ignition OFF then ON, adapter stays powered", "Turn the ignition off, wait, turn it on; gauges return without reconnecting by hand."),
        ValidationItem("ADAPTER_LOSS", "Connection and live data", "Adapter power loss and reconnect", "Unplug the adapter, plug it back; the app reconnects and the trip stays paused, not reset."),
        ValidationItem("FRESHNESS", "Connection and live data", "Stale readings turn to --", "Stop the engine: values fade to -- instead of staying on screen as if live."),
        ValidationItem("TRIP_SWITCH", "Trip computer", "App switching keeps recording", "Start a trip, use another app for a minute, come back; elapsed time and distance continued."),
        ValidationItem("TRIP_SCREEN_OFF", "Trip computer", "Screen off and on", "Lock the tablet while driving a short stretch; the trip continues or shows a clear paused reason."),
        ValidationItem("TRIP_SWIPE", "Trip computer", "Swipe the app away while connected", "Swipe from recents; the notification stays and the trip keeps recording; reopening shows the same trip."),
        ValidationItem("TRIP_STOP", "Trip computer", "Notification Stop with the app closed", "Tap Stop in the notification; the adapter session and notification end and the trip is saved once."),
        ValidationItem("TRIP_KILL", "Trip computer", "Process kill restore", "Force-stop the app; reopening restores the saved trip as paused with a reason."),
        ValidationItem("DTC_SCAN", "Diagnostics", "Fault-code scan shows every code", "Run the engine scan; each service shows its real result and any codes with explanations."),
        ValidationItem("FREEZE_FRAME", "Diagnostics", "Freeze frame shows real values", "Open freeze frame; values are real or a stated reason, never zeros."),
        ValidationItem("ECU_INFO", "Diagnostics", "ECU info reads VIN / calibration / CVN", "Run the ECU scan; fields show decoded values or a precise failure."),
        ValidationItem("REPORT", "Diagnostics", "Full diagnosis report exports", "Share the report; it lists codes, freeze frame, ECU info, rules and limitations."),
        ValidationItem("SESSION_EXPORT", "Diagnostics", "Session evidence export", "Export the session zip and open it on a computer."),
        ValidationItem("DATA_CHECK", "Data quality", "Data check stays quiet on a healthy drive", "Drive normally; no false sensor or rail-pressure alarms."),
        ValidationItem("AUTO_ECU", "Data quality", "Automatic ECU identity scan at connect", "Fresh connect: identity is read once without delaying live data.")
    )

    fun find(id: String) = items.firstOrNull { it.id == id }

    fun statusOf(records: List<ValidationRecord>, id: String): ValidationStatus = records.lastOrNull { it.id == id }?.status ?: ValidationStatus.UNTESTED

    /** Replaces any earlier result for the same check. Unknown ids are ignored. */
    fun record(records: List<ValidationRecord>, id: String, status: ValidationStatus, atMs: Long, note: String = ""): List<ValidationRecord> =
        if (find(id) == null) records else records.filter { it.id != id } + ValidationRecord(id, status, atMs, note)

    fun logLine(phase: ValidationPhase) = "validation phase: ${phase.name} (${phase.label})"

    /** Report section. Only owner-recorded results are PASSED or FAILED; everything else says it was not tested on the car. */
    fun reportLines(records: List<ValidationRecord>): List<String> = buildList {
        val passed = items.count { statusOf(records, it.id) == ValidationStatus.PASSED }
        val failed = items.count { statusOf(records, it.id) == ValidationStatus.FAILED }
        add("  Recorded by the owner on the target car; the app never marks a check itself. $passed passed, $failed failed, ${items.size - passed - failed} not yet tested.")
        items.groupBy { it.group }.forEach { (g, list) ->
            add("  $g")
            list.forEach { it2 ->
                val r = records.lastOrNull { x -> x.id == it2.id }
                val st = r?.status ?: ValidationStatus.UNTESTED
                add("    ${it2.title}: ${st.text}" + (r?.takeIf { x -> x.status != ValidationStatus.UNTESTED }?.let { x -> " (" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(x.atMs)) + ")" } ?: "") +
                    (if (!r?.note.isNullOrBlank()) " - ${r!!.note}" else ""))
            }
        }
    }
}

/** Small tolerant text format for SharedPreferences: id|STATUS|atMs|note. A bad line is skipped, never guessed. */
internal object ValidationCodec {
    fun encode(records: List<ValidationRecord>): String = records.joinToString("\n") { "${it.id}|${it.status.name}|${it.atMs}|${it.note.replace('\n', ' ').replace('|', '/')}" }

    fun decode(text: String?): List<ValidationRecord> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size < 4) return@mapNotNull null
            val st = ValidationStatus.values().firstOrNull { it.name == p[1] } ?: return@mapNotNull null
            val t = p[2].toLongOrNull() ?: return@mapNotNull null
            if (ValidationPlan.find(p[0]) == null) null else ValidationRecord(p[0], st, t, p[3])
        }
    }
}
