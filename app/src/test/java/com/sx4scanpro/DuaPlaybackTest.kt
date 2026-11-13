package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuaPlaybackTest {
    // Launch state: a saved copy opens ready, nothing plays on its own
    @Test fun savedFileOpensIdle() = assertEquals(DuaState.IDLE, DuaPlayback.initial(true))
    @Test fun noSavedFileOpensNoFile() = assertEquals(DuaState.NO_FILE, DuaPlayback.initial(false))
    @Test fun nothingPlaysOnLaunch() = assertNotEquals(DuaState.PLAYING, DuaPlayback.initial(true))

    // Add-file flow
    @Test fun tapWithNoFileOpensPicker() = assertTrue(DuaPlayback.opensPicker(DuaState.NO_FILE))
    @Test fun tapAfterFailedImportOpensPickerAgain() = assertTrue(DuaPlayback.opensPicker(DuaState.IMPORT_FAILED))
    @Test fun tapWithSavedFileDoesNotOpenPicker() {
        val withFile = listOf(
            DuaState.IDLE, DuaState.PLAYING, DuaState.PAUSED, DuaState.FINISHED, DuaState.ERROR, DuaState.FOCUS_DENIED
        )
        for (state in withFile) {
            assertFalse(DuaPlayback.opensPicker(state))
        }
    }
    @Test fun tapWithNoFileKeepsNoFile() = assertEquals(DuaState.NO_FILE, DuaPlayback.onTap(DuaState.NO_FILE))
    @Test fun tapDuringImportIsIgnored() = assertEquals(DuaState.IMPORTING, DuaPlayback.onTap(DuaState.IMPORTING))
    @Test fun verifiedImportIsIdleNotPlaying() = assertEquals(DuaState.IDLE, DuaPlayback.afterImport(ImportResult.OK))
    @Test fun rejectedImportShowsImportFailed() = assertEquals(DuaState.IMPORT_FAILED, DuaPlayback.afterImport(ImportResult.REJECTED))
    @Test fun failedImportShowsImportFailed() = assertEquals(DuaState.IMPORT_FAILED, DuaPlayback.afterImport(ImportResult.FAILED))
    @Test fun removeReturnsToNoFile() = assertEquals(DuaState.NO_FILE, DuaPlayback.afterRemove())

    @Test fun removeIsOfferedOnlyWhenAFileIsSaved() {
        val withoutFile = setOf(DuaState.NO_FILE, DuaState.IMPORTING, DuaState.IMPORT_FAILED)
        for (state in DuaState.values()) {
            assertEquals(!withoutFile.contains(state), DuaPlayback.showsRemove(state))
        }
    }

    // Manual taps with a saved file
    @Test fun tapStartsFromIdle() = assertEquals(DuaState.PLAYING, DuaPlayback.onTap(DuaState.IDLE))
    @Test fun tapPausesWhilePlaying() = assertEquals(DuaState.PAUSED, DuaPlayback.onTap(DuaState.PLAYING))
    @Test fun tapResumesWhenPaused() = assertEquals(DuaState.PLAYING, DuaPlayback.onTap(DuaState.PAUSED))
    @Test fun tapReplaysAfterFinishing() = assertEquals(DuaState.PLAYING, DuaPlayback.onTap(DuaState.FINISHED))
    @Test fun tapRetriesAfterError() = assertEquals(DuaState.PLAYING, DuaPlayback.onTap(DuaState.ERROR))
    @Test fun tapRetriesAfterFocusDenied() = assertEquals(DuaState.PLAYING, DuaPlayback.onTap(DuaState.FOCUS_DENIED))

    // Play outcomes: PLAYING only when start() succeeded
    @Test fun focusDeniedDoesNotPlay() = assertEquals(DuaState.FOCUS_DENIED, DuaPlayback.afterPlay(PlayOutcome.FOCUS_DENIED))
    @Test fun prepareFailureIsAnError() = assertEquals(DuaState.ERROR, DuaPlayback.afterPlay(PlayOutcome.PREPARE_FAILED))
    @Test fun startFailureIsAnErrorNotPlaying() = assertEquals(DuaState.ERROR, DuaPlayback.afterPlay(PlayOutcome.START_FAILED))
    @Test fun onlySuccessfulStartReportsPlaying() {
        for (outcome in PlayOutcome.values()) {
            assertEquals(outcome == PlayOutcome.STARTED, DuaPlayback.afterPlay(outcome) == DuaState.PLAYING)
        }
    }

    // Completion and errors
    @Test fun completionFinishes() = assertEquals(DuaState.FINISHED, DuaPlayback.afterCompletion())
    @Test fun errorReportsError() = assertEquals(DuaState.ERROR, DuaPlayback.afterError())

    // Interruptions and release: pause, never resume
    @Test fun interruptPausesOnlyWhilePlaying() {
        assertEquals(DuaState.PAUSED, DuaPlayback.pauseOnInterrupt(DuaState.PLAYING))
        assertEquals(DuaState.FINISHED, DuaPlayback.pauseOnInterrupt(DuaState.FINISHED))
        assertEquals(DuaState.IDLE, DuaPlayback.pauseOnInterrupt(DuaState.IDLE))
    }
    @Test fun interruptNeverProducesPlaying() {
        for (state in DuaState.values()) {
            assertNotEquals(DuaState.PLAYING, DuaPlayback.pauseOnInterrupt(state))
        }
    }
    @Test fun pausedStaysPausedWithoutATap() = assertEquals(DuaState.PAUSED, DuaPlayback.pauseOnInterrupt(DuaState.PAUSED))

    // Resume reconciliation: the disk decides, playback never restarts by itself
    @Test fun reconcileWithoutFileNeverLeavesPlaying() {
        for (state in DuaState.values()) {
            assertNotEquals(DuaState.PLAYING, DuaPlayback.reconcile(state, false))
        }
    }
    @Test fun reconcileWithoutFileGoesToNoFile() = assertEquals(DuaState.NO_FILE, DuaPlayback.reconcile(DuaState.PLAYING, false))
    @Test fun reconcileKeepsAnImportInProgress() = assertEquals(DuaState.IMPORTING, DuaPlayback.reconcile(DuaState.IMPORTING, true))
    @Test fun reconcileFindsTheSavedFileAfterAFailedImport() = assertEquals(DuaState.IDLE, DuaPlayback.reconcile(DuaState.IMPORT_FAILED, true))
    @Test fun reconcileKeepsStateWhenFileIsPresent() = assertEquals(DuaState.PAUSED, DuaPlayback.reconcile(DuaState.PAUSED, true))
    @Test fun reconcileNeverStartsPlayback() {
        for (state in DuaState.values()) {
            if (state != DuaState.PLAYING) assertNotEquals(DuaState.PLAYING, DuaPlayback.reconcile(state, true))
        }
    }

    // Labels
    @Test fun everyStateHasALabel() {
        for (state in DuaState.values()) {
            assertTrue(DuaPlayback.label(state).isNotBlank())
        }
    }
    @Test fun noFileLabelAsksForTheFile() = assertTrue(DuaPlayback.label(DuaState.NO_FILE).contains("ADD"))
    @Test fun focusDeniedLabelOffersRetry() = assertTrue(DuaPlayback.label(DuaState.FOCUS_DENIED).contains("RETRY"))
}
