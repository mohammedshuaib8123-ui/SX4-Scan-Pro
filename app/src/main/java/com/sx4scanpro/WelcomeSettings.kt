package com.sx4scanpro

/** Owner preferences for the welcome screen. Defaults follow the android-ui-refresh prototype. */
data class WelcomeSettings(
    val durationSec: Float = DEFAULT_DURATION_SEC,
    val sound: Boolean = false,
    val everyConnection: Boolean = true,
    val volume: Float = 1f
) {
    /** Length of the welcome screen; reduced motion caps it so the screen never lingers. */
    fun durationMs(reducedMotion: Boolean): Long {
        val sec = clampDuration(durationSec)
        return ((if (reducedMotion) minOf(sec, REDUCED_MOTION_SEC) else sec) * 1000f).toLong()
    }

    /** Whether to show the welcome for this ECU connection. */
    fun shouldPlay(alreadyPlayedThisSession: Boolean): Boolean = everyConnection || !alreadyPlayedThisSession

    companion object {
        const val DEFAULT_DURATION_SEC = 4.8f
        const val MIN_DURATION_SEC = 2f
        const val MAX_DURATION_SEC = 15f
        const val REDUCED_MOTION_SEC = 1.2f

        fun clampVolume(v: Float): Float = if (v.isNaN()) 1f else v.coerceIn(0f, 1f)

        fun clampDuration(sec: Float): Float =
            if (sec.isNaN()) DEFAULT_DURATION_SEC else sec.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
    }
}
