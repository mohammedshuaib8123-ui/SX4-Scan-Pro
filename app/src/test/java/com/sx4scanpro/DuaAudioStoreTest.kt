package com.sx4scanpro

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuaAudioStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val name = DuaAudioStore.FILE_NAME
    private val tempName = DuaAudioStore.FILE_NAME + DuaAudioStore.TEMP_SUFFIX
    private val backupName = DuaAudioStore.FILE_NAME + DuaAudioStore.BACKUP_SUFFIX

    private fun stream(text: String): InputStream = ByteArrayInputStream(text.toByteArray())

    private fun seed(dir: File, fileName: String, text: String) {
        dir.mkdirs()
        File(dir, fileName).writeText(text)
    }

    private fun read(dir: File): String = File(dir, name).readText()

    // Basic import and persistence

    @Test fun importedCopySurvivesANewStoreInstance() {
        val dir = folder.newFolder("dua")
        assertEquals(ImportResult.OK, DuaAudioStore(dir).importFrom(stream("clip")) { true })
        // A fresh instance stands in for the app restarting.
        assertTrue(DuaAudioStore(dir).exists())
    }

    @Test fun removeDeletesEverythingAndIsRepeatable() {
        val dir = folder.newFolder("dua")
        val store = DuaAudioStore(dir)
        assertEquals(ImportResult.OK, store.importFrom(stream("clip")) { true })
        store.remove()
        store.remove()
        assertFalse(store.exists())
        assertFalse(File(dir, backupName).exists())
    }

    @Test fun removalSurvivesARestart() {
        val dir = folder.newFolder("dua")
        assertEquals(ImportResult.OK, DuaAudioStore(dir).importFrom(stream("clip")) { true })
        DuaAudioStore(dir).remove()
        assertFalse(DuaAudioStore(dir).exists())
    }

    // Validation and copy failures never touch a valid saved copy

    @Test fun emptyCopyIsRejected() {
        val dir = folder.newFolder("dua")
        assertEquals(ImportResult.REJECTED, DuaAudioStore(dir).importFrom(stream("")) { true })
        assertFalse(DuaAudioStore(dir).exists())
    }

    @Test fun rejectedValidationKeepsExistingCopy() {
        val dir = folder.newFolder("dua")
        val store = DuaAudioStore(dir)
        assertEquals(ImportResult.OK, store.importFrom(stream("first")) { true })
        assertEquals(ImportResult.REJECTED, store.importFrom(stream("second")) { false })
        assertEquals("first", read(dir))
        assertFalse(File(dir, tempName).exists())
    }

    @Test fun throwingValidatorIsRejectedAndKeepsExistingCopy() {
        val dir = folder.newFolder("dua")
        val store = DuaAudioStore(dir)
        assertEquals(ImportResult.OK, store.importFrom(stream("first")) { true })
        val result = store.importFrom(stream("second")) { throw IOException("probe failed") }
        assertEquals(ImportResult.REJECTED, result)
        assertEquals("first", read(dir))
    }

    @Test fun copyFailureKeepsExistingCopyAndLeavesNoTemp() {
        val dir = folder.newFolder("dua")
        val store = DuaAudioStore(dir)
        assertEquals(ImportResult.OK, store.importFrom(stream("first")) { true })
        val failingSource = object : InputStream() {
            private var sent = 0
            override fun read(): Int {
                if (sent++ > 3) throw IOException("disk full")
                return 'x'.code
            }
        }
        assertEquals(ImportResult.FAILED, store.importFrom(failingSource) { true })
        assertEquals("first", read(dir))
        assertFalse(File(dir, tempName).exists())
    }

    // Replacement: the old copy is backed up, not deleted, until the new one is installed

    @Test fun replacementSucceedsAndLeavesNoBackup() {
        val dir = folder.newFolder("dua")
        val store = DuaAudioStore(dir)
        assertEquals(ImportResult.OK, store.importFrom(stream("first")) { true })
        assertEquals(ImportResult.OK, store.importFrom(stream("second")) { true })
        assertArrayEquals("second".toByteArray(), File(dir, name).readBytes())
        assertFalse(File(dir, backupName).exists())
    }

    @Test fun replacementInstallFailureRestoresOldCopy() {
        val dir = folder.newFolder("dua")
        assertEquals(ImportResult.OK, DuaAudioStore(dir).importFrom(stream("first")) { true })
        // Only the step that moves the new temporary copy into place fails.
        val failingInstall = DuaAudioStore(dir) { from, to ->
            if (from.name.endsWith(DuaAudioStore.TEMP_SUFFIX)) false else from.renameTo(to)
        }
        assertEquals(ImportResult.FAILED, failingInstall.importFrom(stream("second")) { true })
        assertEquals("first", read(dir))
        assertFalse(File(dir, backupName).exists())
        assertFalse(File(dir, tempName).exists())
    }

    @Test fun backupStepFailureKeepsExistingCopy() {
        val dir = folder.newFolder("dua")
        assertEquals(ImportResult.OK, DuaAudioStore(dir).importFrom(stream("first")) { true })
        // Moving the saved copy aside fails, so nothing is replaced.
        val failingBackup = DuaAudioStore(dir) { from, to ->
            if (from.name == name) false else from.renameTo(to)
        }
        assertEquals(ImportResult.FAILED, failingBackup.importFrom(stream("second")) { true })
        assertEquals("first", read(dir))
        assertFalse(File(dir, tempName).exists())
    }

    @Test fun rollbackFailureIsRestoredOnNextStart() {
        val dir = folder.newFolder("dua")
        assertEquals(ImportResult.OK, DuaAudioStore(dir).importFrom(stream("first")) { true })
        // Installing the new copy fails, and so does the rollback. This simulates the app dying mid-replacement.
        val failingEverything = DuaAudioStore(dir) { from, to ->
            if (from.name.endsWith(DuaAudioStore.TEMP_SUFFIX) || from.name.endsWith(DuaAudioStore.BACKUP_SUFFIX)) {
                false
            } else {
                from.renameTo(to)
            }
        }
        assertEquals(ImportResult.FAILED, failingEverything.importFrom(stream("second")) { true })
        assertFalse(File(dir, name).exists())
        assertTrue(File(dir, backupName).exists())

        // Next app start: the default store repairs the interrupted replacement.
        assertTrue(DuaAudioStore(dir).exists())
        assertEquals("first", read(dir))
        assertFalse(File(dir, backupName).exists())
    }

    @Test fun interruptedReplacementAfterInstallCleansUpOnNextStart() {
        val dir = folder.newFolder("dua")
        seed(dir, name, "second")
        seed(dir, backupName, "first")
        assertTrue(DuaAudioStore(dir).exists())
        assertEquals("second", read(dir))
        assertFalse(File(dir, backupName).exists())
    }

    @Test fun interruptedReplacementBeforeInstallRestoresOldCopyOnNextStart() {
        val dir = folder.newFolder("dua")
        seed(dir, backupName, "first")
        assertTrue(DuaAudioStore(dir).exists())
        assertEquals("first", read(dir))
        assertFalse(File(dir, backupName).exists())
    }
}
