package com.sx4scanpro

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Result of an import attempt. Only OK changes the saved copy. */
internal enum class ImportResult { OK, REJECTED, FAILED }

/**
 * Private copy of the owner's audio file, kept in app storage so it survives closing, reopening,
 * and phone restarts. It is removed only by REMOVE, by clearing the app's data, or by uninstalling.
 * [rename] is injectable so tests can simulate install failures.
 */
internal class DuaAudioStore(
    private val dir: File,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
) {
    val file: File get() = File(dir, FILE_NAME)
    private val temp: File get() = File(dir, FILE_NAME + TEMP_SUFFIX)
    private val backup: File get() = File(dir, FILE_NAME + BACKUP_SUFFIX)

    fun exists(): Boolean {
        recover()
        return file.isFile && file.length() > 0L
    }

    /**
     * Copies [source] to a temporary file, checks it with [validate], then installs it.
     * Any failure before installation leaves the existing copy untouched.
     */
    fun importFrom(source: InputStream, validate: (File) -> Boolean): ImportResult {
        dir.mkdirs()
        recover()
        temp.delete()
        val copied = try {
            source.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!copied) {
            temp.delete()
            return ImportResult.FAILED
        }
        if (temp.length() == 0L) {
            temp.delete()
            return ImportResult.REJECTED
        }
        val valid = try {
            validate(temp)
        } catch (_: Exception) {
            false
        }
        if (!valid) {
            temp.delete()
            return ImportResult.REJECTED
        }
        return install()
    }

    /** Moves the old copy aside, moves the new one in, then discards the old one. Rolls back on failure. */
    private fun install(): ImportResult {
        val hadOld = file.exists()
        if (hadOld) {
            backup.delete()
            if (!rename(file, backup)) {
                temp.delete()
                return ImportResult.FAILED
            }
        }
        if (!rename(temp, file)) {
            // Roll back. If this rename also fails, the old copy stays in the backup and recover() restores it.
            if (hadOld) rename(backup, file)
            temp.delete()
            return ImportResult.FAILED
        }
        backup.delete()
        return ImportResult.OK
    }

    /** Repairs an interrupted replacement. Runs before every check and every import. */
    fun recover() {
        if (!backup.exists()) return
        if (file.exists()) {
            // The new copy was already installed, so the old one is no longer needed.
            backup.delete()
        } else {
            rename(backup, file)
        }
    }

    fun remove() {
        file.delete()
        temp.delete()
        backup.delete()
    }

    companion object {
        const val FILE_NAME = "safar_ki_dua.audio"
        const val TEMP_SUFFIX = ".tmp"
        const val BACKUP_SUFFIX = ".bak"
    }
}
