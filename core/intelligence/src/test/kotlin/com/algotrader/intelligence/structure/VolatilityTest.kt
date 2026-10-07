package com.algotrader.intelligence.structure

import com.algotrader.intelligence.structure.StructureFixtures.candle
import com.algotrader.intelligence.structure.StructureFixtures.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolatilityTest {

    private val series = listOf(
        candle(0, high = 10.0, low = 8.0, close = 9.0),
        candle(1, high = 11.0, low = 9.0, close = 10.0),
        candle(2, high = 12.0, low = 10.0, close = 11.0),
        candle(3, high = 15.0, low = 11.0, close = 14.0),
        candle(4, high = 14.0, low = 12.0, close = 13.0)
    )

    @Test
    fun `warm-up is null for the first period-1 bars then SMA seed then Wilder smoothing`() {
        val atr = Volatility.atr(series, 3)
        assertEquals(5, atr.size)
        assertNull(atr[0])
        assertNull(atr[1])
        assertEquals(2.0, atr[2]!!, 1e-9)                    // mean of TR 2, 2, 2
        assertEquals((2.0 * 2 + 4.0) / 3, atr[3]!!, 1e-9)    // TR3 = 4
        assertEquals(((2.0 * 2 + 4.0) / 3 * 2 + 2.0) / 3, atr[4]!!, 1e-9) // TR4 = 2 (|12-14|)
    }

    @Test
    fun `true range includes the gap from the previous close`() {
        val gapUp = candle(1, high = 14.0, low = 13.0, close = 13.5)
        assertEquals(4.0, Volatility.trueRange(gapUp, previousClose = 10.0), 1e-9)
        val gapDown = candle(1, high = 7.0, low = 6.0, close = 6.5)
        assertEquals(4.0, Volatility.trueRange(gapDown, previousClose = 10.0), 1e-9)
        assertEquals(1.0, Volatility.trueRange(gapUp, previousClose = null), 1e-9)
    }

    @Test
    fun `the first bar uses high minus low`() {
        assertEquals(2.0, Volatility.atr(series, 1)[0]!!, 1e-9)
    }

    @Test
    fun `period 1 equals the true range from the first bar`() {
        val atr = Volatility.atr(series, 1)
        assertEquals(listOf(2.0, 2.0, 2.0, 4.0, 2.0), atr.map { it!! })
    }

    @Test
    fun `a series shorter than the period never produces a value`() {
        assertTrue(Volatility.atr(series, 6).all { it == null })
        assertTrue(Volatility.atr(emptyList(), 3).isEmpty())
    }

    @Test
    fun `flat candles have zero ATR`() {
        val flat = (0 until 6).map { candle(it, high = 5.0, low = 5.0, close = 5.0) }
        assertTrue(Volatility.atr(flat, 3).drop(2).all { it == 0.0 })
    }

    @Test
    fun `earlier values never change when more candles arrive`() {
        val candles = walk(80)
        val full = Volatility.atr(candles, 14)
        for (k in listOf(10, 14, 15, 40, 79)) {
            assertEquals(full.take(k), Volatility.atr(candles.take(k), 14))
        }
    }

    @Test
    fun `non-positive period is rejected`() {
        assertFailsWith<IllegalArgumentException> { Volatility.atr(series, 0) }
    }

    @Test
    fun `analysis exposes ATR only once warmed up`() {
        val candles = walk(30)
        val cold = PriceActionStructure.analyze(candles.take(10), StructureConfig(atrPeriod = 14))
        assertEquals(AnalysisStatus.OK, cold.status)
        assertNull(cold.atr)
        val warm = PriceActionStructure.analyze(candles.take(14), StructureConfig(atrPeriod = 14))
        assertEquals(Volatility.atr(candles.take(14), 14).last(), warm.atr)
        assertTrue(warm.atr!! > 0.0)
    }
}
