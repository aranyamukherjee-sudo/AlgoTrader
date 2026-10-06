package com.algotrader.discovery.candidate

import com.algotrader.discovery.features.FeatureTypes
import com.algotrader.discovery.reproducibility.DnaCanonical
import com.algotrader.discovery.reproducibility.Hashing
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.FeatureDomain
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.dna.MarketScope
import com.algotrader.intelligence.dna.ParamValue
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyOrigin
import com.algotrader.intelligence.dna.TradeSide

/**
 * Proposes candidate strategies as ordinary ASI-1 [StrategyDna]. There is no
 * second candidate-definition system: anything that can produce DNA can plug
 * in here.
 */
fun interface CandidateGenerator {
    fun generate(market: MarketScope, timeframe: Timeframe): List<StrategyDna>
}

/**
 * A small, deterministic, exhaustive grammar over the starter features:
 *
 *   ENTRY    close breaks above the prior N-bar high
 *   CONTEXT  optional: short-term trend up; optional: range contraction just before
 *   CONFIRM  optional: current bar's range expansion
 *   EXIT     close breaks below the prior M-bar low
 *
 * Exhaustive enumeration in a fixed order, so the same inputs always give the
 * same candidates in the same order, with ids that are hashes of the rules.
 * It is a proof of the architecture, not a claim that these rules have an edge.
 */
class BreakoutCandidateGenerator(
    private val breakoutLookbacks: List<Int> = listOf(10, 20, 40),
    private val exitLookbacks: List<Int> = listOf(5, 10)
) : CandidateGenerator {

    init {
        require(breakoutLookbacks.isNotEmpty() && exitLookbacks.isNotEmpty()) { "lookback lists must not be empty" }
        require(breakoutLookbacks.all { it >= 1 } && exitLookbacks.all { it >= 1 }) { "lookbacks must be at least 1" }
        require(breakoutLookbacks.toSet().size == breakoutLookbacks.size) { "breakout lookbacks must be distinct" }
        require(exitLookbacks.toSet().size == exitLookbacks.size) { "exit lookbacks must be distinct" }
    }

    override fun generate(market: MarketScope, timeframe: Timeframe): List<StrategyDna> {
        val out = ArrayList<StrategyDna>()
        for (n in breakoutLookbacks) {
            for (m in exitLookbacks) {
                for (contraction in listOf(false, true)) {
                    for (trend in listOf(false, true)) {
                        for (expansion in listOf(false, true)) {
                            out += build(market, timeframe, n, m, contraction, trend, expansion)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun num(v: Int) = ParamValue.Num(v.toDouble())

    private fun build(
        market: MarketScope,
        timeframe: Timeframe,
        n: Int,
        m: Int,
        contraction: Boolean,
        trend: Boolean,
        expansion: Boolean
    ): StrategyDna {
        val breakoutUp = FeatureRef(
            FeatureDomain.PRICE_ACTION, FeatureTypes.RANGE_BREAKOUT,
            mapOf("lookback" to num(n), "direction" to ParamValue.Text("up"))
        )
        val breakoutDown = FeatureRef(
            FeatureDomain.PRICE_ACTION, FeatureTypes.RANGE_BREAKOUT,
            mapOf("lookback" to num(m), "direction" to ParamValue.Text("down"))
        )

        val context = ArrayList<Condition>()
        if (trend) {
            context += Condition.Leaf(
                "trend_up",
                FeatureRef(
                    FeatureDomain.TREND, FeatureTypes.MA_RELATION,
                    mapOf("fast" to num(10), "slow" to num(30), "relation" to ParamValue.Text("above"))
                ),
                "Average close of the last 10 bars is above that of the last 30 bars"
            )
        }
        if (contraction) {
            context += Condition.Leaf(
                "contraction",
                FeatureRef(
                    FeatureDomain.VOLATILITY, FeatureTypes.RANGE_CONTRACTION,
                    mapOf("shortBars" to num(5), "longBars" to num(20), "ratio" to ParamValue.Num(0.7))
                ),
                "Average range of the 5 bars before this one is below 70% of the 20 bars before those"
            )
        }
        val confirmation = if (expansion) {
            Condition.Leaf(
                "expansion",
                FeatureRef(
                    FeatureDomain.VOLATILITY, FeatureTypes.RANGE_EXPANSION,
                    mapOf("bars" to num(10), "multiplier" to ParamValue.Num(1.5))
                ),
                "This bar's range is above 1.5 times the average range of the prior 10 bars"
            )
        } else {
            null
        }

        val suffix = listOfNotNull(
            if (trend) "trend" else null,
            if (contraction) "contraction" else null,
            if (expansion) "expansion" else null
        ).joinToString("+")
        val pending = StrategyDna(
            id = StrategyId("disc.pending"),
            name = "Breakout $n / exit $m" + if (suffix.isEmpty()) "" else " ($suffix)",
            origin = StrategyOrigin.DISCOVERED,
            market = market,
            timeframe = timeframe,
            sides = setOf(TradeSide.LONG),
            structures = listOf(breakoutUp),
            requiredContext = if (context.isEmpty()) null else Condition.AllOf(context),
            entry = Condition.Leaf("breakout", breakoutUp, "Close breaks above the prior $n-bar high"),
            confirmation = confirmation,
            exit = Condition.Leaf("breakdown", breakoutDown, "Close breaks below the prior $m-bar low"),
            description = "Generated breakout candidate (research only; not validated)"
        )
        return pending.copy(id = StrategyId("disc." + Hashing.short(DnaCanonical.key(pending))))
    }
}
