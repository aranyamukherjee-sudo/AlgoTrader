package com.algotrader.intelligence.pattern

import com.algotrader.intelligence.pattern.PatternFixtures.cfg
import com.algotrader.intelligence.pattern.PatternFixtures.detect
import com.algotrader.intelligence.pattern.PatternFixtures.of
import com.algotrader.intelligence.pattern.PatternFixtures.types
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.ClassifiedPivot
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.StructureFixtures.pivot
import com.algotrader.intelligence.structure.StructureLabel
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.StructureFixtures.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoublePatternTest {

    @Test
    fun `double top - exact members, envelope, neckline and confirmation`() {
        val r = detect(*PatternFixtures.doubleTop)
        assertEquals(listOf(PatternType.DOUBLE_TOP), types(r))
        val p = r.patterns.single()
        assertEquals(listOf(1, 2, 3), p.pivots.map { it.index })
        assertEquals(listOf(PivotType.HIGH, PivotType.LOW, PivotType.HIGH), p.pivots.map { it.type })
        assertEquals(listOf(120.5, 109.5, 120.5), p.pivots.map { it.price })
        assertEquals(109.5, p.neckline!!, 1e-9)
        assertEquals(120.5, p.high, 1e-9)
        assertEquals(109.5, p.low, 1e-9)
        assertEquals(1, p.startIndex)
        assertEquals(3, p.endIndex)
        assertEquals(4, p.confirmedIndex)            // second high at 3 + right 1
        assertEquals(at(4), p.confirmedAt)
    }

    @Test
    fun `double bottom - exact members, envelope, neckline and confirmation`() {
        val r = detect(*PatternFixtures.doubleBottom)
        assertEquals(listOf(PatternType.DOUBLE_BOTTOM), types(r))
        val p = r.patterns.single()
        assertEquals(listOf(99.5, 110.5, 99.5), p.pivots.map { it.price })
        assertEquals(110.5, p.neckline!!, 1e-9)
        assertEquals(110.5, p.high, 1e-9)
        assertEquals(99.5, p.low, 1e-9)
        assertEquals(4, p.confirmedIndex)
    }

    @Test
    fun `not confirmed before the second extreme is confirmed - no look-ahead`() {
        val candles = com.algotrader.intelligence.structure.StructureFixtures.fromMids(PatternFixtures.doubleTop.toList())
        // 4 candles (0..3): the second high at index 3 cannot be confirmed yet
        assertTrue(PatternDetector.detect(candles.take(4), cfg).patterns.isEmpty())
        assertEquals(1, PatternDetector.detect(candles.take(5), cfg).patterns.size)
    }

    @Test
    fun `M top - second high lower but within the allowed fraction of the swing`() {
        val r = detect(*PatternFixtures.mTop)
        assertEquals(listOf(PatternType.M_TOP), types(r))
        assertEquals(listOf(120.5, 109.5, 115.5), r.patterns.single().pivots.map { it.price })
    }

    @Test
    fun `W bottom - second low higher but within the allowed fraction of the swing`() {
        val r = detect(*PatternFixtures.wBottom)
        assertEquals(listOf(PatternType.W_BOTTOM), types(r))
        assertEquals(listOf(99.5, 110.5, 104.5), r.patterns.single().pivots.map { it.price })
    }

    @Test
    fun `level tolerance boundary is inclusive and exact`() {
        // exact numbers (halfRange 0): highs 100 and 99 around a low of 90; tolerance 1% of the larger = exactly 1.0
        val tol = cfg.copy(levelTolerance = 0.01)
        assertEquals(listOf(PatternType.DOUBLE_TOP), types(detect(80.0, 100.0, 90.0, 99.0, 80.0, halfRange = 0.0, config = tol)))
        // just outside the tolerance it is no longer a double top; it is an M top (1.01 <= 0.5 * (100 - 90))
        val outside = detect(80.0, 100.0, 90.0, 98.99, 80.0, halfRange = 0.0, config = tol)
        assertTrue(of(outside, PatternType.DOUBLE_TOP).isEmpty())
        assertEquals(listOf(PatternType.M_TOP), types(outside))
        // zero tolerance: only exactly equal highs
        val exact = cfg.copy(levelTolerance = 0.0)
        assertEquals(listOf(PatternType.DOUBLE_TOP), types(detect(80.0, 100.0, 90.0, 100.0, 80.0, halfRange = 0.0, config = exact)))
        assertEquals(listOf(PatternType.M_TOP), types(detect(80.0, 100.0, 90.0, 99.99, 80.0, halfRange = 0.0, config = exact)))
    }

    @Test
    fun `second extreme beyond the allowed fraction is not a pattern`() {
        // second high 113 vs first 120.5: gap 7.5 > 0.5 * (120.5 - 109.5) = 5.5
        assertTrue(detect(100.0, 120.0, 110.0, 113.0, 100.0).patterns.isEmpty())
        // W with the second low too high: gap 7.5 > 5.5
        assertTrue(detect(120.0, 100.0, 110.0, 107.0, 120.0).patterns.isEmpty())
    }

    @Test
    fun `ratio boundary is inclusive`() {
        // exact numbers: neckline 90, first high 100, second 95 -> gap 5 == 0.5 * 10
        val r = detect(80.0, 100.0, 90.0, 95.0, 80.0, halfRange = 0.0, config = cfg.copy(minDepth = 0.0))
        assertEquals(listOf(PatternType.M_TOP), types(r))
        val over = detect(80.0, 100.0, 90.0, 94.9, 80.0, halfRange = 0.0, config = cfg.copy(minDepth = 0.0))
        assertTrue(over.patterns.isEmpty())
    }

    @Test
    fun `W ratio boundary is inclusive`() {
        // exact numbers: first low 100, neckline 110, second low 105 -> gap 5 == 0.5 * 10
        assertEquals(listOf(PatternType.W_BOTTOM), types(detect(120.0, 100.0, 110.0, 105.0, 120.0, halfRange = 0.0)))
        assertTrue(detect(120.0, 100.0, 110.0, 105.1, 120.0, halfRange = 0.0).patterns.isEmpty())
    }

    @Test
    fun `a lower second low or a higher second high is not a double or W or M`() {
        assertTrue(detect(120.0, 100.0, 110.0, 95.0, 120.0).patterns.isEmpty())   // lower low
        assertTrue(detect(100.0, 120.0, 110.0, 125.0, 100.0).patterns.isEmpty())  // higher high
    }

    @Test
    fun `too shallow a neckline is not a pattern`() {
        // highs 101, low between 100.9 -> depth 0.1 < 1% of 100.9
        assertTrue(detect(100.0, 101.0, 100.9, 101.0, 100.0, halfRange = 0.0).patterns.isEmpty())
        // the same shape passes when minDepth is relaxed
        assertEquals(
            listOf(PatternType.DOUBLE_TOP),
            types(detect(100.0, 101.0, 100.9, 101.0, 100.0, halfRange = 0.0, config = cfg.copy(minDepth = 0.0001)))
        )
    }

    private fun crafted(vararg pivots: SwingPivot) = StructureAnalysis(
        AnalysisStatus.OK, "", 20, pivots.map { ClassifiedPivot(it, StructureLabel.FIRST) },
        MarketStructureState.UNDEFINED, emptyList(), null
    )

    @Test
    fun `with several opposite pivots between, the neckline is the most extreme and ties pick the earliest`() {
        val h1 = pivot(PivotType.HIGH, 1, 100.0)
        val h2 = pivot(PivotType.HIGH, 6, 100.0)
        val deeper = pivot(PivotType.LOW, 3, 88.0)
        val r = PatternDetector.detect(
            crafted(h1, pivot(PivotType.LOW, 2, 90.0), deeper, pivot(PivotType.LOW, 4, 89.0), h2), cfg
        )
        val p = r.patterns.single()
        assertEquals(PatternType.DOUBLE_TOP, p.type)
        assertEquals(listOf(h1, deeper, h2), p.pivots)
        assertEquals(88.0, p.neckline!!, 1e-9)
        // tie: two equally deep lows -> the earlier one is the neckline pivot
        val first = pivot(PivotType.LOW, 2, 88.0)
        val tied = PatternDetector.detect(crafted(h1, first, pivot(PivotType.LOW, 3, 88.0), h2), cfg).patterns.single()
        assertEquals(2, tied.pivots[1].index)
    }

    @Test
    fun `highs without an opposite pivot between them are never joined`() {
        // crafted structure: two consecutive HIGH pivots, nothing between
        val s = com.algotrader.intelligence.structure.PriceActionStructure.analyze(
            com.algotrader.intelligence.structure.StructureFixtures.fromMids(PatternFixtures.doubleTop.toList()),
            cfg.structure
        )
        val twoHighs = s.copy(pivots = listOf(s.pivots[0], s.pivots[2]))
        assertTrue(PatternDetector.detect(twoHighs, cfg).patterns.isEmpty())
    }
}
