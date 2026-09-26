package com.algotrader.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CprEmaTrendStrategyTest {

    private val strategy = CprEmaTrendStrategy(emaPeriod = 3)

    // Each testCandle is exactly one day apart (see TestCandles.kt), so
    // every candle here falls on its own calendar day and the daily-CPR
    // grouping lines up one prior-day CPR per bar.
    private fun bullishSetupPlus(lastClose: Double) = listOf(
        testCandle(0, high = 105.0, low = 95.0, close = 100.0),
        testCandle(1, high = 104.0, low = 94.0, close = 99.0),
        testCandle(2, high = 106.0, low = 96.0, close = 101.0),
        // Day 3's range feeds day 4's CPR: pivot ~121.67, TC ~123.33, BC 120.
        testCandle(3, high = 130.0, low = 110.0, close = 125.0),
        testCandle(4, high = 150.0, low = 100.0, close = lastClose)
    )

    @Test
    fun `no signal when there is insufficient history`() {
        val candles = listOf(testCandle(0, close = 100.0), testCandle(1, close = 101.0))
        val signals = strategy.evaluate(contextOf(candles))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `emits a buy when price is above both the ema and the prior day's cpr top-central`() {
        val signals = strategy.evaluate(contextOf(bullishSetupPlus(lastClose = 140.0)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.BUY, signals.first().type)
    }

    @Test
    fun `emits a sell when price is below both the ema and the prior day's cpr bottom-central`() {
        val candles = listOf(
            testCandle(0, high = 105.0, low = 95.0, close = 100.0),
            testCandle(1, high = 104.0, low = 94.0, close = 99.0),
            testCandle(2, high = 106.0, low = 96.0, close = 101.0),
            // Day 3's range feeds day 4's CPR: pivot ~98.33, TC ~96.67, BC 100.
            testCandle(3, high = 110.0, low = 90.0, close = 95.0),
            testCandle(4, high = 100.0, low = 40.0, close = 50.0)
        )
        val signals = strategy.evaluate(contextOf(candles))

        assertEquals(1, signals.size)
        assertEquals(SignalType.SELL, signals.first().type)
    }

    @Test
    fun `holds when the ema and cpr conditions disagree`() {
        // 115 sits above the EMA-implied midpoint but below the CPR
        // top-central, so neither the long nor the short condition is met.
        val signals = strategy.evaluate(contextOf(bullishSetupPlus(lastClose = 115.0)))

        assertEquals(1, signals.size)
        assertEquals(SignalType.HOLD, signals.first().type)
    }
}
