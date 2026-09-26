package com.algotrader.strategy

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Portfolio
import com.algotrader.domain.Timeframe
import java.time.Instant

/** Deterministic synthetic candle builders shared by the strategy tests. */

internal val TEST_INSTRUMENT = Instrument(symbol = "TEST", exchange = "NSE")

internal fun testCandle(
    index: Int,
    close: Double,
    high: Double = close,
    low: Double = close,
    open: Double = close
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

/** Builds one candle per close, with open == high == low == close. */
internal fun testCandles(closes: List<Double>): List<Candle> =
    closes.mapIndexed { index, close -> testCandle(index, close) }

internal fun contextOf(candles: List<Candle>): StrategyContext = StrategyContext(
    candles = candles,
    portfolio = Portfolio(cash = 100_000.0)
)
