package com.algotrader.intelligence.setup

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
}
