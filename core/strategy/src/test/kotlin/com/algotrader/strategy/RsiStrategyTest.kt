package com.algotrader.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RsiStrategyTest {

    private val strategy = RsiStrategy(period = 14, oversold = 30.0, overbought = 70.0)

    @Test
    fun `no signal when there is insufficient history`() {
        val closes = (1..10).map { 100.0 + it }
        val signals = strategy.evaluate(contextOf(testCandles(closes)))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `emits a buy when rsi is oversold`() {
        // Strictly falling closes: every change is a loss, so RSI bottoms out at 0.
        val closes = (0..20).map { 200.0 - it }
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.BUY, signals.first().type)
    }

    @Test
    fun `emits a sell when rsi is overbought`() {
        // Strictly rising closes: every change is a gain, so RSI tops out at 100.
        val closes = (0..20).map { 100.0 + it }
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.SELL, signals.first().type)
    }

    @Test
    fun `holds when rsi is in the neutral zone`() {
        // Alternating up/down closes of equal size keep gains and losses balanced.
        val closes = (0..20).map { if (it % 2 == 0) 100.0 else 101.0 }
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.HOLD, signals.first().type)
    }
}
