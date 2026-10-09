package com.algotrader.intelligence.opportunity

import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.setup.BreakoutId
import com.algotrader.intelligence.setup.BreakoutSetup
import com.algotrader.intelligence.setup.SetupLiveAssessment
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessment
import com.algotrader.intelligence.setup.context.SetupContext
import java.time.Instant

/**
 * An already-evaluated candidate presented to the ASI-4 orchestration layer.
 *
 * Detection, qualification, context extraction, confluence, and confidence
 * calculation remain responsibilities of their existing components.
 */
data class SetupOpportunityCandidate(
    val setup: BreakoutSetup,
    val strategy: StrategyRef,
    val instrument: InstrumentRef,
    val timeframe: Timeframe,
    val assessment: SetupLiveAssessment,
    val context: SetupContext,
    val confluence: SetupConfluenceAssessment,
    val evaluatedAt: Instant
)

/** Result of submitting a candidate to the registry. */
sealed interface OpportunityRegistration {
    data class Created(val opportunity: Opportunity) : OpportunityRegistration
    data class AlreadyPresent(val opportunity: Opportunity) : OpportunityRegistration
}

/**
 * Deterministic, in-memory registry for composed setup opportunities.
 *
 * The registry does not advance opportunity states, execute trades, send
 * notifications, or supersede existing opportunities.
 *
 * Stale evaluations are tracked independently for each exact strategy
 * version + instrument reference + timeframe stream.
 */
class OpportunityRegistry {

    private data class StreamKey(
        val strategy: StrategyRef,
        val instrument: InstrumentRef,
        val timeframe: Timeframe
    )

    private data class OccurrenceKey(
        val stream: StreamKey,
        val breakoutId: BreakoutId,
        val breakAt: Instant,
        val confirmedAt: Instant
    )

    private val lock = Any()
    private val opportunities = linkedMapOf<OccurrenceKey, Opportunity>()
    private val latestEvaluationByStream = mutableMapOf<StreamKey, Instant>()

    /**
     * Submit an evaluated candidate.
     *
     * Rejected input leaves both the occurrence registry and stream chronology
     * unchanged. Duplicate occurrences return the registered instance without
     * resetting its lifecycle or execution information.
     */
    fun submit(
        candidate: SetupOpportunityCandidate
    ): TransitionResult<OpportunityRegistration> = synchronized(lock) {
        val breakout = candidate.setup.breakout
        val stream = StreamKey(
            strategy = candidate.strategy,
            instrument = candidate.instrument,
            timeframe = candidate.timeframe
        )
        val occurrence = OccurrenceKey(
            stream = stream,
            breakoutId = breakout.id,
            breakAt = breakout.breakAt,
            confirmedAt = breakout.confirmedAt
        )

        if (candidate.evaluatedAt.isBefore(breakout.confirmedAt)) {
            return@synchronized TransitionResult.Rejected(
                "evaluation time is before breakout confirmation"
            )
        }

        val latest = latestEvaluationByStream[stream]
        if (latest != null && candidate.evaluatedAt.isBefore(latest)) {
            return@synchronized TransitionResult.Rejected(
                "stale evaluation: ${candidate.evaluatedAt} is before $latest"
            )
        }

        // A repeated occurrence is idempotent. Do not replace the stored
        // instance with a newly composed OPPORTUNITY_FOUND object.
        opportunities[occurrence]?.let { existing ->
            latestEvaluationByStream[stream] = maxOf(
                latest ?: candidate.evaluatedAt,
                candidate.evaluatedAt
            )
            return@synchronized TransitionResult.Applied(
                OpportunityRegistration.AlreadyPresent(existing)
            )
        }

        val composed = try {
            SetupOpportunityComposer.compose(
                setup = candidate.setup,
                strategy = candidate.strategy,
                instrument = candidate.instrument,
                timeframe = candidate.timeframe,
                assessment = candidate.assessment,
                context = candidate.context,
                confluence = candidate.confluence,
                at = candidate.evaluatedAt
            )
        } catch (failure: IllegalArgumentException) {
            return@synchronized TransitionResult.Rejected(
                "candidate rejected: ${failure.message ?: "invalid candidate"}"
            )
        }

        // Commit registry and chronology only after composition succeeds.
        opportunities[occurrence] = composed
        latestEvaluationByStream[stream] = candidate.evaluatedAt

        TransitionResult.Applied(OpportunityRegistration.Created(composed))
    }

    /** Snapshot of registered opportunities in deterministic insertion order. */
    fun snapshot(): List<Opportunity> = synchronized(lock) {
        opportunities.values.toList()
    }

    fun size(): Int = synchronized(lock) {
        opportunities.size
    }
}
