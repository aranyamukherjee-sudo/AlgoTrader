package com.algotrader.intelligence.opportunity

import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyRef

/**
 * Read-only filters for querying registered opportunities.
 *
 * A null field imposes no restriction. An empty [states] set means any state.
 * When [includeTerminal] is false, terminal states are excluded even if
 * explicitly included in [states].
 *
 * Opportunity IDs are not guaranteed to be unique. An ID filter can
 * therefore return more than one occurrence.
 */
data class OpportunityQuery(
    val id: OpportunityId? = null,
    val strategy: StrategyRef? = null,
    val instrument: InstrumentRef? = null,
    val timeframe: Timeframe? = null,
    val states: Set<OpportunityState> = emptySet(),
    val includeTerminal: Boolean = true
)
