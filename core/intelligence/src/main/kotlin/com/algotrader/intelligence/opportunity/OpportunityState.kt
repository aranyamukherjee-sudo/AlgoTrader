package com.algotrader.intelligence.opportunity

/** Coarse phase of an opportunity. */
enum class OpportunityPhase { SETUP, ENTRY, IN_PLAY, EXIT, VOIDED }

/**
 * Entry side of an opportunity, derived from its single state. "Conditions
 * met" and "confirmed" are different things, and neither means an order was
 * placed (that is execution, a separate layer).
 */
enum class EntryState { NOT_STARTED, WAITING, CONDITIONS_MET, CONFIRMED, VOIDED }

/** Exit side, derived from the same state. An exit warning is not an exit signal. */
enum class ExitState { NOT_APPLICABLE, NONE, FORMING, ALERTED, CONDITIONS_MET, CONFIRMED }

/**
 * The single source of truth for where an opportunity is. Entry and exit
 * views are derived from it, so they can never disagree.
 *
 * Happy path:
 * OPPORTUNITY_FOUND -> ALERTED -> WAITING_FOR_ENTRY -> ENTRY_CONDITIONS_MET ->
 * ENTRY_CONFIRMED -> MONITORING -> EXIT_CONDITIONS_FORMING -> EXIT_ALERT ->
 * EXIT_CONDITIONS_MET -> EXIT_CONFIRMED.
 *
 * Before entry is confirmed an opportunity can end as CANCELLED (conditions
 * weakened / invalidated / etc.) or EXPIRED. Neither says anything about the
 * strategy itself.
 */
enum class OpportunityState {
    OPPORTUNITY_FOUND,
    ALERTED,
    WAITING_FOR_ENTRY,
    ENTRY_CONDITIONS_MET,
    ENTRY_CONFIRMED,
    MONITORING,
    EXIT_CONDITIONS_FORMING,
    EXIT_ALERT,
    EXIT_CONDITIONS_MET,
    EXIT_CONFIRMED,
    CANCELLED,
    EXPIRED;

    val phase: OpportunityPhase
        get() = when (this) {
            OPPORTUNITY_FOUND, ALERTED, WAITING_FOR_ENTRY -> OpportunityPhase.SETUP
            ENTRY_CONDITIONS_MET, ENTRY_CONFIRMED -> OpportunityPhase.ENTRY
            MONITORING -> OpportunityPhase.IN_PLAY
            EXIT_CONDITIONS_FORMING, EXIT_ALERT, EXIT_CONDITIONS_MET, EXIT_CONFIRMED -> OpportunityPhase.EXIT
            CANCELLED, EXPIRED -> OpportunityPhase.VOIDED
        }

    val entryState: EntryState
        get() = when (this) {
            OPPORTUNITY_FOUND, ALERTED -> EntryState.NOT_STARTED
            WAITING_FOR_ENTRY -> EntryState.WAITING
            ENTRY_CONDITIONS_MET -> EntryState.CONDITIONS_MET
            ENTRY_CONFIRMED, MONITORING, EXIT_CONDITIONS_FORMING, EXIT_ALERT,
            EXIT_CONDITIONS_MET, EXIT_CONFIRMED -> EntryState.CONFIRMED
            CANCELLED, EXPIRED -> EntryState.VOIDED
        }

    val exitState: ExitState
        get() = when (this) {
            OPPORTUNITY_FOUND, ALERTED, WAITING_FOR_ENTRY, ENTRY_CONDITIONS_MET,
            CANCELLED, EXPIRED -> ExitState.NOT_APPLICABLE
            ENTRY_CONFIRMED, MONITORING -> ExitState.NONE
            EXIT_CONDITIONS_FORMING -> ExitState.FORMING
            EXIT_ALERT -> ExitState.ALERTED
            EXIT_CONDITIONS_MET -> ExitState.CONDITIONS_MET
            EXIT_CONFIRMED -> ExitState.CONFIRMED
        }

    val isTerminal: Boolean get() = this == EXIT_CONFIRMED || this == CANCELLED || this == EXPIRED

    /** Cancelling or expiring is only meaningful before the entry is confirmed. */
    val canCancelOrExpire: Boolean
        get() = entryState == EntryState.NOT_STARTED ||
            entryState == EntryState.WAITING || entryState == EntryState.CONDITIONS_MET
}

object OpportunityStateMachine {

    private val table: Map<OpportunityState, Set<OpportunityState>> = mapOf(
        // ALERTED may be skipped (alert suppressed by preferences).
        OpportunityState.OPPORTUNITY_FOUND to setOf(
            OpportunityState.ALERTED, OpportunityState.WAITING_FOR_ENTRY,
            OpportunityState.CANCELLED, OpportunityState.EXPIRED
        ),
        OpportunityState.ALERTED to setOf(
            OpportunityState.WAITING_FOR_ENTRY, OpportunityState.CANCELLED, OpportunityState.EXPIRED
        ),
        OpportunityState.WAITING_FOR_ENTRY to setOf(
            OpportunityState.ENTRY_CONDITIONS_MET, OpportunityState.CANCELLED, OpportunityState.EXPIRED
        ),
        // Conditions can lapse before confirmation, so it may fall back to waiting.
        OpportunityState.ENTRY_CONDITIONS_MET to setOf(
            OpportunityState.ENTRY_CONFIRMED, OpportunityState.WAITING_FOR_ENTRY,
            OpportunityState.CANCELLED, OpportunityState.EXPIRED
        ),
        // Exit conditions can be met suddenly (gap, hard stop), skipping the warning states.
        OpportunityState.ENTRY_CONFIRMED to setOf(
            OpportunityState.MONITORING, OpportunityState.EXIT_CONDITIONS_MET
        ),
        OpportunityState.MONITORING to setOf(
            OpportunityState.EXIT_CONDITIONS_FORMING, OpportunityState.EXIT_CONDITIONS_MET
        ),
        // A warning can fade without becoming an exit.
        OpportunityState.EXIT_CONDITIONS_FORMING to setOf(
            OpportunityState.EXIT_ALERT, OpportunityState.MONITORING, OpportunityState.EXIT_CONDITIONS_MET
        ),
        OpportunityState.EXIT_ALERT to setOf(
            OpportunityState.MONITORING, OpportunityState.EXIT_CONDITIONS_MET
        ),
        OpportunityState.EXIT_CONDITIONS_MET to setOf(
            OpportunityState.EXIT_CONFIRMED, OpportunityState.MONITORING
        ),
        OpportunityState.EXIT_CONFIRMED to emptySet(),
        OpportunityState.CANCELLED to emptySet(),
        OpportunityState.EXPIRED to emptySet()
    )

    fun allowedFrom(state: OpportunityState): Set<OpportunityState> = table.getValue(state)

    fun canTransition(from: OpportunityState, to: OpportunityState): Boolean = to in allowedFrom(from)
}
