package com.sx4scanpro

/** Playback and import states for the Safar Ki Dua button. Pure Kotlin so the rules can be unit-tested. */
internal enum class DuaState {
    NO_FILE, IMPORTING, IMPORT_FAILED, IDLE, PLAYING, PAUSED, FINISHED, ERROR, FOCUS_DENIED
}

/** What the Android layer reports after the owner asks to play. */
internal enum class PlayOutcome { FOCUS_DENIED, PREPARE_FAILED, START_FAILED, STARTED }

internal object DuaPlayback {
    /** A saved copy means the app opens ready to play. Nothing starts playing on launch. */
    fun initial(hasSavedFile: Boolean): DuaState = if (hasSavedFile) DuaState.IDLE else DuaState.NO_FILE

    /**
     * Brings the state in line with the disk when the screen returns to the foreground.
     * PLAYING is never kept when there is no saved file, and an import in progress is left alone.
     */
    fun reconcile(state: DuaState, hasSavedFile: Boolean): DuaState = when {
        state == DuaState.IMPORTING -> state
        !hasSavedFile -> DuaState.NO_FILE
        state == DuaState.NO_FILE || state == DuaState.IMPORT_FAILED -> DuaState.IDLE
        else -> state
    }

    /** A tap opens the file browser when there is no file yet, or when the last import failed. */
    fun opensPicker(state: DuaState): Boolean = state == DuaState.NO_FILE || state == DuaState.IMPORT_FAILED

    /** REMOVE is offered only when a saved copy exists. */
    fun showsRemove(state: DuaState): Boolean = when (state) {
        DuaState.NO_FILE, DuaState.IMPORTING, DuaState.IMPORT_FAILED -> false
        else -> true
    }

    /** Manual taps only. Taps during an import or with no file do not change playback. */
    fun onTap(state: DuaState): DuaState = when (state) {
        DuaState.NO_FILE, DuaState.IMPORTING, DuaState.IMPORT_FAILED -> state
        DuaState.PLAYING -> DuaState.PAUSED
        DuaState.IDLE, DuaState.PAUSED, DuaState.FINISHED, DuaState.ERROR, DuaState.FOCUS_DENIED -> DuaState.PLAYING
    }

    /** Only a verified, installed copy moves to IDLE. Every other result shows a retry state. */
    fun afterImport(result: ImportResult): DuaState =
        if (result == ImportResult.OK) DuaState.IDLE else DuaState.IMPORT_FAILED

    fun afterRemove(): DuaState = DuaState.NO_FILE

    /** PLAYING is reported only when start() actually succeeded. */
    fun afterPlay(outcome: PlayOutcome): DuaState = when (outcome) {
        PlayOutcome.STARTED -> DuaState.PLAYING
        PlayOutcome.FOCUS_DENIED -> DuaState.FOCUS_DENIED
        PlayOutcome.PREPARE_FAILED, PlayOutcome.START_FAILED -> DuaState.ERROR
    }

    fun afterCompletion(): DuaState = DuaState.FINISHED

    fun afterError(): DuaState = DuaState.ERROR

    /** Leaving the foreground, losing audio focus, releasing the player, or a manual pause. Nothing resumes by itself. */
    fun pauseOnInterrupt(state: DuaState): DuaState = if (state == DuaState.PLAYING) DuaState.PAUSED else state

    fun label(state: DuaState): String = when (state) {
        DuaState.NO_FILE -> "TAP TO ADD YOUR AUDIO FILE"
        DuaState.IMPORTING -> "CHECKING AND ADDING AUDIO…"
        DuaState.IMPORT_FAILED -> "COULD NOT ADD THAT FILE · TAP TO CHOOSE AGAIN"
        DuaState.IDLE -> "TAP TO PLAY"
        DuaState.PLAYING -> "PLAYING · TAP TO PAUSE"
        DuaState.PAUSED -> "PAUSED · TAP TO RESUME"
        DuaState.FINISHED -> "TAP TO REPLAY"
        DuaState.ERROR -> "COULD NOT PLAY · TAP TO RETRY"
        DuaState.FOCUS_DENIED -> "OTHER APP HAS AUDIO · TAP TO RETRY"
    }
}
