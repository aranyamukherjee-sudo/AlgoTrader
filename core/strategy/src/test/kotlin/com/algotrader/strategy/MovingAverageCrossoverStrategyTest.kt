package com.algotrader.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovingAverageCrossoverStrategyTest {

    private val strategy = MovingAverageCrossoverStrategy(fastPeriod = 2, slowPeriod = 4)

    @Test
    fun `no signal when there is insufficient history`() {
        val closes = listOf(100.0, 101.0, 102.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `emits a buy on a valid bullish crossover`() {
        // Falling prices keep the fast SMA at/below the slow SMA, then a
        // sharp rise pulls the fast SMA above the slow SMA on the last bar.
        val closes = listOf(100.0, 99.0, 98.0, 97.0, 96.0, 110.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.BUY, signals.first().type)
    }

    @Test
    fun `holds when both averages stay on the same side (no crossover)`() {
        // Smooth, steady uptrend: the fast average stays above the slow
        // average throughout, so there is no crossover on the last bar.
        val closes = listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.HOLD, signals.first().type)
    }
}
