package com.algotrader.backtest

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Portfolio
import com.algotrader.domain.Timeframe
import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.Signal
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.StrategyContext
import com.algotrader.strategy.StrategyMetadata
import java.time.Instant

internal val TEST_INSTRUMENT = Instrument(symbol = "TEST", exchange = "NSE")

internal fun testCandle(
    index: Int,
    close: Double,
    open: Double = close,
    high: Double = maxOf(open, close),
    low: Double = minOf(open, close)
): Candle = Candle(
    instrument = TEST_INSTRUMENT,
    timeframe = Timeframe.DAY_1,
    timestamp = Instant.EPOCH.plusSeconds(index * 86_400L),
    open = open,
    high = high,
    low = low,
    close = close,
    volume = 100.0
)

/** Builds one candle per (open, close) pair, indexed from 0. */
internal fun testCandles(bars: List<Pair<Double, Double>>): List<Candle> =
    bars.mapIndexed { index, (open, close) -> testCandle(index, close = close, open = open) }

/**
 * A strategy whose signal at each bar index is fully scripted, for
 * deterministic engine tests. Unmapped bars hold.
 */
internal class ScriptedStrategy(
    private val signalsByIndex: Map<Int, SignalType>,
    override val metadata: StrategyMetadata = StrategyMetadata(
        description = "test strategy",
        requiredIndicators = emptyList(),
        parameters = emptyList(),
        direction = PositionDirection.LONG_AND_SHORT,
        entryRule = "scripted",
        exitRule = "scripted"
    )
) : Strategy {
    override val name: String = "Scripted"

    override fun evaluate(context: StrategyContext): List<Signal> {
        val index = context.candles.lastIndex
        val type = signalsByIndex[index] ?: SignalType.HOLD
        val bar = context.candles.last()
        return listOf(Signal(instrument = bar.instrument, type = type, timestamp = bar.timestamp))
    }
}

internal fun contextOf(candles: List<Candle>): StrategyContext = StrategyContext(
    candles = candles,
    portfolio = Portfolio(cash = 100_000.0)
)
