package com.sx4scanpro

/**
 * Conservative, testable alert evaluator.
 *
 * Rules must be explicitly configured from verified vehicle data. An empty rule
 * list is intentional: no warning thresholds are enabled by default.
 */
enum class WarningSeverity { CAUTION, CRITICAL }

data class SensorReading(
    val value: Double?,
    val capturedAtMs: Long,
    val supported: Boolean,
    val valid: Boolean
)

data class WarningRule(
    val id: String,
    val sensor: String,
    val severity: WarningSeverity,
    val triggerAtOrAbove: Double,
    val clearBelow: Double,
    val consecutiveSamples: Int = 3,
    val cooldownMs: Long = 60_000L,
    val maxAgeMs: Long = 4_000L
) {
    init {
        require(id.isNotBlank() && sensor.isNotBlank())
        require(clearBelow < triggerAtOrAbove) { "Clear threshold must provide hysteresis" }
        require(consecutiveSamples >= 1)
        require(cooldownMs >= 0L && maxAgeMs > 0L)
    }
}

data class WarningEvent(
    val ruleId: String,
    val severity: WarningSeverity,
    val value: Double,
    val triggeredAtMs: Long
)

class WarningEngine(private val rules: List<WarningRule> = emptyList()) {
    private data class State(
        var consecutiveHigh: Int = 0,
        var active: Boolean = false,
        var lastEmittedAtMs: Long = Long.MIN_VALUE
    )

    private val states = rules.associate { it.id to State() }

    init {
        require(rules.map { it.id }.distinct().size == rules.size) { "Warning rule IDs must be unique" }
    }

    @Synchronized
    fun evaluate(readings: Map<String, SensorReading>, nowMs: Long): List<WarningEvent> {
        val events = mutableListOf<WarningEvent>()
        for (rule in rules) {
            val state = states.getValue(rule.id)
            val reading = readings[rule.sensor]
            val value = reading?.value
            val fresh = reading != null &&
                reading.supported &&
                reading.valid &&
                value != null &&
                value.isFinite() &&
                nowMs >= reading.capturedAtMs &&
                nowMs - reading.capturedAtMs <= rule.maxAgeMs

            // Missing, unsupported, invalid or stale data cannot trigger or clear an alert.
            if (!fresh) {
                state.consecutiveHigh = 0
                continue
            }

            if (state.active) {
                if (value!! < rule.clearBelow) {
                    state.active = false
                    state.consecutiveHigh = 0
                }
                continue
            }

            if (value!! >= rule.triggerAtOrAbove) {
                state.consecutiveHigh++
                val cooldownPassed = state.lastEmittedAtMs == Long.MIN_VALUE ||
                    nowMs - state.lastEmittedAtMs >= rule.cooldownMs
                if (state.consecutiveHigh >= rule.consecutiveSamples && cooldownPassed) {
                    state.active = true
                    state.lastEmittedAtMs = nowMs
                    events += WarningEvent(rule.id, rule.severity, value, nowMs)
                }
            } else {
                state.consecutiveHigh = 0
            }
        }
        return events
    }

    fun configuredRuleCount(): Int = rules.size
}
