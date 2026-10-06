package com.algotrader.intelligence

import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.common.getOrThrow
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.EvidenceRef
import com.algotrader.intelligence.dna.FeatureDomain
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.dna.InstrumentKind
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.MarketScope
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyOrigin
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.dna.TradeSide
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidencePolarity
import com.algotrader.intelligence.evidence.EvidenceSample
import com.algotrader.intelligence.evidence.EvidenceSource
import com.algotrader.intelligence.evidence.EvidenceSourceType
import com.algotrader.intelligence.lifecycle.StrategyRecord
import com.algotrader.intelligence.opportunity.Opportunity
import com.algotrader.intelligence.opportunity.OpportunityId
import com.algotrader.intelligence.opportunity.OpportunityState
import java.time.Instant

object TestFixtures {
    val T0: Instant = Instant.parse("2026-10-06T04:00:00Z")
    fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)

    val niftyIndex = InstrumentRef(Instrument("NIFTY 50", "NSE"), InstrumentKind.INDEX)
    val niftyFuture = InstrumentRef(
        Instrument("NIFTY-FUT", "NSE"), InstrumentKind.FUTURES, underlying = "NIFTY", contractId = "NIFTY-FUT-1"
    )

    fun leaf(id: String, type: String = "feature", domain: FeatureDomain = FeatureDomain.PRICE_ACTION) =
        Condition.Leaf(id, FeatureRef(domain, type), "description of $id")

    /** A price-action-only strategy (no indicators), generated-style, to prove DNA is not indicator-bound. */
    fun priceActionDna(
        id: String = "gen.double_bottom",
        version: Int = 1,
        tradeTarget: InstrumentRef? = niftyFuture
    ) = StrategyDna(
        id = StrategyId(id),
        version = version,
        name = "Double bottom breakout",
        origin = StrategyOrigin.GENERATED,
        market = MarketScope(signalSource = niftyIndex, tradeTarget = tradeTarget),
        timeframe = Timeframe.MINUTE_15,
        sides = setOf(TradeSide.LONG),
        structures = listOf(FeatureRef(FeatureDomain.CHART_STRUCTURE, "double_bottom")),
        requiredContext = Condition.AllOf(listOf(leaf("trend", "trend_alignment", FeatureDomain.TREND))),
        entry = Condition.AllOf(listOf(leaf("neckline_break"), leaf("break_confirmation"))),
        exit = Condition.AnyOf(listOf(leaf("structure_failure"), leaf("trailing"))),
        invalidation = leaf("lower_low_than_bottom")
    )

    fun researchEvidence(
        id: String,
        strategy: StrategyRef,
        kind: EvidenceKind = EvidenceKind.OUT_OF_SAMPLE_PERFORMANCE,
        sample: EvidenceSample = EvidenceSample.OUT_OF_SAMPLE,
        polarity: EvidencePolarity = EvidencePolarity.SUPPORTING,
        at: Instant = T0
    ) = EvidenceItem(
        id = EvidenceRef(id),
        strategy = strategy,
        kind = kind,
        sample = sample,
        summary = "evidence $id",
        source = EvidenceSource(EvidenceSourceType.BACKTEST_RUN, "backtest-job-$id"),
        recordedAt = at,
        polarity = polarity
    )

    fun liveEvidence(id: String, strategy: StrategyRef, kind: EvidenceKind = EvidenceKind.TREND_ALIGNMENT) =
        EvidenceItem(
            id = EvidenceRef(id),
            strategy = strategy,
            kind = kind,
            sample = EvidenceSample.LIVE,
            summary = "live evidence $id",
            source = EvidenceSource(EvidenceSourceType.LIVE_MARKET_DATA, "snapshot-$id"),
            recordedAt = T0
        )

    fun record(dna: StrategyDna = priceActionDna()) = StrategyRecord(dna = dna, createdAt = T0)

    fun opportunity(
        strategy: StrategyRef = priceActionDna().ref,
        confidence: Double? = 0.68,
        instrument: InstrumentRef = niftyFuture
    ) = Opportunity.detected(
        id = OpportunityId("opp-1"),
        strategy = strategy,
        instrument = instrument,
        timeframe = Timeframe.MINUTE_15,
        side = TradeSide.LONG,
        at = T0,
        confidence = confidence
    )

    /** Moves an opportunity to [target] (asserting the move is legal), [minutes] after T0. */
    fun Opportunity.step(target: OpportunityState, minutes: Long, confidence: Double? = null): Opportunity =
        advanceTo(target, at(minutes), "to $target", confidence).getOrThrow()
}
