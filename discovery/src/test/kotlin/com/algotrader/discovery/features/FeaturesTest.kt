package com.algotrader.discovery.features

import com.algotrader.discovery.fixtures.SyntheticCandles
import com.algotrader.discovery.fixtures.SyntheticCandles.candle
import com.algotrader.discovery.fixtures.SyntheticCandles.fromCloses
import com.algotrader.discovery.fixtures.SyntheticCandles.rangeBars
import com.algotrader.intelligence.dna.FeatureDomain
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.dna.ParamValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeaturesTest {

    private val registry = FeatureRegistry.standard()

    private fun num(v: Double) = ParamValue.Num(v)
    private fun text(v: String) = ParamValue.Text(v)

    private fun ma(fast: Int, slow: Int, relation: String) = FeatureRef(
        FeatureDomain.TREND, FeatureTypes.MA_RELATION,
        mapOf("fast" to num(fast.toDouble()), "slow" to num(slow.toDouble()), "relation" to text(relation))
    )

    private fun breakout(lookback: Int, direction: String) = FeatureRef(
        FeatureDomain.PRICE_ACTION, FeatureTypes.RANGE_BREAKOUT,
        mapOf("lookback" to num(lookback.toDouble()), "direction" to text(direction))
    )

    @Test
    fun `standard registry has the five starter features`() {
        assertEquals(
            setOf(
                FeatureTypes.MA_RELATION, FeatureTypes.RANGE_BREAKOUT, FeatureTypes.RANGE_CONTRACTION,
                FeatureTypes.RANGE_EXPANSION, FeatureTypes.REJECTION_CANDLE
            ),
            registry.types()
        )
    }

    @Test
    fun `moving average relation`() {
        val rising = fromCloses((1..30).map { it.toDouble() })
        assertTrue(registry.evaluate(ma(5, 30, "above"), rising))
        assertFalse(registry.evaluate(ma(5, 30, "below"), rising))
        // not enough history: false, never a guess
        assertFalse(registry.evaluate(ma(5, 30, "above"), rising.take(29)))
        assertFailsWith<IllegalArgumentException> { registry.evaluate(ma(30, 5, "above"), rising) }
        assertFailsWith<IllegalArgumentException> { registry.evaluate(ma(5, 30, "sideways"), rising) }
    }

    @Test
    fun `range breakout excludes the current bar and needs enough history`() {
        val up = fromCloses(List(10) { 100.0 } + listOf(101.0))
        assertTrue(registry.evaluate(breakout(10, "up"), up))
        assertFalse(registry.evaluate(breakout(10, "down"), up))
        // lookback 11 needs 12 bars; only 11 are available
        assertFalse(registry.evaluate(breakout(11, "up"), up))

        val down = fromCloses(List(10) { 100.0 } + listOf(99.0))
        assertTrue(registry.evaluate(breakout(10, "down"), down))

        // closing exactly at the prior high (100.1) is not a breakout
        val touch = fromCloses(List(10) { 100.0 }).dropLast(1) + candle(9, 100.0, 100.1, 99.9, 100.1)
        assertFalse(registry.evaluate(breakout(9, "up"), touch))
    }

    @Test
    fun `range contraction is measured before the current bar`() {
        val contraction = FeatureRef(
            FeatureDomain.VOLATILITY, FeatureTypes.RANGE_CONTRACTION,
            mapOf("shortBars" to num(2.0), "longBars" to num(4.0), "ratio" to num(0.5))
        )
        // prior window (4 bars) ranges 4; recent window (2 bars) ranges 1; current bar is anything
        assertTrue(registry.evaluate(contraction, rangeBars(listOf(4.0, 4.0, 4.0, 4.0, 1.0, 1.0, 9.0))))
        // recent window not smaller enough
        assertFalse(registry.evaluate(contraction, rangeBars(listOf(4.0, 4.0, 4.0, 4.0, 3.0, 3.0, 1.0))))
        // needs 1 + 2 + 4 = 7 bars
        assertFalse(registry.evaluate(contraction, rangeBars(listOf(4.0, 4.0, 4.0, 1.0, 1.0, 1.0))))
    }

    @Test
    fun `range expansion needs a strictly larger current range`() {
        val expansion = FeatureRef(
            FeatureDomain.VOLATILITY, FeatureTypes.RANGE_EXPANSION,
            mapOf("bars" to num(3.0), "multiplier" to num(2.0))
        )
        assertTrue(registry.evaluate(expansion, rangeBars(listOf(1.0, 1.0, 1.0, 3.0))))
        assertFalse(registry.evaluate(expansion, rangeBars(listOf(1.0, 1.0, 1.0, 2.0)))) // equal is not enough
        assertFalse(registry.evaluate(expansion, rangeBars(listOf(0.0, 0.0, 0.0, 3.0)))) // no baseline
        assertFalse(registry.evaluate(expansion, rangeBars(listOf(1.0, 1.0, 3.0)))) // too short
    }

    @Test
    fun `rejection candle`() {
        fun rejection(direction: String) = FeatureRef(
            FeatureDomain.PRICE_ACTION, FeatureTypes.REJECTION_CANDLE,
            mapOf("direction" to text(direction), "wickRatio" to num(0.5))
        )
        // long lower wick, closes in the upper half
        val bullish = listOf(candle(0, 100.0, 101.0, 95.0, 100.5))
        assertTrue(registry.evaluate(rejection("bullish"), bullish))
        assertFalse(registry.evaluate(rejection("bearish"), bullish))
        // long upper wick, closes in the lower half
        val bearish = listOf(candle(0, 100.0, 105.0, 99.0, 99.5))
        assertTrue(registry.evaluate(rejection("bearish"), bearish))
        assertFalse(registry.evaluate(rejection("bullish"), bearish))
        // no range, no empty input
        assertFalse(registry.evaluate(rejection("bullish"), listOf(candle(0, 100.0, 100.0, 100.0, 100.0))))
        assertFalse(registry.evaluate(rejection("bullish"), emptyList()))
    }

    @Test
    fun `parameters are validated`() {
        assertFailsWith<IllegalArgumentException> {
            registry.evaluate(FeatureRef(FeatureDomain.PRICE_ACTION, FeatureTypes.RANGE_BREAKOUT), SyntheticCandles.allStrong)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.evaluate(breakout(0, "up"), SyntheticCandles.allStrong)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.evaluate(
                FeatureRef(
                    FeatureDomain.PRICE_ACTION, FeatureTypes.RANGE_BREAKOUT,
                    mapOf("lookback" to num(2.5), "direction" to text("up"))
                ),
                SyntheticCandles.allStrong
            )
        }
    }

    @Test
    fun `unknown features fail loudly and the registry is extensible`() {
        val custom = FeatureRef(FeatureDomain.CUSTOM, "my_new_feature")
        assertFailsWith<IllegalArgumentException> { registry.evaluate(custom, SyntheticCandles.allStrong) }

        val extended = registry.withFeature("my_new_feature") { _, candles -> candles.size > 3 }
        assertTrue(extended.supports("my_new_feature"))
        assertTrue(extended.evaluate(custom, SyntheticCandles.allStrong))
        assertFalse(registry.supports("my_new_feature")) // the original registry is unchanged
        assertFailsWith<IllegalArgumentException> { extended.withFeature("my_new_feature") { _, _ -> true } }
    }
}
