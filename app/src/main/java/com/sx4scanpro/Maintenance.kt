package com.sx4scanpro

/**
 * Service reminders counted in kilometres the app has actually tracked from road speed. The owner
 * sets every interval; the app ships none. Distance driven while the app was not connected is not
 * counted, so these are reminders, not an odometer.
 */
internal object Maintenance {
    val ITEMS = listOf("Engine oil and filter", "Air filter", "Fuel filter", "Coolant", "Timing belt", "Brake fluid")

    enum class State { NOT_SET, OK, DUE_SOON, OVERDUE }

    data class Status(val state: State, val remainingKm: Double?)

    /** Remaining kilometres, or null when no interval is set. [dueSoonKm] is a fixed 10% of the interval. */
    fun status(intervalKm: Int?, trackedKm: Double, doneAtKm: Double): Status {
        if (intervalKm == null || intervalKm <= 0) return Status(State.NOT_SET, null)
        val remaining = intervalKm - (trackedKm - doneAtKm).coerceAtLeast(0.0)
        val state = when {
            remaining <= 0.0 -> State.OVERDUE
            remaining <= intervalKm * 0.10 -> State.DUE_SOON
            else -> State.OK
        }
        return Status(state, remaining)
    }

    fun describe(s: Status): String = when (s.state) {
        State.NOT_SET -> "No interval set"
        State.OVERDUE -> String.format(java.util.Locale.US, "Overdue by %.0f km", -(s.remainingKm ?: 0.0))
        State.DUE_SOON -> String.format(java.util.Locale.US, "Due soon: %.0f km left", s.remainingKm ?: 0.0)
        State.OK -> String.format(java.util.Locale.US, "%.0f km left", s.remainingKm ?: 0.0)
    }

    fun key(name: String, what: String): String = "maint_" + what + "_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
}
