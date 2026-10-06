package com.algotrader.intelligence.opportunity

import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.confidence.ConfidenceHistory
import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.confidence.ConfidenceScale
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.dna.TradeSide
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidenceScope
import java.time.Instant

@JvmInline
value class OpportunityId(val value: String) {
    init {
        require(value.isNotBlank()) { "opportunity id must not be blank" }
    }
}

/** Why an opportunity was cancelled before entry. None of these retire a strategy. */
enum class CancellationReason {
    CONDITIONS_WEAKENED,
    INVALIDATION_CONDITION_MET,
    SUPERSEDED,
    USER_DISMISSED,
    STRATEGY_RETIRED
}

data class CancellationInfo(
    val reason: CancellationReason,
    val detail: String,
    val at: Instant,
    val stateWhenCancelled: OpportunityState
)

data class OpportunityTransition(
    val from: OpportunityState?,
    val to: OpportunityState,
    val at: Instant,
    val reason: String,
    val confidence: Double?
)

/**
 * A current market occurrence matching ONE exact strategy version ([strategy]).
 * A strategy is a reusable rule set; this is one live instance of it.
 *
 * - Live only: it holds LIVE evidence, bound to the same strategy version.
 * - It never mutates a strategy record, so cancelling or expiring an
 *   opportunity cannot retire or degrade the strategy.
 * - [state] is the single source of truth; [entryState]/[exitState] derive from it.
 * - [execution] is a separate layer and is not changed by state transitions.
 * - Confidence uses the existing 0.0..1.0 scale and is NOT a probability of profit.
 */
