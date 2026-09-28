package com.algotrader.backtest

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.strategyengine.StrategyConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

import java.time.Instant

class StrategyBacktestRunnerTest {

    private val instrument = Instrument(
        symbol = "TEST",
        exchange = "NSE"
    )

    private fun candles(): List<Candle> {
        val base = Instant.parse("2026-01-01T09:15:00Z")

        return listOf(
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_5,
                timestamp = base,
                open = 100.0,
                high = 102.0,
                low = 99.0,
                close = 101.0,
                volume = 1000.0
            ),
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_5,
                timestamp = base.plusSeconds(300),
                open = 101.0,
                high = 104.0,
                low = 100.0,
                close = 103.0,
                volume = 1100.0
            ),
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_5,
                timestamp = base.plusSeconds(600),
                open = 103.0,
                high = 106.0,
                low = 102.0,
                close = 105.0,
                volume = 1200.0
            ),
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_5,
                timestamp = base.plusSeconds(900),
                open = 105.0,
                high = 108.0,
                low = 104.0,
                close = 107.0,
                volume = 1300.0
            ),
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_5,
                timestamp = base.plusSeconds(1200),
                open = 107.0,
                high = 110.0,
                low = 106.0,
                close = 109.0,
                volume = 1400.0
            )
        )
    }

    @Test
    fun `runner executes strategy configuration through backtest engine`() {
        val runner = StrategyBacktestRunner()

        val result = runner.run(
            configuration = StrategyConfiguration(
                strategyId = "moving_average_crossover",
                parameters = mapOf(
                    "fastPeriod" to 2.0,
                    "slowPeriod" to 3.0
                )
            ),
            candles = candles(),
            backtestConfig = BacktestConfig(
                initialCapital = 100_000.0
            )
        )

        assertNotNull(result)
        assertEquals("Moving Average Crossover", result.strategyName)
        assertNotNull(result.metrics)
    }

    @Test
    fun `runner supports strategy id shorthand`() {
        val runner = StrategyBacktestRunner()

        val result = runner.run(
            strategyId = "rsi",
            candles = candles()
        )

        assertNotNull(result)
        assertEquals("RSI", result.strategyName)
    }

    @Test
    fun `runner rejects empty candle input`() {
        val runner = StrategyBacktestRunner()

        assertFailsWith<IllegalArgumentException> {
            runner.run(
                strategyId = "rsi",
                candles = emptyList()
            )
        }
    }

    @Test
    fun `runner propagates unknown strategy id`() {
        val runner = StrategyBacktestRunner()

        assertFailsWith<IllegalStateException> {
            runner.run(
                strategyId = "does_not_exist",
                candles = candles()
            )
        }
    }
}
