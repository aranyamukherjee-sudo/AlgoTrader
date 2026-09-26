package com.algotrader.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BollingerBandsStrategyTest {

    private val strategy = BollingerBandsStrategy(period = 5, stdDevMultiplier = 2.0)

    @Test
    fun `no signal when there is insufficient history`() {
        val closes = listOf(100.0, 101.0, 102.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `emits a buy when price touches the lower band`() {
        val closes = listOf(100.0, 100.0, 100.0, 100.0, 80.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.BUY, signals.first().type)
    }

    @Test
    fun `emits a sell when price touches the upper band`() {
        val closes = listOf(100.0, 100.0, 100.0, 100.0, 120.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.SELL, signals.first().type)
    }

    @Test
    fun `holds when price is inside the bands`() {
        val closes = listOf(100.0, 101.0, 99.0, 100.0, 100.0)
        val signals = strategy.evaluate(contextOf(testCandles(closes)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.HOLD, signals.first().type)
    }
}
