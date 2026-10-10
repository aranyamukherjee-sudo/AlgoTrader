package com.algotrader.intelligence.opportunity

import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyRef
import java.time.Instant

/**
 * Read-only filters for recorded opportunity state transitions.
 *
 * The lower time boundary is inclusive; the upper boundary is exclusive.
 * A null boundary imposes no restriction. The selected range must be
 * increasing when both boundaries are supplied.
 *
 * Opportunity IDs are not guaranteed to be unique. Filtering by ID
 * therefore returns transition records for every matching occurrence.
 *
 * This is lifecycle transition history, not a complete audit log:
 * reassessments and execution-link updates do not append transitions.
 */
data class OpportunityTransitionQuery(
    val id: OpportunityId? = null,
    val fromInclusive: Instant? = null,
    val untilExclusive: Instant? = null
) {
    init {
        require(
            fromInclusive == null ||
                untilExclusive == null ||
                fromInclusive.isBefore(untilExclusive)
        ) {
            "fromInclusive must be before untilExclusive"
        }
    }
}

/**
 * One recorded state transition and the registered occurrence it belongs to.
 *
 * transitionIndex is zero-based within the opportunity's transition list.
 * It distinguishes multiple transitions belonging to the same occurrence.
 */
data class OpportunityTransitionRecord(
    val opportunityId: OpportunityId,
    val strategy: StrategyRef,
    val instrument: InstrumentRef,
    val timeframe: Timeframe,
    val side: com.algotrader.intelligence.dna.TradeSide,
    val detectedAt: Instant,
    val transitionIndex: Int,
    val transition: OpportunityTransition
)
