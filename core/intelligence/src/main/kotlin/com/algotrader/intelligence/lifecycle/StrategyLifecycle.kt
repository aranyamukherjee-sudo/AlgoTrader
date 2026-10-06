package com.algotrader.intelligence.lifecycle

/**
 * Where a strategy version is in its life. This is about the STRATEGY, never
 * about one opportunity.
 *
 * - DISCOVERED  proposed (by a user, a built-in, or later discovery); not yet tested.
 * - VALIDATING  being tested (in-sample / validation / out-of-sample).
 * - PROMISING   has research evidence of a historical edge; not yet watched live.
 * - MONITORING  enrolled in live scanning for setups.
 * - ACTIVE      trusted for live use, so its opportunities are surfaced per alert preferences.
 * - DEGRADING   its evidence is weakening; no new live opportunities.
 * - RETIRED     terminal. A revived idea becomes a new DNA version/id.
 */
enum class StrategyLifecycle {
    DISCOVERED,
    VALIDATING,
    PROMISING,
    MONITORING,
    ACTIVE,
    DEGRADING,
    RETIRED;

    val isTerminal: Boolean get() = this == RETIRED

    /** Only these states may produce new live opportunities. */
    val canDetectOpportunities: Boolean get() = this == MONITORING || this == ACTIVE

    fun allowedNext(): Set<StrategyLifecycle> = StrategyLifecycleRules.allowedFrom(this)

    fun canTransitionTo(target: StrategyLifecycle): Boolean = target in allowedNext()
}

object StrategyLifecycleRules {
    private val table: Map<StrategyLifecycle, Set<StrategyLifecycle>> = mapOf(
        StrategyLifecycle.DISCOVERED to setOf(StrategyLifecycle.VALIDATING, StrategyLifecycle.RETIRED),
        StrategyLifecycle.VALIDATING to setOf(StrategyLifecycle.PROMISING, StrategyLifecycle.RETIRED),
        StrategyLifecycle.PROMISING to setOf(
            StrategyLifecycle.MONITORING, StrategyLifecycle.VALIDATING, StrategyLifecycle.RETIRED
        ),
        StrategyLifecycle.MONITORING to setOf(
            StrategyLifecycle.ACTIVE, StrategyLifecycle.DEGRADING, StrategyLifecycle.RETIRED
        ),
        StrategyLifecycle.ACTIVE to setOf(
            StrategyLifecycle.MONITORING, StrategyLifecycle.DEGRADING, StrategyLifecycle.RETIRED
        ),
        StrategyLifecycle.DEGRADING to setOf(StrategyLifecycle.VALIDATING, StrategyLifecycle.RETIRED),
        StrategyLifecycle.RETIRED to emptySet()
    )

    fun allowedFrom(state: StrategyLifecycle): Set<StrategyLifecycle> = table.getValue(state)
}
