package com.algotrader.discovery.rules

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestEngine
import com.algotrader.backtest.ExitReason
import com.algotrader.discovery.candidate.BreakoutCandidateGenerator
import com.algotrader.discovery.features.FeatureRegistry
import com.algotrader.discovery.fixtures.DiscoveryFixtures
import com.algotrader.discovery.fixtures.DiscoveryFixtures.named
import com.algotrader.discovery.fixtures.SyntheticCandles
import com.algotrader.domain.Candle
import com.algotrader.domain.Portfolio
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.FeatureDomain
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyOrigin
import com.algotrader.intelligence.dna.TradeSide
import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.StrategyContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleStrategyTest {

    private val registry = FeatureRegistry.standard()
        .withFeature("always_true") { _, _ -> true }
        .withFeature("always_false") { _, _ -> false }

    private fun leaf(id: String, type: String) =
        Condition.Leaf(id, FeatureRef(FeatureDomain.CUSTOM, type), "rule $id ($type)")

    private fun dna(
        entry: Condition,
        exit: Condition,
        context: Condition? = null,
        confirmation: Condition? = null,
        invalidation: Condition? = null,
        sides: Set<TradeSide> = setOf(TradeSide.LONG)
    ) = StrategyDna(
        id = StrategyId("test.rule"),
        name = "Rule test",
        origin = StrategyOrigin.GENERATED,
        market = DiscoveryFixtures.futuresMarket,
        timeframe = Timeframe.MINUTE_15,
        sides = sides,
        requiredContext = context,
        entry = entry,
        confirmation = confirmation,
        exit = exit,
        invalidation = invalidation
    )

    private val candles: List<Candle> = SyntheticCandles.allStrong.take(5)
    private fun signalOf(d: StrategyDna): SignalType? =
        RuleStrategy(d, registry).evaluate(StrategyContext(candles, Portfolio(0.0))).firstOrNull()?.type

    @Test
    fun `conditions combine with and-or`() {
        val t = leaf("t", "always_true")
        val f = leaf("f", "always_false")
        fun eval(c: Condition) = RuleEvaluator.evaluate(c, registry, candles)
        assertTrue(eval(Condition.AllOf(listOf(t, t))))
        assertTrue(!eval(Condition.AllOf(listOf(t, f))))
        assertTrue(eval(Condition.AnyOf(listOf(f, t))))
        assertTrue(!eval(Condition.AnyOf(listOf(f, f))))
        assertTrue(eval(Condition.AllOf(listOf(t, Condition.AnyOf(listOf(f, t))))))
    }

    @Test
    fun `entry rule produces a BUY`() {
        assertEquals(SignalType.BUY, signalOf(dna(entry = leaf("e", "always_true"), exit = leaf("x", "always_false"))))
    }

    @Test
    fun `no signal when nothing fires`() {
        assertEquals(null, signalOf(dna(entry = leaf("e", "always_false"), exit = leaf("x", "always_false"))))
    }

    @Test
    fun `exit always wins over entry on the same bar`() {
        assertEquals(SignalType.SELL, signalOf(dna(entry = leaf("e", "always_true"), exit = leaf("x", "always_true"))))
    }

    @Test
    fun `invalidation closes and blocks entry`() {
        val d = dna(
            entry = leaf("e", "always_true"), exit = leaf("x", "always_false"),
            invalidation = leaf("i", "always_true")
        )
        assertEquals(SignalType.SELL, signalOf(d))
    }

    @Test
    fun `context and confirmation must both hold for an entry`() {
        val entry = leaf("e", "always_true")
        val exit = leaf("x", "always_false")
        assertEquals(null, signalOf(dna(entry, exit, context = leaf("c", "always_false"))))
        assertEquals(null, signalOf(dna(entry, exit, confirmation = leaf("k", "always_false"))))
        assertEquals(
            SignalType.BUY,
            signalOf(dna(entry, exit, context = leaf("c", "always_true"), confirmation = leaf("k", "always_true")))
        )
    }

    @Test
    fun `empty history gives no signal and metadata describes a long-only rule`() {
        val strategy = RuleStrategy(dna(leaf("e", "always_true"), leaf("x", "always_false")), registry)
        assertTrue(strategy.evaluate(StrategyContext(emptyList(), Portfolio(0.0))).isEmpty())
        assertEquals(PositionDirection.LONG_ONLY, strategy.metadata.direction)
        assertTrue(strategy.metadata.entryRule.contains("always_true"))
    }

    @Test
    fun `unsupported dna is refused up front`() {
        assertFailsWith<IllegalArgumentException> {
            RuleStrategy(
                dna(leaf("e", "always_true"), leaf("x", "always_false"), sides = setOf(TradeSide.SHORT)), registry
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RuleStrategy(dna(leaf("e", "no_such_feature"), leaf("x", "always_false")), registry)
        }
    }

    @Test
    fun `a generated candidate runs through the existing backtest engine`() {
        val dna = BreakoutCandidateGenerator()
            .generate(DiscoveryFixtures.futuresMarket, Timeframe.MINUTE_15)
            .first { it.name == DiscoveryFixtures.SIMPLE }
        val result = BacktestEngine(BacktestConfig()).run(RuleStrategy(dna, FeatureRegistry.standard()), SyntheticCandles.allStrong)

        val first = result.trades.first()
        // breakout on bar 20's close -> filled at bar 21's open; exit fires on bar 32's close -> filled at bar 33's open
        assertEquals(21, first.entryIndex)
        assertEquals(102.0, first.entryPrice)
        assertEquals(33, first.exitIndex)
        assertEquals(105.0, first.exitPrice)
        assertEquals(ExitReason.STRATEGY_SIGNAL, first.exitReason)
    }

    @Test
    fun `rules cannot see the future`() {
        val dna = BreakoutCandidateGenerator()
            .generate(DiscoveryFixtures.futuresMarket, Timeframe.MINUTE_15)
            .first { it.name == DiscoveryFixtures.SIMPLE }
        val strategy = RuleStrategy(dna, FeatureRegistry.standard())
        val engine = BacktestEngine(BacktestConfig())

        val full = engine.run(strategy, SyntheticCandles.allStrong)
        val prefix = engine.run(strategy, SyntheticCandles.allStrong.take(500))

        // every trade that closed well before the prefix ends is identical with or without the later bars
        val fromFull = full.trades.filter { it.exitIndex < 490 }
        val fromPrefix = prefix.trades.filter { it.exitIndex < 490 }
        assertTrue(fromFull.isNotEmpty())
        assertEquals(fromFull, fromPrefix)
    }
}
