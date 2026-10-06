package com.algotrader.intelligence.confidence

import com.algotrader.strategy.Signal
import java.time.Instant

/**
 * The ONE confidence scale in ALTRIXA: a Double in 0.0..1.0, exactly the scale
 * of the existing [Signal.confidence]. There is no second representation; a
 * percentage for display (0.68 -> "68%") is derived later in presentation.
 *
 * Confidence is an assessment of setup quality against historically validated
 * conditions. It is NOT a probability of profit and never implies a guaranteed
 * or certain outcome.
 */
object ConfidenceScale {
    const val MIN = 0.0
    const val MAX = 1.0

    fun isValid(value: Double): Boolean = value.isFinite() && value >= MIN && value <= MAX

    fun requireValid(value: Double, name: String = "confidence"): Double {
        require(isValid(value)) { "$name must be a finite value within $MIN..$MAX (was $value)" }
        return value
    }
}

/** One confidence assessment at one moment, with the reason it changed. */
data class ConfidenceReading(
    val value: Double,
    val at: Instant,
    val reason: String = ""
) {
    init {
        ConfidenceScale.requireValid(value)
    }

    companion object {
        /** Re-uses an engine [Signal]'s own confidence/timestamp. Throws if the signal's value is outside 0..1. */
        fun fromSignal(signal: Signal, reason: String = signal.reason): ConfidenceReading =
            ConfidenceReading(signal.confidence, signal.timestamp, reason)
    }
}

enum class ConfidenceTrend { UNKNOWN, RISING, FALLING, STEADY }

/**
 * Append-only, chronological confidence readings, so confidence can change as
 * market conditions evolve (e.g. 0.68 forming -> 0.76 approaching -> 0.84
 * confirmed -> 0.72 weakening) without losing earlier readings.
 */
data class ConfidenceHistory(val readings: List<ConfidenceReading> = emptyList()) {
    init {
        readings.zipWithNext().forEach { (a, b) ->
            require(!b.at.isBefore(a.at)) { "confidence readings must be in chronological order" }
        }
    }

    val current: Double? get() = readings.lastOrNull()?.value
    val previous: Double? get() = readings.getOrNull(readings.size - 2)?.value

    /** current - previous, or null until there are two readings. */
    val change: Double?
        get() {
            val c = current
            val p = previous
            return if (c == null || p == null) null else c - p
        }

    val trend: ConfidenceTrend
        get() {
            val delta = change ?: return ConfidenceTrend.UNKNOWN
            return when {
                delta > EPSILON -> ConfidenceTrend.RISING
                delta < -EPSILON -> ConfidenceTrend.FALLING
                else -> ConfidenceTrend.STEADY
            }
        }

    fun record(reading: ConfidenceReading): ConfidenceHistory = copy(readings = readings + reading)

    private companion object {
        const val EPSILON = 1e-9
    }
}