data class Opportunity(
    val id: OpportunityId,
    val strategy: StrategyRef,
    val instrument: InstrumentRef,
    val timeframe: Timeframe,
    val side: TradeSide,
    val state: OpportunityState,
    val detectedAt: Instant,
    val transitions: List<OpportunityTransition>,
    val updatedAt: Instant,
    val confidenceHistory: ConfidenceHistory = ConfidenceHistory(),
    val evidence: EvidenceLedger = EvidenceLedger(),
    val cancellation: CancellationInfo? = null,
    val execution: ExecutionLink = ExecutionLink.None
) {
    init {
        require(instrument.isTradable) { "an opportunity needs a tradable instrument; ${instrument.kind} is reference-only" }
        require(evidence.items.all { it.scope == EvidenceScope.LIVE }) {
            "an opportunity holds live evidence only; research evidence belongs to the strategy"
        }
        require(evidence.items.all { it.strategy == strategy }) {
            "evidence must be about the opportunity's exact strategy version ($strategy)"
        }
        require(transitions.isNotEmpty() && transitions.last().to == state) {
            "state must match the last recorded transition"
        }
        require((state == OpportunityState.CANCELLED) == (cancellation != null)) {
            "cancellation info must be present exactly when the state is CANCELLED"
        }
        require(execution.rank == 0 || state.entryState == EntryState.CONFIRMED) {
            "execution can only exist after the entry is confirmed"
        }
    }

    val entryState: EntryState get() = state.entryState
    val exitState: ExitState get() = state.exitState
    val currentConfidence: Double? get() = confidenceHistory.current

    fun advanceTo(
        target: OpportunityState,
        at: Instant,
        reason: String,
        confidence: Double? = null,
        newEvidence: List<EvidenceItem> = emptyList()
    ): TransitionResult<Opportunity> {
        if (target == OpportunityState.CANCELLED) return reject("use cancel() so a cancellation reason is recorded")
        if (target == OpportunityState.EXPIRED) return reject("use expire() to expire an opportunity")
        precheck(at, reason, confidence, newEvidence)?.let { return it }
        if (!OpportunityStateMachine.canTransition(state, target)) {
            return reject("$state cannot move to $target")
        }
        return TransitionResult.Applied(moved(target, at, reason, confidence, newEvidence, null))
    }

    /** Records a changed assessment without changing state (e.g. conditions weakening). */
    fun reassess(
        confidence: Double,
        at: Instant,
        reason: String,
        newEvidence: List<EvidenceItem> = emptyList()
    ): TransitionResult<Opportunity> {
        precheck(at, reason, confidence, newEvidence)?.let { return it }
        return TransitionResult.Applied(
            copy(
                confidenceHistory = confidenceHistory.record(ConfidenceReading(confidence, at, reason)),
                evidence = newEvidence.fold(evidence) { ledger, item -> ledger.add(item) },
                updatedAt = at
            )
        )
    }

    fun cancel(reason: CancellationReason, detail: String, at: Instant): TransitionResult<Opportunity> {
        precheck(at, detail, null, emptyList())?.let { return it }
        if (!state.canCancelOrExpire) return reject("entry is already confirmed; use the exit states instead of cancelling")
        val info = CancellationInfo(reason, detail, at, state)
        return TransitionResult.Applied(moved(OpportunityState.CANCELLED, at, detail, null, emptyList(), info))
    }

    fun expire(detail: String, at: Instant): TransitionResult<Opportunity> {
        precheck(at, detail, null, emptyList())?.let { return it }
        if (!state.canCancelOrExpire) return reject("entry is already confirmed; an opportunity can no longer expire")
        return TransitionResult.Applied(moved(OpportunityState.EXPIRED, at, detail, null, emptyList(), null))
    }

    /** Records that a person acted / an order exists. Never triggered by state changes. */
    fun recordExecution(link: ExecutionLink, at: Instant): TransitionResult<Opportunity> {
        if (link.rank == 0) return reject("nothing to record")
        if (at.isBefore(updatedAt)) return reject("time is before the last update")
        if (state.entryState != EntryState.CONFIRMED) return reject("execution requires a confirmed entry")
        if (state == OpportunityState.EXIT_CONFIRMED) return reject("the opportunity is already closed")
        if (link.rank < execution.rank) return reject("execution cannot move backwards")
        return TransitionResult.Applied(copy(execution = link, updatedAt = at))
    }

    private fun precheck(
        at: Instant,
        reason: String,
        confidence: Double?,
        newEvidence: List<EvidenceItem>
    ): TransitionResult.Rejected? {
        if (state.isTerminal) return TransitionResult.Rejected("$state is terminal")
        if (reason.isBlank()) return TransitionResult.Rejected("a reason is required")
        if (at.isBefore(updatedAt)) return TransitionResult.Rejected("time is before the last update")
        if (confidence != null && !ConfidenceScale.isValid(confidence)) {
            return TransitionResult.Rejected("confidence must be within 0.0..1.0")
        }
        for (item in newEvidence) {
            if (item.scope != EvidenceScope.LIVE) return TransitionResult.Rejected("only live evidence can be added to an opportunity")
            if (item.strategy != strategy) return TransitionResult.Rejected("evidence belongs to ${item.strategy}, not $strategy")
            if (evidence.items.any { it.id == item.id }) return TransitionResult.Rejected("evidence ${item.id.id} already recorded")
        }
        return null
    }

    private fun moved(
        target: OpportunityState,
        at: Instant,
        reason: String,
        confidence: Double?,
        newEvidence: List<EvidenceItem>,
        info: CancellationInfo?
    ): Opportunity = copy(
        state = target,
        transitions = transitions + OpportunityTransition(state, target, at, reason, confidence),
        confidenceHistory = if (confidence == null) confidenceHistory
        else confidenceHistory.record(ConfidenceReading(confidence, at, reason)),
        evidence = newEvidence.fold(evidence) { ledger, item -> ledger.add(item) },
        cancellation = info,
        updatedAt = at
    )

    private fun reject(reason: String): TransitionResult.Rejected = TransitionResult.Rejected(reason)

    companion object {
        /** A newly detected opportunity. [confidence] is null until something has scored it. */
        fun detected(
            id: OpportunityId,
            strategy: StrategyRef,
            instrument: InstrumentRef,
            timeframe: Timeframe,
            side: TradeSide,
            at: Instant,
            confidence: Double? = null,
            evidence: EvidenceLedger = EvidenceLedger(),
            reason: String = "Setup detected"
        ): Opportunity = Opportunity(
            id = id,
            strategy = strategy,
            instrument = instrument,
            timeframe = timeframe,
            side = side,
            state = OpportunityState.OPPORTUNITY_FOUND,
            detectedAt = at,
            transitions = listOf(
                OpportunityTransition(null, OpportunityState.OPPORTUNITY_FOUND, at, reason, confidence)
            ),
            updatedAt = at,
            confidenceHistory = if (confidence == null) ConfidenceHistory()
            else ConfidenceHistory(listOf(ConfidenceReading(confidence, at, reason))),
            evidence = evidence
        )
    }
}
