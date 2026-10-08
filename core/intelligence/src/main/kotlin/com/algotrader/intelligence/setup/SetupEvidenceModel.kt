package com.algotrader.intelligence.setup

import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidenceSample
import java.time.Instant

/**
 * Deterministic live evidence and confidence assessment for one existing setup.
 *
 * This is the ASI-3.4 contract only. Evidence extraction and confidence
 * calculation are implemented in subsequent steps.
 *
 * Evidence remains sourced through [EvidenceLedger]. Strategy identity is not
 * duplicated here because [BreakoutSetup] does not own a StrategyRef.
 */
data class SetupEvidenceAssessment(
    val breakoutId: BreakoutId,
    val asOfIndex: Int,
    val asOf: Instant,
    val evidence: EvidenceLedger,
    val confidence: ConfidenceReading
) {
    init {
        require(asOfIndex >= 0) {
            "asOfIndex must be non-negative"
        }
        require(evidence.items.all { it.sample == EvidenceSample.LIVE }) {
            "setup evidence assessment may contain only LIVE evidence"
        }
    }

    val evidenceCount: Int
        get() = evidence.items.size

    val supportingEvidenceCount: Int
        get() = evidence.supporting().size

    val contradictingEvidenceCount: Int
        get() = evidence.contradicting().size
}
