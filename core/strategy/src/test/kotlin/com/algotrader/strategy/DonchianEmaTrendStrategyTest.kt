package com.algotrader.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DonchianEmaTrendStrategyTest {

    private val strategy = DonchianEmaTrendStrategy(donchianPeriod = 3, emaPeriod = 3)

    @Test
    fun `no signal when there is insufficient history`() {
        val candles = listOf(
            testCandle(0, high = 100.0, low = 90.0, close = 95.0),
            testCandle(1, high = 101.0, low = 91.0, close = 96.0)
        )
        val signals = strategy.evaluate(contextOf(candles))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `emits a buy on a breakout confirmed by the ema`() {
        val candles = listOf(
            testCandle(0, high = 105.0, low = 95.0, close = 100.0),
            testCandle(1, high = 110.0, low = 96.0, close = 105.0),
            testCandle(2, high = 108.0, low = 97.0, close = 104.0),
            testCandle(3, high = 115.0, low = 104.0, close = 112.0),
            testCandle(4, high = 130.0, low = 110.0, close = 120.0)
        )
        val signals = strategy.evaluate(contextOf(candles))

        assertEquals(1, signals.size)
        assertEquals(SignalType.BUY, signals.first().type)
    }

    @Test
    fun `emits a sell on a breakdown confirmed by the ema`() {
        val candles = listOf(
            testCandle(0, high = 105.0, low = 95.0, close = 100.0),
            testCandle(1, high = 106.0, low = 96.0, close = 101.0),
            testCandle(2, high = 104.0, low = 94.0, close = 99.0),
            testCandle(3, high = 100.0, low = 85.0, close = 90.0),
            testCandle(4, high = 95.0, low = 70.0, close = 75.0)
        )
        val signals = strategy.evaluate(contextOf(candles))

        assertEquals(1, signals.size)
        assertEquals(SignalType.SELL, signals.first().type)
    }

    @Test
    fun `does not signal a false breakout from the current candle's own extreme high`() {
        val candles = listOf(
            testCandle(0, high = 100.0, low = 90.0, close = 95.0),
            testCandle(1, high = 98.0, low = 88.0, close = 94.0),
            testCandle(2, high = 99.0, low = 89.0, close = 95.0),
            testCandle(3, high = 150.0, low = 90.0, close = 96.0)
        )
        val signals = strategy.evaluate(contextOf(candles))

        assertEquals(1, signals.size)
        assertEquals(SignalType.HOLD, signals.first().type)
    }
}
