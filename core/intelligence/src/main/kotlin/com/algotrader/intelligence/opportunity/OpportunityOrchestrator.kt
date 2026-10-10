package com.algotrader.intelligence.opportunity

import com.algotrader.intelligence.common.TransitionResult

/**
 * Result for one candidate processed by the ASI-4.3 orchestrator.
 */
data class OpportunityOrchestrationItem(
    val candidate: SetupOpportunityCandidate,
    val result: TransitionResult<OpportunityRegistration>
)

/**
 * Batch result with inspectable successes, duplicates, and rejections.
 */
data class OpportunityOrchestrationResult(
    val items: List<OpportunityOrchestrationItem>
) {
    val created: List<Opportunity>
        get() = items.mapNotNull {
            val applied = it.result as? TransitionResult.Applied ?: return@mapNotNull null
            (applied.value as? OpportunityRegistration.Created)?.opportunity
        }

    val alreadyPresent: List<Opportunity>
        get() = items.mapNotNull {
            val applied = it.result as? TransitionResult.Applied ?: return@mapNotNull null
            (applied.value as? OpportunityRegistration.AlreadyPresent)?.opportunity
        }

    val rejected: List<OpportunityOrchestrationItem>
        get() = items.filter { it.result is TransitionResult.Rejected }

    val isSuccessful: Boolean
        get() = rejected.isEmpty()
}

/**
 * ASI-4.3 deterministic orchestration boundary.
 *
 * ASI-3.x remains responsible for candle-derived detection, qualification,
 * evidence, context, and confluence. This component orders evaluated
 * candidates and submits them to the ASI-4.1 registry.
 *
 * It does not fetch market data, invent evidence, advance lifecycle states,
 * or execute trades.
 */
class OpportunityOrchestrator(
    private val registry: OpportunityRegistry = OpportunityRegistry()
) {
    /**
     * Process candidates in stable stream and evaluation order.
     *
     * Individual rejections are returned to the caller; they do not abort
     * processing of the remaining candidates.
     */
    fun process(
        candidates: Collection<SetupOpportunityCandidate>
    ): OpportunityOrchestrationResult {
        val ordered = candidates.sortedWith(
            compareBy<SetupOpportunityCandidate>(
                { it.strategy.id.value },
                { it.strategy.version },
                { it.instrument.instrument.exchange },
                { it.instrument.instrument.symbol },
                { it.instrument.instrument.currency },
                { it.instrument.kind.name },
                { it.instrument.underlying.orEmpty() },
                { it.instrument.contractId.orEmpty() },
                { it.timeframe.ordinal },
                { it.evaluatedAt },
                { it.setup.breakout.confirmedAt },
                { it.setup.breakout.id.value }
            )
        )

        return OpportunityOrchestrationResult(
            items = ordered.map { candidate ->
                OpportunityOrchestrationItem(
                    candidate = candidate,
                    result = registry.submit(candidate)
                )
            }
        )
    }

    fun snapshot(): List<Opportunity> = registry.snapshot()

    fun size(): Int = registry.size()
}
