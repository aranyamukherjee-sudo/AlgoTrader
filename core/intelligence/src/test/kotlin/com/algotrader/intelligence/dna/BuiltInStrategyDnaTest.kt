package com.algotrader.intelligence.dna

import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.TestFixtures
import com.algotrader.strategy.BollingerBandsStrategy
import com.algotrader.strategy.CprEmaTrendStrategy
import com.algotrader.strategy.DonchianChannelStrategy
import com.algotrader.strategy.DonchianEmaTrendStrategy
import com.algotrader.strategy.MacdStrategy
import com.algotrader.strategy.MovingAverageCrossoverStrategy
import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.RsiStrategy
import com.algotrader.strategy.Signal
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.StrategyContext
import com.algotrader.strategy.StrategyMetadata
import com.algotrader.strategy.StrategyParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInStrategyDnaTest {

    private val market = MarketScope(TestFixtures.niftyIndex, TestFixtures.niftyFuture)

    private val builtIns: List<Triple<String, Strategy, Set<TradeSide>>> = listOf(
        Triple("moving_average_crossover", MovingAverageCrossoverStrategy(fastPeriod = 5, slowPeriod = 10), setOf(TradeSide.LONG)),
        Triple("rsi", RsiStrategy(), setOf(TradeSide.LONG, TradeSide.SHORT)),
        Triple("macd", MacdStrategy(fastPeriod = 12, slowPeriod = 26, signalPeriod = 9), setOf(TradeSide.LONG, TradeSide.SHORT)),
        Triple("bollinger_bands", BollingerBandsStrategy(period = 20, stdDevMultiplier = 2.0), setOf(TradeSide.LONG, TradeSide.SHORT)),
        Triple("donchian_channel", DonchianChannelStrategy(period = 20), setOf(TradeSide.LONG, TradeSide.SHORT)),
        Triple("donchian_ema", DonchianEmaTrendStrategy(donchianPeriod = 20, emaPeriod = 50), setOf(TradeSide.LONG, TradeSide.SHORT)),
        Triple("cpr_ema", CprEmaTrendStrategy(emaPeriod = 20), setOf(TradeSide.LONG, TradeSide.SHORT))
    )

    @Test
    fun `all seven built in strategies can be described as strategy dna`() {
        assertEquals(7, builtIns.size)
        for ((engineId, strategy, expectedSides) in builtIns) {
            val dna = BuiltInStrategyDna.from(engineId, strategy, market, Timeframe.MINUTE_15)

            assertEquals(StrategyId("builtin.$engineId"), dna.id)
            assertEquals(StrategyOrigin.BUILT_IN, dna.origin)
            assertEquals(expectedSides, dna.sides, engineId)
            assertEquals(engineId, dna.engineBinding?.engineStrategyId)
            assertTrue(dna.indicators.isNotEmpty(), engineId)
            assertEquals(StrategyComposition.INDICATOR_BASED, dna.composition)
            assertEquals(FeatureDomain.ENGINE_RULE, (dna.entry as Condition.Leaf).feature.domain)
            assertEquals(strategy.metadata.exitRule, (dna.exit as Condition.Leaf).description)
        }
    }

    @Test
    fun `rsi parameters and indicator are carried over`() {
        val dna = BuiltInStrategyDna.from("rsi", RsiStrategy(), market, Timeframe.DAY_1)
        val indicator = dna.indicators.single()

        assertEquals("RSI", indicator.type)
        assertEquals(ParamValue.Num(14.0), indicator.params["arg0"])
        assertEquals(14.0, dna.engineBinding?.parameters?.get("period"))
        assertEquals(30.0, dna.engineBinding?.parameters?.get("oversold"))
    }

    @Test
    fun `indicator specs are parsed into type and positional params`() {
        val bollinger = BuiltInStrategyDna.parseIndicator("BollingerBands(20, 2.0)")
        assertEquals("BollingerBands", bollinger.type)
        assertEquals(ParamValue.Num(20.0), bollinger.params["arg0"])
        assertEquals(ParamValue.Num(2.0), bollinger.params["arg1"])

        assertEquals(ParamValue.Text("daily"), BuiltInStrategyDna.parseIndicator("CPR(daily)").params["arg0"])
        assertTrue(BuiltInStrategyDna.parseIndicator("Plain").params.isEmpty())
    }

    @Test
    fun `any future engine strategy is describable, not only the seven`() {
        val custom = object : Strategy {
            override val name = "Custom"
            override val metadata = StrategyMetadata(
                description = "custom",
                requiredIndicators = emptyList(),
                parameters = listOf(StrategyParameter("x", 1.0)),
                direction = PositionDirection.SHORT_ONLY,
                entryRule = "custom entry",
                exitRule = "custom exit",
                stopLossPercent = 2.0
            )

            override fun evaluate(context: StrategyContext): List<Signal> = emptyList()
        }
        val dna = BuiltInStrategyDna.from("custom", custom, market, Timeframe.HOUR_1)

        assertEquals(setOf(TradeSide.SHORT), dna.sides)
        assertEquals(2.0, dna.risk.stopLossPercent)
        assertEquals(StrategyComposition.RULE_BASED, dna.composition)
    }
}
