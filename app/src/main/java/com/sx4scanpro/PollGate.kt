package com.sx4scanpro

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lets ECU discovery borrow the adapter from the live poll loop without the two interleaving commands.
 *
 * The poll loop wraps each slice in [tryEnterSlice] / [exitSlice]. [pause] blocks new slices and waits for the
 * slice in flight to finish, unless the caller is that slice's own thread: it already owns the adapter, so waiting
 * would deadlock. [resume] is safe to call from `finally` and never goes below zero.
 */
internal class PollGate {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var pauseCount = 0
    private var sliceThread: Thread? = null

    /** False while paused or while another slice is running; the caller then skips this slice. */
    fun tryEnterSlice(): Boolean = lock.withLock {
        if (pauseCount > 0 || sliceThread != null) false else { sliceThread = Thread.currentThread(); true }
    }

    fun exitSlice() = lock.withLock {
        if (sliceThread === Thread.currentThread()) { sliceThread = null; changed.signalAll() }
    }

    /** True once no slice other than the caller's own is running and no new one can start. False on timeout (nothing is held). */
    fun pause(timeoutMs: Long = DEFAULT_PAUSE_MS): Boolean = lock.withLock {
        pauseCount++
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (sliceThread != null && sliceThread !== Thread.currentThread()) {
            if (left <= 0L) { pauseCount--; changed.signalAll(); return@withLock false }
            left = changed.awaitNanos(left)
        }
        true
    }

    fun resume() = lock.withLock { if (pauseCount > 0) pauseCount--; changed.signalAll() }

    fun isPaused(): Boolean = lock.withLock { pauseCount > 0 }

    companion object { const val DEFAULT_PAUSE_MS = 20_000L }
}
