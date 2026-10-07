package com.algotrader.intelligence.pattern

import com.algotrader.intelligence.pattern.PatternFixtures.cfg
import com.algotrader.intelligence.pattern.PatternFixtures.detect
import com.algotrader.intelligence.pattern.PatternFixtures.of
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.StructureFixtures.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlagTest {
    private fun flags(r: PatternAnalysis) =
        r.patterns.filter { it.type == PatternType.BULL_FLAG || it.type == PatternType.BEAR_FLAG }

    @Test
    fun `bull flag - pole up then a shallow lower-high pullback, exact members and timing`() {
        val r = detect(*PatternFixtures.bullFlag)
        val p = of(r, PatternType.BULL_FLAG).single()
        assertEquals(listOf(1, 5, 6, 7), p.pivots.map { it.index })
        assertEquals(
            listOf(PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH), p.pivots.map { it.type }
        )
        assertEquals(listOf(99.5, 130.5, 119.5, 127.5), p.pivots.map { it.price })
        assertEquals(130.5, p.high, 1e-9)
        assertEquals(99.5, p.low, 1e-9)
        assertEquals(8, p.confirmedIndex)
        assertEquals(at(8), p.confirmedAt)
        assertEquals(1, flags(r).size)
    }

    @Test
    fun `bear flag - pole down then a shallow higher-low bounce`() {
        val r = detect(*PatternFixtures.bearFlag)
        val p = of(r, PatternType.BEAR_FLAG).single()
        assertEquals(listOf(1, 5, 6, 7), p.pivots.map { it.index })
        assertEquals(listOf(100.5, 69.5, 80.5, 72.5), p.pivots.map { it.price })
        assertEquals(8, p.confirmedIndex)
        assertEquals(1, flags(r).size)
    }

    @Test
    fun `no flag before the flag's last pivot is confirmed`() {
        val candles = com.algotrader.intelligence.structure.StructureFixtures.fromMids(PatternFixtures.bullFlag.toList())
        assertTrue(flags(PatternDetector.detect(candles.take(8), cfg)).isEmpty())   // indices 0..7: pivot 7 unconfirmed
        assertEquals(1, flags(PatternDetector.detect(candles.take(9), cfg)).size)
    }

    @Test
    fun `a pole smaller than poleMinMove is not a flag`() {
        // same shape, scaled to a ~1.5% pole around 100
        val mids = doubleArrayOf(100.5, 100.0, 100.3, 100.6, 100.9, 101.5, 101.0, 101.3, 100.8)
        assertTrue(flags(detect(*mids, halfRange = 0.0)).isEmpty())
        assertTrue(flags(detect(*mids, halfRange = 0.0, config = cfg.copy(poleMinMove = 0.01))).isNotEmpty())
    }

    @Test
    fun `pole move boundary is inclusive`() {
        // exact numbers: pole 100 -> 103 is exactly 3%
        val mids = doubleArrayOf(101.0, 100.0, 101.0, 102.0, 103.0, 101.5, 102.5, 101.0)
        val at3 = flags(detect(*mids, halfRange = 0.0))
        assertEquals(listOf(PatternType.BULL_FLAG), at3.map { it.type })
        val above = flags(detect(*mids, halfRange = 0.0, config = cfg.copy(poleMinMove = 0.0301)))
        assertTrue(above.isEmpty())
    }

    @Test
    fun `a pullback deeper than maxFlagRetrace is not a flag - boundary inclusive`() {
        // pole 100 -> 130 (30); pullback to 115 retraces exactly 50%
        val exact = doubleArrayOf(105.0, 100.0, 105.0, 110.0, 120.0, 130.0, 115.0, 125.0, 118.0)
        assertEquals(listOf(PatternType.BULL_FLAG), flags(detect(*exact, halfRange = 0.0)).map { it.type })
        val deeper = doubleArrayOf(105.0, 100.0, 105.0, 110.0, 120.0, 130.0, 114.9, 125.0, 118.0)
        assertTrue(flags(detect(*deeper, halfRange = 0.0)).isEmpty())
    }

    @Test
    fun `the counter-move must stay inside the pole - a new high is not a flag`() {
        val breakout = doubleArrayOf(105.0, 100.0, 105.0, 110.0, 120.0, 130.0, 120.0, 131.0, 118.0)
        assertTrue(flags(detect(*breakout, halfRange = 0.0)).isEmpty())
        val equalTop = doubleArrayOf(105.0, 100.0, 105.0, 110.0, 120.0, 130.0, 120.0, 130.0, 118.0)
        assertTrue(flags(detect(*equalTop, halfRange = 0.0)).isEmpty())
    }

    @Test
    fun `a flag longer than its pole is not a flag`() {
        // pole = 1 bar (index 1 -> 2); flag high arrives 3 bars after the pole top
        val mids = doubleArrayOf(105.0, 100.0, 130.0, 120.0, 126.0, 121.0, 127.0, 118.0)
        assertTrue(flags(detect(*mids, halfRange = 0.0)).isEmpty())
    }

    @Test
    fun `a pullback that falls to or below the pole start is not a flag`() {
        val mids = doubleArrayOf(105.0, 100.0, 105.0, 110.0, 120.0, 130.0, 99.0, 125.0, 118.0)
        assertTrue(flags(detect(*mids, halfRange = 0.0, config = cfg.copy(maxFlagRetrace = 1.0))).isEmpty())
    }
}
