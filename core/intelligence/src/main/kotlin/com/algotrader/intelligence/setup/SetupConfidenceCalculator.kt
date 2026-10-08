package com.algotrader.intelligence.setup

import com.algotrader.intelligence.confidence.ConfidenceHistory
import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidencePolarity
import java.time.Instant

/**
 * Explicit, immutable scoring policy for ASI-3.4 setup confidence.
 *
 * The policy assigns equal weight to the four live evidence categories that
 * ASI-3.4.2 can currently extract. Other live evidence kinds are deliberately
 * ignored until a later ASI step defines how they participate in confidence.
 */
data class SetupConfidencePolicy(
    val weights: Map<EvidenceKind, Double> = DEFAULT_WEIGHTS
) {
    init {
        require(weights.isNotEmpty()) {
            "confidence policy must contain at least one evidence kind"
        }
        require(weights.values.all { it.isFinite() && it > 0.0 }) {
            "confidence weights must be finite and greater than zero"
        }
    }

    companion object {
        val DEFAULT_WEIGHTS: Map<EvidenceKind, Double> = linkedMapOf(
            EvidenceKind.CURRENT_PATTERN_MATCH to 1.0,
            EvidenceKind.TREND_ALIGNMENT to 1.0,
            EvidenceKind.VOLATILITY_CONDITION to 1.0,
            EvidenceKind.BREAKOUT_RETEST_CONFIRMATION to 1.0
        )
    }
}

/**
 * Converts extracted live setup evidence into the existing 0.0..1.0
 * [ConfidenceReading] representation.
 *
 * Evidence is scored by category, not by raw item count. Multiple items of one
 * category are averaged first, so repeated pattern-boundary evidence cannot
 * overwhelm the other categories.
 *
 * Supporting evidence contributes +1 and contradicting evidence contributes -1.
 * Missing evidence contributes nothing and is excluded from the normalization.
 *
 * Therefore:
 * - all available categories supporting -> 1.0
 * - all available categories contradicting -> 0.0
 * - balanced support/contradiction -> 0.5
 * - no scoreable evidence -> null
 *
 * This component does not perform as-of or lookahead checks; those belong to
 * [SetupEvidenceExtractor].
 */
enum class SetupConfidenceCondition {
    INSUFFICIENT,
    SUPPORTING,
    BALANCED,
    CONTRADICTING
}

enum class SetupConfidenceChange {
    INITIAL,
    IMPROVED,
    STABLE,
    DEGRADED
}

/**
 * Interpretation of one confidence reading against an optional prior history.
 *
 * This is descriptive only. It does not change setup lifecycle state, cancel an
 * opportunity, expire a setup, or retire a strategy.
 */
data class SetupConfidenceAssessment(
    val reading: ConfidenceReading?,
    val condition: SetupConfidenceCondition,
    val change: SetupConfidenceChange
) {
    val isDegraded: Boolean
        get() = change == SetupConfidenceChange.DEGRADED

    val isContradicting: Boolean
        get() = condition == SetupConfidenceCondition.CONTRADICTING

    val isActionable: Boolean
        get() = reading != null
}

object SetupConfidenceCalculator {

    fun calculate(
        evidence: EvidenceLedger,
        asOf: Instant,
        policy: SetupConfidencePolicy = SetupConfidencePolicy()
    ): ConfidenceReading? {
        val scoreable = evidence.items
            .filter { policy.weights.containsKey(it.kind) }
            .groupBy { it.kind }

        if (scoreable.isEmpty()) return null

        var weightedNet = 0.0
        var availableWeight = 0.0

        for ((kind, items) in scoreable) {
            val weight = policy.weights.getValue(kind)
            val categoryScore = items.map { item ->
                when (item.polarity) {
                    EvidencePolarity.SUPPORTING -> 1.0
                    EvidencePolarity.CONTRADICTING -> -1.0
                }
            }.average()

            weightedNet += categoryScore * weight
            availableWeight += weight
        }

        val normalized = weightedNet / availableWeight
        val confidence = 0.5 + (0.5 * normalized)

        return ConfidenceReading(
            value = confidence.coerceIn(0.0, 1.0),
            at = asOf,
            reason = "Setup evidence confidence"
        )
    }

    /**
     * Calculates confidence and classifies contradiction/degradation without
     * introducing lifecycle transitions.
     *
     * [prior] is the existing chronological confidence history. When supplied,
     * the new reading is compared with its current value only; the history
     * itself is not mutated by this function.
     */
    fun assess(
        evidence: EvidenceLedger,
        asOf: Instant,
        prior: ConfidenceHistory = ConfidenceHistory(),
        policy: SetupConfidencePolicy = SetupConfidencePolicy()
    ): SetupConfidenceAssessment {
        val reading = calculate(evidence, asOf, policy)

        if (reading == null) {
            return SetupConfidenceAssessment(
                reading = null,
                condition = SetupConfidenceCondition.INSUFFICIENT,
                change = SetupConfidenceChange.INITIAL
            )
        }

        val condition = when {
            reading.value > 0.5 -> SetupConfidenceCondition.SUPPORTING
            reading.value < 0.5 -> SetupConfidenceCondition.CONTRADICTING
            else -> SetupConfidenceCondition.BALANCED
        }

        val previous = prior.current
        val change = when {
            previous == null -> SetupConfidenceChange.INITIAL
            reading.value > previous + 1e-9 -> SetupConfidenceChange.IMPROVED
            reading.value < previous - 1e-9 -> SetupConfidenceChange.DEGRADED
            else -> SetupConfidenceChange.STABLE
        }

        return SetupConfidenceAssessment(
            reading = reading,
            condition = condition,
            change = change
        )
    }
}
