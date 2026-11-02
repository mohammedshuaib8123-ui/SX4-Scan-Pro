package com.sx4scanpro

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded, thread-safe record of what was sent to and received from the adapter.
 * It exists so a failed drive test can be diagnosed from the real ECU replies instead of guesses.
 */
internal class TrafficLog(private val maxLines: Int = 400) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(direction: String, text: String, nowMs: Long = System.currentTimeMillis()) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMs))
        lines.addLast("$stamp $direction ${text.trim()}")
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    /** Plain text for sharing, with a header the person reading it needs to interpret the lines. */
    @Synchronized
    fun asText(header: String): String =
        header.trim() + "\n----\n" + (if (lines.isEmpty()) "(no adapter traffic recorded yet)" else lines.joinToString("\n"))

    /** The newest [count] lines, newest last, for the on-screen preview. */
    @Synchronized
    fun tail(count: Int): List<String> = lines.toList().takeLast(count)
}
