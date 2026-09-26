package com.algotrader.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DonchianChannelStrategyTest {

    private val strategy = DonchianChannelStrategy(period = 3)

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
    fun `emits a buy on a valid upside breakout`() {
        // Bars 0-2 establish a channel with an upper bound of 110. Bar 3
        // closes above it (112), a genuine breakout of the *prior* channel.
        val candles = listOf(
            testCandle(0, high = 105.0, low = 95.0, close = 100.0),
            testCandle(1, high = 110.0, low = 96.0, close = 105.0),
            testCandle(2, high = 108.0, low = 97.0, close = 104.0),
            testCandle(3, high = 115.0, low = 104.0, close = 112.0)
        )
        val signals = strategy.evaluate(contextOf(candles))

        assertEquals(1, signals.size)
        assertEquals(SignalType.BUY, signals.first().type)
    }

    @Test
    fun `emits a sell on a valid downside breakout`() {
        // Bars 0-2 establish a channel with a lower bound of 94. Bar 3
        // closes below it (90), a genuine breakdown of the *prior* channel.
        val candles = listOf(
            testCandle(0, high = 105.0, low = 95.0, close = 100.0),
            testCandle(1, high = 106.0, low = 96.0, close = 101.0),
            testCandle(2, high = 104.0, low = 94.0, close = 99.0),
            testCandle(3, high = 100.0, low = 85.0, close = 90.0)
        )
        val signals = strategy.evaluate(contextOf(candles))

        assertEquals(1, signals.size)
        assertEquals(SignalType.SELL, signals.first().type)
    }

    @Test
    fun `does not signal a false breakout from the current candle's own extreme high`() {
        // Bar 3 prints a dramatic new high (150) that would trivially look
        // like a breakout if the channel included the current bar (its
        // upper channel would then just be its own high), or if the
        // strategy checked the current high instead of the close. The
        // close (96) is unremarkable relative to the *prior* channel
        // (upper 100, from bars 0-2), so the correct behaviour is HOLD.
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
