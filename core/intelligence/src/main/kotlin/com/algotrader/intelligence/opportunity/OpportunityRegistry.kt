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

    /**
     * Apply a validated lifecycle operation to one registered opportunity.
     *
     * The transform must use the Opportunity domain methods (advanceTo,
     * reassess, cancel, expire, or recordExecution). Rejected operations leave
     * the registry unchanged. Identity is immutable after registration.
     */
    fun updateRegistered(
        id: OpportunityId,
        transform: (Opportunity) -> TransitionResult<Opportunity>
    ): TransitionResult<Opportunity> = synchronized(lock) {
        val matches = opportunities.entries.filter { it.value.id == id }

        if (matches.isEmpty()) {
            return@synchronized TransitionResult.Rejected(
                "opportunity ${id.value} is not registered"
            )
        }
        if (matches.size != 1) {
            return@synchronized TransitionResult.Rejected(
                "opportunity id ${id.value} is ambiguous in the registry"
            )
        }

        val entry = matches.single()
        val current = entry.value
        val result = try {
            transform(current)
        } catch (failure: RuntimeException) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle operation failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }

        if (result is TransitionResult.Rejected) {
            return@synchronized result
        }

        val updated = (result as TransitionResult.Applied).value
        if (
            updated.id != current.id ||
            updated.strategy != current.strategy ||
            updated.instrument != current.instrument ||
            updated.timeframe != current.timeframe ||
            updated.side != current.side ||
            updated.detectedAt != current.detectedAt
        ) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle update cannot change registered opportunity identity"
            )
        }
        if (updated.updatedAt.isBefore(current.updatedAt)) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle update cannot move time backwards"
            )
        }

        val oldTransitions = current.transitions
        val newTransitions = updated.transitions

        if (newTransitions.size < oldTransitions.size ||
            newTransitions.take(oldTransitions.size) != oldTransitions
        ) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle update cannot rewrite transition history"
            )
        }

        if (updated.state != current.state) {
            if (newTransitions.size != oldTransitions.size + 1) {
                return@synchronized TransitionResult.Rejected(
                    "state change must append exactly one transition"
                )
            }

            val transition = newTransitions.last()
            if (
                transition.from != current.state ||
                transition.to != updated.state ||
                transition.at != updated.updatedAt ||
                transition.at.isBefore(current.updatedAt)
            ) {
                return@synchronized TransitionResult.Rejected(
                    "state change has an inconsistent transition record"
                )
            }

            val legalTransition = when (updated.state) {
                OpportunityState.CANCELLED -> {
                    val cancellation = updated.cancellation
                    current.state.canCancelOrExpire &&
                        cancellation != null &&
                        cancellation.stateWhenCancelled == current.state &&
                        cancellation.at == transition.at &&
                        cancellation.detail == transition.reason
                }
                OpportunityState.EXPIRED ->
                    current.state.canCancelOrExpire &&
                        updated.cancellation == null &&
                        transition.reason.isNotBlank()
                else ->
                    OpportunityStateMachine.canTransition(
                        current.state,
                        updated.state
                    ) && updated.cancellation == null
            }

            if (!legalTransition) {
                return@synchronized TransitionResult.Rejected(
                    "lifecycle update violates opportunity state rules"
                )
            }
        } else {
            if (newTransitions != oldTransitions) {
                return@synchronized TransitionResult.Rejected(
                    "non-transition update cannot alter transition history"
                )
            }
            if (updated.cancellation != current.cancellation) {
                return@synchronized TransitionResult.Rejected(
                    "non-transition update cannot alter cancellation"
                )
            }
        }

        val oldConfidence = current.confidenceHistory.readings
        val newConfidence = updated.confidenceHistory.readings
        if (
            newConfidence.size < oldConfidence.size ||
            newConfidence.take(oldConfidence.size) != oldConfidence
        ) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle update cannot rewrite confidence history"
            )
        }
        val addedConfidence = newConfidence.drop(oldConfidence.size)
        if (addedConfidence.size > 1) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle update cannot append multiple confidence readings"
            )
        }
        if (updated.state != current.state) {
            val transition = newTransitions.last()
            val reading = addedConfidence.singleOrNull()
            if (transition.confidence == null) {
                if (reading != null) {
                    return@synchronized TransitionResult.Rejected(
                        "transition without confidence cannot append a confidence reading"
                    )
                }
            } else if (
                reading == null ||
                reading.value != transition.confidence ||
                reading.at != transition.at ||
                reading.reason != transition.reason
            ) {
                return@synchronized TransitionResult.Rejected(
                    "transition confidence must match its confidence history reading"
                )
            }
        }
        if (newConfidence.any { it.at.isAfter(updated.updatedAt) }) {
            return@synchronized TransitionResult.Rejected(
                "confidence reading cannot be after the opportunity update"
            )
        }
        if (
            updated.state == current.state &&
            addedConfidence.isNotEmpty()
        ) {
            val reading = addedConfidence.single()
            if (
                reading.at != updated.updatedAt ||
                reading.value != updated.currentConfidence
            ) {
                return@synchronized TransitionResult.Rejected(
                    "reassessment confidence must match the opportunity update"
                )
            }
        }
        if (addedConfidence.any { it.at.isBefore(current.updatedAt) }) {
            return@synchronized TransitionResult.Rejected(
                "confidence history cannot move backwards in time"
            )
        }

        val oldEvidence = current.evidence.items
        val newEvidence = updated.evidence.items
        if (
            newEvidence.size < oldEvidence.size ||
            newEvidence.take(oldEvidence.size) != oldEvidence
        ) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle update cannot rewrite evidence history"
            )
        }
        if (newEvidence.any {
                it.scope != com.algotrader.intelligence.evidence.EvidenceScope.LIVE ||
                    it.strategy != current.strategy ||
                    it.recordedAt.isAfter(updated.updatedAt)
            }
        ) {
            return@synchronized TransitionResult.Rejected(
                "lifecycle update contains invalid or future evidence"
            )
        }

        if (updated.state != current.state && updated.execution != current.execution) {
            return@synchronized TransitionResult.Rejected(
                "state transitions cannot alter execution"
            )
        }

        if (updated.execution != current.execution) {
            if (
                updated.execution.rank <= current.execution.rank ||
                updated.state.entryState != EntryState.CONFIRMED ||
                updated.state == OpportunityState.EXIT_CONFIRMED
            ) {
                return@synchronized TransitionResult.Rejected(
                    "execution update violates execution progression rules"
                )
            }

            val executionAt = when (val link = updated.execution) {
                ExecutionLink.None -> null
                is ExecutionLink.UserActed -> link.at
                is ExecutionLink.OrderPlaced -> link.at
            }
            if (executionAt == null || executionAt.isAfter(updated.updatedAt)) {
                return@synchronized TransitionResult.Rejected(
                    "execution timestamp is inconsistent with the opportunity update"
                )
            }
        }

        opportunities[entry.key] = updated
        TransitionResult.Applied(updated)
    }

    /** Snapshot of registered opportunities in deterministic insertion order. */
    fun snapshot(): List<Opportunity> = synchronized(lock) {
        opportunities.values.toList()
    }

    fun size(): Int = synchronized(lock) {
        opportunities.size
    }
}
