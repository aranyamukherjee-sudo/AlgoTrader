package com.algotrader.strategy.indicator

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndicatorsTest {

    private val instrument = Instrument(symbol = "TEST", exchange = "NSE")

    @Test
    fun `donchian channel tracks the rolling high and low`() {
        val highs = listOf(10.0, 12.0, 9.0, 15.0, 11.0)
        val lows = listOf(8.0, 9.0, 7.0, 10.0, 9.0)

        val channel = donchianChannel(highs, lows, period = 3)

        assertNull(channel.upper[1])
        assertEquals(12.0, channel.upper[2]) // max(10,12,9)
        assertEquals(7.0, channel.lower[2])  // min(8,9,7)
        assertEquals(15.0, channel.upper[3]) // max(12,9,15)
        assertEquals((15.0 + 7.0) / 2.0, channel.middle[3])
    }

    @Test
    fun `donchian channel is null until the lookback window is full`() {
        val highs = listOf(10.0, 12.0)
        val lows = listOf(8.0, 9.0)

        val channel = donchianChannel(highs, lows, period = 5)

        assertNull(channel.upper[0])
        assertNull(channel.upper[1])
    }

    @Test
    fun `central pivot range formula matches the standard definition`() {
        val cpr = centralPivotRange(previousHigh = 110.0, previousLow = 90.0, previousClose = 100.0)

        // pivot = (110+90+100)/3 = 100; bc = (110+90)/2 = 100; tc = 2*100-100 = 100
        assertEquals(100.0, cpr.pivot, 1e-9)
        assertEquals(100.0, cpr.bottomCentral, 1e-9)
        assertEquals(100.0, cpr.topCentral, 1e-9)
    }

    @Test
    fun `central pivot range widens when close moves farther from range midpoint`() {
        val narrow = centralPivotRange(
            previousHigh = 101.0,
            previousLow = 99.0,
            previousClose = 101.0
        )
        val wide = centralPivotRange(
            previousHigh = 120.0,
            previousLow = 80.0,
            previousClose = 120.0
        )

        val narrowWidth = narrow.topCentral - narrow.bottomCentral
        val wideWidth = wide.topCentral - wide.bottomCentral

        assertTrue(wideWidth > narrowWidth)
        assertTrue(narrowWidth > 0.0)
    }

    @Test
    fun `daily cpr uses the previous day's ohlc and is null on the first day`() {
        // Day 1 (two candles): high 110, low 90, close 100 (last candle's close).
        // Day 2 (one candle): should get a CPR computed from day 1's range.
        val day1a = candle(0, day = 1, high = 105.0, low = 95.0, close = 100.0)
        val day1b = candle(1, day = 1, high = 110.0, low = 90.0, close = 100.0)
        val day2a = candle(2, day = 2, high = 100.0, low = 100.0, close = 100.0)

        val cprSeries = dailyCentralPivotRange(listOf(day1a, day1b, day2a), zone = ZoneOffset.UTC)

        assertNull(cprSeries[0])
        assertNull(cprSeries[1])
        val day2Cpr = requireNotNull(cprSeries[2])
        assertEquals(100.0, day2Cpr.pivot, 1e-9) // (110+90+100)/3 = 100
    }

    private fun candle(index: Int, day: Int, high: Double, low: Double, close: Double): Candle = Candle(
        instrument = instrument,
        timeframe = Timeframe.MINUTE_5,
        timestamp = Instant.parse("2026-01-0${day}T0${index}:00:00Z"),
        open = close,
        high = high,
        low = low,
        close = close,
        volume = 100.0
    )
}
