package com.algotrader.intelligence.setup

import com.algotrader.intelligence.confidence.ConfidenceHistory
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.evidence.EvidenceLedger
import java.time.Instant

/**
 * ASI-3.4.6 composition result for one existing setup at one live as-of point.
 *
 * The existing 3.4 contracts remain unchanged:
 * - evidence is always available as an EvidenceLedger;
 * - confidence condition/change semantics remain in SetupConfidenceAssessment;
 * - SetupEvidenceAssessment is created only when a ConfidenceReading exists.
 */
data class SetupLiveAssessment(
    val evidence: EvidenceLedger,
    val confidenceAssessment: SetupConfidenceAssessment,
    val evidenceAssessment: SetupEvidenceAssessment?
) {
    val reading
        get() = confidenceAssessment.reading

    val condition: SetupConfidenceCondition
        get() = confidenceAssessment.condition

    val change: SetupConfidenceChange
        get() = confidenceAssessment.change

    val isDegraded: Boolean
        get() = confidenceAssessment.isDegraded

    val isContradicting: Boolean
        get() = confidenceAssessment.isContradicting

    val isActionable: Boolean
        get() = confidenceAssessment.isActionable
}

/**
 * Deterministically composes existing ASI-3.4 evidence extraction and
 * confidence assessment for an already-created BreakoutSetup.
 *
 * This component does not detect breakouts, qualify them, or advance setup
 * lifecycle state. Temporal filtering remains owned by
 * SetupEvidenceExtractor.
 */
object SetupEvidenceAssessmentEngine {

    fun assess(
        setup: BreakoutSetup,
        strategy: StrategyRef,
        asOfIndex: Int,
        asOf: Instant,
        prior: ConfidenceHistory = ConfidenceHistory(),
        policy: SetupConfidencePolicy = SetupConfidencePolicy()
    ): SetupLiveAssessment {
        require(asOfIndex >= setup.breakout.confirmedIndex) {
            "asOfIndex must not precede breakout confirmation"
        }

        val evidence = SetupEvidenceExtractor.extract(
            setup = setup,
            strategy = strategy,
            asOfIndex = asOfIndex,
            asOf = asOf
        )

        val confidence = SetupConfidenceCalculator.assess(
            evidence = evidence,
            asOf = asOf,
            prior = prior,
            policy = policy
        )

        val evidenceAssessment = confidence.reading?.let { reading ->
            SetupEvidenceAssessment(
                breakoutId = setup.breakout.id,
                asOfIndex = asOfIndex,
                asOf = asOf,
                evidence = evidence,
                confidence = reading
            )
        }

        return SetupLiveAssessment(
            evidence = evidence,
            confidenceAssessment = confidence,
            evidenceAssessment = evidenceAssessment
        )
    }
}
