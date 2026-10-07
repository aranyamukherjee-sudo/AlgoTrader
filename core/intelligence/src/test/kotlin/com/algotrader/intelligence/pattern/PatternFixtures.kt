package com.algotrader.intelligence.pattern

import com.algotrader.intelligence.structure.StructureFixtures
import com.algotrader.intelligence.structure.StructureFixtures.fromMids

/**
 * Mechanical fixtures built on the ASI-3.1 [StructureFixtures] (left = right = 1, so every local extreme of the
 * mid-price path is a pivot: high = mid + 0.5, low = mid - 0.5). They pin geometry rules only; they are not market
 * data and say nothing about real-market behaviour.
 */
internal object PatternFixtures {
    val cfg = PatternConfig(structure = StructureFixtures.tight)

    fun detect(vararg mids: Double, halfRange: Double = 0.5, config: PatternConfig = cfg) =
        PatternDetector.detect(fromMids(mids.toList(), halfRange), config)

    fun types(analysis: PatternAnalysis): List<PatternType> = analysis.patterns.map { it.type }

    /** Patterns of one family only (families may legitimately overlap on the same pivots). */
    fun of(analysis: PatternAnalysis, type: PatternType) = analysis.patterns.filter { it.type == type }

    // ---- exact fixtures (mids) ----
    val doubleTop = doubleArrayOf(100.0, 120.0, 110.0, 120.0, 100.0)
    val doubleBottom = doubleArrayOf(120.0, 100.0, 110.0, 100.0, 120.0)
    val mTop = doubleArrayOf(100.0, 120.0, 110.0, 115.0, 100.0)
    val wBottom = doubleArrayOf(120.0, 100.0, 110.0, 105.0, 120.0)
    val range = doubleArrayOf(100.0, 120.0, 100.0, 120.0, 100.0, 120.0, 100.0)
    val ascending = doubleArrayOf(100.0, 120.0, 105.0, 120.0, 110.0, 120.0, 112.0)
    val descending = doubleArrayOf(120.0, 100.0, 115.0, 100.0, 110.0, 100.0, 108.0)
    val symmetrical = doubleArrayOf(100.0, 130.0, 105.0, 125.0, 110.0, 120.0, 112.0)
    val bullFlag = doubleArrayOf(105.0, 100.0, 105.0, 110.0, 120.0, 130.0, 120.0, 127.0, 118.0)
    val bearFlag = doubleArrayOf(95.0, 100.0, 95.0, 90.0, 80.0, 70.0, 80.0, 73.0, 82.0)

    val all = listOf(
        doubleTop, doubleBottom, mTop, wBottom, range, ascending, descending, symmetrical, bullFlag, bearFlag
    )
}
