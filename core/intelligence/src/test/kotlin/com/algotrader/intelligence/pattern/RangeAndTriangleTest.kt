package com.algotrader.intelligence.pattern

import com.algotrader.intelligence.pattern.PatternFixtures.cfg
import com.algotrader.intelligence.pattern.PatternFixtures.detect
import com.algotrader.intelligence.pattern.PatternFixtures.of
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.StructureFixtures.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeAndTriangleTest {
    private val rangeTypes = setOf(
        PatternType.RECTANGLE, PatternType.ASCENDING_TRIANGLE,
        PatternType.DESCENDING_TRIANGLE, PatternType.SYMMETRICAL_TRIANGLE
    )

    private fun shapes(r: PatternAnalysis) = r.patterns.filter { it.type in rangeTypes }

    @Test
    fun `rectangle - flat highs and flat lows, one per qualifying 4-pivot window`() {
        val r = detect(*PatternFixtures.range)
        val rects = of(r, PatternType.RECTANGLE)
        assertEquals(2, rects.size)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(2, 3, 4, 5)), rects.map { p -> p.pivots.map { it.index } })
        assertEquals(listOf(5, 6), rects.map { it.confirmedIndex })
        val first = rects[0]
        assertEquals(
            listOf(PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW), first.pivots.map { it.type }
        )
        assertEquals(120.5, first.high, 1e-9)
        assertEquals(99.5, first.low, 1e-9)
        assertEquals(at(5), first.confirmedAt)
        assertEquals(null, first.neckline)
        // the double tops / bottoms inside the same range are reported by their own family
        assertEquals(2, of(r, PatternType.DOUBLE_TOP).size)
        assertEquals(1, of(r, PatternType.DOUBLE_BOTTOM).size)
    }

    @Test
    fun `ascending triangle - flat highs, rising lows`() {
        val s = shapes(detect(*PatternFixtures.ascending))
        assertEquals(listOf(PatternType.ASCENDING_TRIANGLE, PatternType.ASCENDING_TRIANGLE), s.map { it.type })
        assertEquals(listOf(5, 6), s.map { it.confirmedIndex })
        assertEquals(listOf(120.5, 104.5, 120.5, 109.5), s[0].pivots.map { it.price })
    }

    @Test
    fun `descending triangle - flat lows, falling highs`() {
        val s = shapes(detect(*PatternFixtures.descending))
        assertEquals(PatternType.DESCENDING_TRIANGLE, s.first().type)
        assertTrue(s.all { it.type == PatternType.DESCENDING_TRIANGLE })
        assertEquals(listOf(99.5, 115.5, 99.5, 110.5), s[0].pivots.map { it.price })
    }

    @Test
    fun `symmetrical triangle - falling highs and rising lows`() {
        val s = shapes(detect(*PatternFixtures.symmetrical))
        assertTrue(s.isNotEmpty() && s.all { it.type == PatternType.SYMMETRICAL_TRIANGLE })
        assertEquals(listOf(130.5, 104.5, 125.5, 109.5), s[0].pivots.map { it.price })
        assertEquals(5, s[0].confirmedIndex)
    }

    @Test
    fun `broadening and rising-wedge shapes are not reported as a range or triangle`() {
        // broadening: higher highs and lower lows
        assertTrue(shapes(detect(100.0, 110.0, 95.0, 120.0, 90.0, 130.0, 100.0)).isEmpty())
        // rising wedge: both rising (lows rise faster than highs, still not one of the families)
        assertTrue(shapes(detect(100.0, 120.0, 105.0, 125.0, 112.0, 130.0, 118.0)).isEmpty())
        // falling channel: both falling
        assertTrue(shapes(detect(130.0, 110.0, 125.0, 105.0, 120.0, 100.0, 118.0)).isEmpty())
    }

    @Test
    fun `flat tolerance is inclusive - a slightly tilted top is still flat, a clearly tilted one is not`() {
        // exact numbers (halfRange 0): highs 100 and 99.5 (0.5% of 100 = 0.5), lows exactly 80 and 80
        val path = doubleArrayOf(90.0, 100.0, 80.0, 99.5, 80.0, 85.0)
        val flat = shapes(detect(*path, halfRange = 0.0, config = cfg.copy(levelTolerance = 0.005)))
        assertEquals(listOf(PatternType.RECTANGLE), flat.map { it.type })
        val strict = shapes(detect(*path, halfRange = 0.0, config = cfg.copy(levelTolerance = 0.004)))
        assertTrue(strict.none { it.type == PatternType.RECTANGLE })
        assertEquals(listOf(PatternType.DESCENDING_TRIANGLE), strict.map { it.type })
    }

    @Test
    fun `a too-narrow range is not reported`() {
        // 0.2-wide band at ~100: far below minDepth 1%
        assertTrue(shapes(detect(100.0, 100.2, 100.0, 100.2, 100.0, 100.2, 100.0, halfRange = 0.0)).isEmpty())
        assertTrue(shapes(detect(100.0, 100.2, 100.0, 100.2, 100.0, 100.2, 100.0, halfRange = 0.0,
            config = cfg.copy(minDepth = 0.0005))).isNotEmpty())
    }

    @Test
    fun `fewer than four alternating pivots never form a range or triangle`() {
        assertTrue(shapes(detect(100.0, 120.0, 100.0, 120.0, 100.0)).isEmpty())
    }
}
