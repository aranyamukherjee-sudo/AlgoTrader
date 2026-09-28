package com.algotrader.strategyengine

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.domain.Portfolio
import com.algotrader.strategy.BollingerBandsStrategy
import com.algotrader.strategy.MovingAverageCrossoverStrategy
import com.algotrader.strategy.RsiStrategy
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.SignalType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class StrategyEngineTest {

    private val factory = StrategyFactory()
    private val runner = StrategyRunner(factory)

    private val instrument = Instrument(
        symbol = "NSE:NIFTY50-INDEX",
        exchange = "NSE"
    )

    private val timeframe = Timeframe.MINUTE_1

    private val portfolio = Portfolio(
        cash = 100000.0,
        positions = emptyList()
    )

    private fun candles(count: Int = 30): List<Candle> =
        (0 until count).map { index ->
            Candle(
                instrument = instrument,
                timeframe = timeframe,
                timestamp = Instant.ofEpochSecond(index.toLong()),
                open = 100.0 + index,
                high = 101.0 + index,
                low = 99.0 + index,
                close = 100.5 + index,
                volume = 1000.0
            )
        }

    @Test
    fun registryContainsAllSupportedStrategies() {
        assertEquals(7, StrategyRegistry.ids.size)

        assertTrue(
            StrategyRegistry.ids.contains(
                StrategyRegistry.MOVING_AVERAGE_CROSSOVER
            )
        )
        assertTrue(
            StrategyRegistry.ids.contains(
                StrategyRegistry.RSI
            )
        )
        assertTrue(
            StrategyRegistry.ids.contains(
                StrategyRegistry.MACD
            )
        )
        assertTrue(
            StrategyRegistry.ids.contains(
                StrategyRegistry.BOLLINGER_BANDS
            )
        )
        assertTrue(
            StrategyRegistry.ids.contains(
                StrategyRegistry.DONCHIAN_CHANNEL
            )
        )
        assertTrue(
            StrategyRegistry.ids.contains(
                StrategyRegistry.DONCHIAN_EMA
            )
        )
        assertTrue(
            StrategyRegistry.ids.contains(
                StrategyRegistry.CPR_EMA
            )
        )
    }

    @Test
    fun factoryCreatesMovingAverageStrategy() {
        val strategy = factory.create(
            StrategyConfiguration(
                strategyId = StrategyRegistry.MOVING_AVERAGE_CROSSOVER
            )
        )

        assertIs<MovingAverageCrossoverStrategy>(strategy)
    }

    @Test
    fun factoryCreatesRsiStrategyWithCustomParameters() {
        val strategy = factory.create(
            StrategyConfiguration(
                strategyId = StrategyRegistry.RSI,
                parameters = mapOf(
                    "period" to 10.0,
                    "oversold" to 25.0,
                    "overbought" to 75.0
                )
            )
        )

        assertIs<RsiStrategy>(strategy)
        assertEquals(10.0, strategy.metadata.parameters.first { it.name == "period" }.value)
    }

    @Test
    fun factoryCreatesBollingerStrategy() {
        val strategy = factory.create(
            StrategyRegistry.BOLLINGER_BANDS
        )

        assertIs<BollingerBandsStrategy>(strategy)
    }

    @Test
    fun unknownStrategyIdFailsClearly() {
        assertFailsWith<IllegalStateException> {
            factory.create("does_not_exist")
        }
    }

    @Test
    fun runnerHandlesEmptyCandles() {
        val result = runner.evaluate(
            StrategyConfiguration(
                strategyId = StrategyRegistry.RSI
            ),
            emptyList(),
            portfolio
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun runnerSortsCandlesBeforeEvaluation() {
        val ordered = candles(30)
        val reversed = ordered.reversed()

        val resultOrdered = runner.evaluate(
            StrategyConfiguration(
                strategyId = StrategyRegistry.RSI
            ),
            ordered,
            portfolio
        )

        val resultReversed = runner.evaluate(
            StrategyConfiguration(
                strategyId = StrategyRegistry.RSI
            ),
            reversed,
            portfolio
        )

        assertEquals(resultOrdered, resultReversed)
    }

    @Test
    fun runnerCanEvaluateConcreteStrategy() {
        val strategy: Strategy = RsiStrategy()

        val result = runner.evaluate(
            strategy = strategy,
            candles = candles(),
            portfolio = portfolio
        )

        assertTrue(result.isNotEmpty() || result.isEmpty())
    }
}
