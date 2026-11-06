package com.sx4scanpro

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling log file kept in the app's own storage so a drive test can be shared afterwards.
 * Keeps at most [keep] files of about [maxBytes] each; the oldest is deleted first.
 * Writes never throw: logging must not be able to break a drive.
 */
internal class SessionLog(private val dir: File, private val maxBytes: Long = 700_000L, private val keep: Int = 3) {
    private val name = "session"

    @Synchronized
    fun append(tag: String, text: String, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            dir.mkdirs()
            val current = File(dir, "$name.log")
            if (current.exists() && current.length() >= maxBytes) rotate()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(nowMs))
            current.appendText("$stamp $tag ${text.trim().replace("\r", " ⏎ ").replace("\n", " ⏎ ")}\n")
        }
    }

    private fun rotate() {
        File(dir, "$name.${keep - 1}.log").delete()
        for (i in keep - 2 downTo 1) File(dir, "$name.$i.log").renameTo(File(dir, "$name.${i + 1}.log"))
        File(dir, "$name.log").renameTo(File(dir, "$name.1.log"))
    }

    /** All kept text, oldest first. */
    @Synchronized
    fun readAll(): String = runCatching {
        val files = (keep - 1 downTo 1).map { File(dir, "$name.$it.log") } + File(dir, "$name.log")
        files.filter { it.exists() }.joinToString("") { it.readText() }
    }.getOrDefault("")

    /** One shareable file: [header], then the whole log. Returns null if it could not be written. */
    @Synchronized
    fun export(header: String, nowMs: Long = System.currentTimeMillis()): File? = runCatching {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        dir.listFiles { f -> f.name.startsWith("share-") }?.forEach { it.delete() }
        File(dir, "share-$stamp.txt").also { it.writeText(header.trim() + "\n----\n" + readAll().ifEmpty { "(no session log yet)\n" }) }
    }.getOrNull()

    @Synchronized
    fun clear() { runCatching { dir.listFiles()?.forEach { it.delete() } } }
}
