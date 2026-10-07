package com.algotrader.intelligence.structure

import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.structure.StructureFixtures.at
import com.algotrader.intelligence.structure.StructureFixtures.candle
import com.algotrader.intelligence.structure.StructureFixtures.fromMids
import com.algotrader.intelligence.structure.StructureFixtures.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceActionStructureTest {
    private val cfg = StructureConfig()

    private fun invalid(candles: List<com.algotrader.domain.Candle>, config: StructureConfig = cfg): StructureAnalysis {
        val r = PriceActionStructure.analyze(candles, config)
        assertEquals(AnalysisStatus.INVALID_INPUT, r.status, r.message)
        assertTrue(r.pivots.isEmpty() && r.zones.isEmpty())
        assertEquals(MarketStructureState.UNDEFINED, r.state)
        assertNull(r.atr)
        assertTrue(r.message.isNotBlank())
        return r
    }

    private val good get() = fromMids(10.0, 11.0, 15.0, 12.0, 11.0, 9.0, 6.0, 8.0, 10.0)

    @Test
    fun `duplicate timestamp is rejected`() {
        val c = good.toMutableList()
        c[4] = c[4].copy(timestamp = c[3].timestamp)
        assertTrue(invalid(c).message.contains("duplicate"))
    }

    @Test
    fun `decreasing timestamp is rejected and never silently sorted`() {
        val c = good.toMutableList()
        c[5] = c[5].copy(timestamp = at(1))
        assertTrue(invalid(c).message.contains("decrease"))
        invalid(good.reversed())
    }

    @Test
    fun `invalid chronology wins over insufficient data`() {
        val two = listOf(candle(0, 11.0, 9.0), candle(0, 12.0, 8.0))
        assertEquals(AnalysisStatus.INVALID_INPUT, PriceActionStructure.analyze(two, cfg).status)
    }

    @Test
    fun `mixed instruments or timeframes are rejected`() {
        val a = good.toMutableList()
        a[3] = a[3].copy(instrument = Instrument("BANKNIFTY", "NSE"))
        assertTrue(invalid(a).message.contains("instrument"))
        val b = good.toMutableList()
        b[3] = b[3].copy(timeframe = Timeframe.MINUTE_15)
        assertTrue(invalid(b).message.contains("timeframe"))
    }

    @Test
    fun `non-finite prices and high below low are rejected`() {
        val nan = good.toMutableList()
        nan[2] = nan[2].copy(high = Double.NaN)
        assertTrue(invalid(nan).message.contains("non-finite"))
        val inf = good.toMutableList()
        inf[2] = inf[2].copy(close = Double.POSITIVE_INFINITY)
        invalid(inf)
        val inverted = good.toMutableList()
        inverted[2] = inverted[2].copy(high = 1.0, low = 2.0)
        assertTrue(invalid(inverted).message.contains("high < low"))
    }

    @Test
    fun `configuration errors are programmer errors and throw`() {
        assertFailsWith<IllegalArgumentException> { StructureConfig(left = 0) }
        assertFailsWith<IllegalArgumentException> { StructureConfig(right = 0) }
        assertFailsWith<IllegalArgumentException> { StructureConfig(left = 5, maxStrength = 4) }
        assertFailsWith<IllegalArgumentException> { StructureConfig(minZoneTouches = 1) }
        assertFailsWith<IllegalArgumentException> { StructureConfig(zoneTolerance = -0.1) }
        assertFailsWith<IllegalArgumentException> { StructureConfig(equalityTolerance = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { StructureConfig(atrPeriod = 0) }
    }

    @Test
    fun `repeated evaluation gives identical results and leaves the input untouched`() {
        val candles = walk(250)
        val snapshot = candles.toList()
        val first = PriceActionStructure.analyze(candles, cfg)
        repeat(25) { assertEquals(first, PriceActionStructure.analyze(candles, cfg)) }
        assertEquals(first, PriceActionStructure.analyze(candles.toList(), StructureConfig()))
        assertEquals(snapshot, candles)
        assertTrue(first.pivots.isNotEmpty())
    }

    @Test
    fun `an equal copy of the candles with fresh objects gives an equal analysis`() {
        val a = walk(100, seed = 99L)
        val b = walk(100, seed = 99L)
        assertEquals(PriceActionStructure.analyze(a, cfg), PriceActionStructure.analyze(b, cfg))
        assertTrue(PriceActionStructure.analyze(a, cfg) != PriceActionStructure.analyze(walk(100, seed = 98L), cfg))
    }

    @Test
    fun `end to end - a staircase up is an uptrend with ATR and a populated result`() {
        val mids = doubleArrayOf(
            100.0, 110.0, 104.0, 118.0, 108.0, 126.0, 114.0, 134.0, 120.0, 142.0, 128.0, 150.0, 136.0, 146.0
        )
        val r = PriceActionStructure.analyze(
            fromMids(mids.toList(), halfRange = 1.0), StructureConfig(left = 1, right = 1, maxStrength = 3, atrPeriod = 5)
        )
        assertEquals(AnalysisStatus.OK, r.status)
        assertEquals(MarketStructureState.UPTREND, r.state)
        assertTrue(r.atr!! > 0.0)
        assertEquals(14, r.candleCount)
        assertTrue(r.pivots.all { it.pivot.confirmedIndex <= 13 })
    }

    @Test
    fun `changing candles after a pivot's confirmation cannot change that pivot`() {
        val candles = walk(120)
        val full = PriceActionStructure.analyze(candles, cfg).pivots
        val target = full.first { it.pivot.confirmedIndex in 30..60 }
        val cut = target.pivot.confirmedIndex
        val tampered = candles.take(cut + 1) + candles.drop(cut + 1).map {
            it.copy(high = it.high + 500.0, low = it.low + 400.0, close = it.close + 450.0, open = it.open + 450.0)
        }
        val after = PriceActionStructure.analyze(tampered, cfg).pivots
        assertEquals(full.filter { it.pivot.confirmedIndex <= cut }, after.filter { it.pivot.confirmedIndex <= cut })
    }
}
