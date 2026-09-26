package com.algotrader.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacdStrategyTest {

    private val strategy = MacdStrategy(fastPeriod = 2, slowPeriod = 4, signalPeriod = 2)

    @Test
    fun `no signal when there is insufficient history`() {
        val closes = listOf(100.0, 101.0, 102.0, 103.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `emits a buy on a bullish macd-signal crossover`() {
        // Falling prices push MACD at/below its signal line, then a sharp
        // rise pushes MACD above the (lagging) signal line on the last bar.
        val closes = listOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0, 120.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.BUY, signals.first().type)
    }

    @Test
    fun `emits a sell on a bearish macd-signal crossover`() {
        // Mirror image of the bullish case: rising then a sharp drop.
        val closes = listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 80.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.SELL, signals.first().type)
    }

    @Test
    fun `holds with no crossover during a steady trend`() {
        val closes = listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.HOLD, signals.first().type)
    }
}
