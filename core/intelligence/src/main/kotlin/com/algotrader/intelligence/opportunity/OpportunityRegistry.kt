package com.algotrader.intelligence.opportunity

import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.evidence.EvidenceItem
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
     * Reassess one registered opportunity without advancing its lifecycle.
     *
     * Confidence and new live evidence are appended through the domain model;
     * the registry's normal validation keeps rejection atomic.
     */
    fun reassessRegistered(
        id: OpportunityId,
        confidence: Double,
        at: Instant,
        reason: String,
        newEvidence: List<EvidenceItem> = emptyList()
    ): TransitionResult<Opportunity> = updateRegistered(id) { opportunity ->
        opportunity.reassess(
            confidence = confidence,
            at = at,
            reason = reason,
            newEvidence = newEvidence
        )
    }

    /**
     * Supersede one registered opportunity with a newer opportunity for the
     * same instrument and timeframe.
     *
     * Strategy versions may differ. The successor remains unchanged; the
     * predecessor is cancelled through the domain model with SUPERSEDED.
     * Validation and mutation run under the registry lock, so rejected
     * operations leave the registry unchanged.
     */
    fun supersedeRegistered(
        predecessorId: OpportunityId,
        successorId: OpportunityId,
        at: Instant,
        reason: String
    ): TransitionResult<Opportunity> = synchronized(lock) {
        if (predecessorId == successorId) {
            return@synchronized TransitionResult.Rejected(
                "an opportunity cannot supersede itself"
            )
        }

        if (reason.isBlank()) {
            return@synchronized TransitionResult.Rejected(
                "a supersession reason is required"
            )
        }

        val predecessorMatches =
            opportunities.entries.filter { it.value.id == predecessorId }

        if (predecessorMatches.isEmpty()) {
            return@synchronized TransitionResult.Rejected(
                "predecessor ${predecessorId.value} is not registered"
            )
        }
        if (predecessorMatches.size != 1) {
            return@synchronized TransitionResult.Rejected(
                "predecessor id ${predecessorId.value} is ambiguous in the registry"
            )
        }

        val successorMatches =
            opportunities.entries.filter { it.value.id == successorId }

        if (successorMatches.isEmpty()) {
            return@synchronized TransitionResult.Rejected(
                "successor ${successorId.value} is not registered"
            )
        }
        if (successorMatches.size != 1) {
            return@synchronized TransitionResult.Rejected(
                "successor id ${successorId.value} is ambiguous in the registry"
            )
        }

        val predecessor = predecessorMatches.single().value
        val successor = successorMatches.single().value

        if (predecessor.instrument != successor.instrument) {
            return@synchronized TransitionResult.Rejected(
                "supersession requires the same instrument"
            )
        }
        if (predecessor.timeframe != successor.timeframe) {
            return@synchronized TransitionResult.Rejected(
                "supersession requires the same timeframe"
            )
        }
        if (!successor.detectedAt.isAfter(predecessor.detectedAt)) {
            return@synchronized TransitionResult.Rejected(
                "successor must be detected after the predecessor"
            )
        }
        if (at.isBefore(predecessor.updatedAt)) {
            return@synchronized TransitionResult.Rejected(
                "supersession time is before the predecessor update"
            )
        }
        if (at.isBefore(successor.updatedAt)) {
            return@synchronized TransitionResult.Rejected(
                "supersession time is before the successor update"
            )
        }
        if (successor.state.isTerminal) {
            return@synchronized TransitionResult.Rejected(
                "a terminal opportunity cannot supersede another opportunity"
            )
        }
        if (!predecessor.state.canCancelOrExpire) {
            return@synchronized TransitionResult.Rejected(
                "predecessor cannot be superseded after entry confirmation"
            )
        }

        val detail = "Superseded by ${successor.id.value}: $reason"

        // updateRegistered is synchronized on the same reentrant JVM monitor.
        // All cross-opportunity checks have passed before the sole mutation.
        updateRegistered(predecessorId) { current ->
            current.cancel(
                reason = CancellationReason.SUPERSEDED,
                detail = detail,
                at = at
            )
        }
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

    /**
     * Return matching registered opportunities in deterministic order.
     *
     * All supplied filters are combined with AND semantics. Results are
     * copied while holding the registry lock; callers cannot mutate the
     * registry through the returned list.
     *
     * Ordering is independent of registration order. The existing
     * [snapshot] method retains its insertion-order contract.
     */
    fun query(filter: OpportunityQuery = OpportunityQuery()): List<Opportunity> =
        synchronized(lock) {
            opportunities.values
                .asSequence()
                .filter { opportunity ->
                    (filter.id == null || opportunity.id == filter.id) &&
                        (filter.strategy == null || opportunity.strategy == filter.strategy) &&
                        (filter.instrument == null || opportunity.instrument == filter.instrument) &&
                        (filter.timeframe == null || opportunity.timeframe == filter.timeframe) &&
                        (filter.states.isEmpty() || opportunity.state in filter.states) &&
                        (filter.includeTerminal || !opportunity.state.isTerminal)
                }
                .sortedWith(
                    compareBy<Opportunity> { it.detectedAt }
                        .thenBy { it.id.value }
                        .thenBy { it.strategy.id.value }
                        .thenBy { it.strategy.version }
                        .thenBy { it.instrument.instrument.exchange }
                        .thenBy { it.instrument.instrument.symbol }
                        .thenBy { it.instrument.instrument.currency }
                        .thenBy { it.instrument.kind.name }
                        .thenBy { it.instrument.underlying.orEmpty() }
                        .thenBy { it.instrument.contractId.orEmpty() }
                        .thenBy { it.timeframe.ordinal }
                        .thenBy { it.state.name }
                        .thenBy { it.updatedAt }
                )
                .toList()
        }

    /**
     * Return recorded state transitions in deterministic chronological order.
     *
     * Filtering and copying occur under the registry lock. The method is
     * read-only and includes transitions from terminal opportunities.
     * Reassessments and execution updates are not state transitions and are
     * therefore not represented in this result.
     */
    fun transitionHistory(
        filter: OpportunityTransitionQuery = OpportunityTransitionQuery()
    ): List<OpportunityTransitionRecord> = synchronized(lock) {
        opportunities.values
            .asSequence()
            .filter { opportunity ->
                filter.id == null || opportunity.id == filter.id
            }
            .flatMap { opportunity ->
                opportunity.transitions
                    .withIndex()
                    .asSequence()
                    .map { indexed ->
                        OpportunityTransitionRecord(
                            opportunityId = opportunity.id,
                            strategy = opportunity.strategy,
                            instrument = opportunity.instrument,
                            timeframe = opportunity.timeframe,
                            side = opportunity.side,
                            detectedAt = opportunity.detectedAt,
                            transitionIndex = indexed.index,
                            transition = indexed.value
                        )
                    }
            }
            .filter { record ->
                (filter.fromInclusive == null ||
                    !record.transition.at.isBefore(filter.fromInclusive)) &&
                    (filter.untilExclusive == null ||
                        record.transition.at.isBefore(filter.untilExclusive))
            }
            .sortedWith(
                compareBy<OpportunityTransitionRecord> { it.transition.at }
                    .thenBy { it.detectedAt }
                    .thenBy { it.opportunityId.value }
                    .thenBy { it.strategy.id.value }
                    .thenBy { it.strategy.version }
                    .thenBy { it.instrument.instrument.exchange }
                    .thenBy { it.instrument.instrument.symbol }
                    .thenBy { it.instrument.instrument.currency }
                    .thenBy { it.instrument.kind.name }
                    .thenBy { it.instrument.underlying.orEmpty() }
                    .thenBy { it.instrument.contractId.orEmpty() }
                    .thenBy { it.timeframe.ordinal }
                    .thenBy { it.side.name }
                    .thenBy { it.transitionIndex }
                    .thenBy { it.transition.from?.name.orEmpty() }
                    .thenBy { it.transition.to.name }
                    .thenBy { it.transition.reason }
                    .thenBy { it.transition.confidence ?: -1.0 }
            )
            .toList()
    }

    /** Return all non-terminal registered opportunities in deterministic order. */
    fun activeSnapshot(): List<Opportunity> =
        query(OpportunityQuery(includeTerminal = false))

    /** Snapshot of registered opportunities in deterministic insertion order. */
    fun snapshot(): List<Opportunity> = synchronized(lock) {
        opportunities.values.toList()
    }

    fun size(): Int = synchronized(lock) {
        opportunities.size
    }
}
