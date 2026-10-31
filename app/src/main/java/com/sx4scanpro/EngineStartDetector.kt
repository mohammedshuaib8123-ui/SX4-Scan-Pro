package com.sx4scanpro

/**
 * Debounces engine-running transitions using only fresh, valid RPM samples.
 * This detects a transition; it is not an engine-health or warning threshold.
 */
internal class EngineStartDetector(
    private val startSamplesRequired: Int = 2,
    private val stopSamplesRequired: Int = 5
) {
    private var running = false
    private var positiveSamples = 0
    private var zeroSamples = 0

    init {
        require(startSamplesRequired > 0)
        require(stopSamplesRequired > 0)
    }

    /** Returns true exactly once when a stopped -> running transition is confirmed. */
    fun update(rpm: Int?, isFresh: Boolean): Boolean {
        if (!isFresh || rpm == null || rpm < 0) return false
        if (rpm > 0) {
            zeroSamples = 0
            if (!running) {
                positiveSamples++
                if (positiveSamples >= startSamplesRequired) {
                    running = true
                    positiveSamples = 0
                    return true
                }
            }
        } else {
            positiveSamples = 0
            if (running) {
                zeroSamples++
                if (zeroSamples >= stopSamplesRequired) {
                    running = false
                    zeroSamples = 0
                }
            }
        }
        return false
    }
}
