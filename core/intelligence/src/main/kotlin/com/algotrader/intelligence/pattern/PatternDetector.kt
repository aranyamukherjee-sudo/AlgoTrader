package com.algotrader.intelligence.pattern

import com.algotrader.domain.Candle
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.MarketStructure
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.PriceActionStructure
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.SwingPivot
import kotlin.math.abs

/**
 * Deterministic pivot-geometry pattern detection (ASI-3.2). Pure and stateless.
 *
 * It never looks at candles itself: it consumes the CONFIRMED pivots of an ASI-3.1 [StructureAnalysis] (swing
 * detection is not duplicated). Every pattern is a fixed shape over consecutive pivots and is reported only once its
 * LAST pivot is confirmed, so `detect(prefix)` always equals `detect(full)` restricted to
 * `confirmedIndex <= prefix end`, and later candles can never alter or remove a reported pattern.
 *
 * Families are independent detections over the same pivots and MAY overlap (e.g. a bull flag's four pivots can also
 * satisfy a symmetrical triangle; a long range yields one rectangle per qualifying 4-pivot window and the double
 * tops/bottoms inside it). Choosing between them is for the consumer; nothing here is a trading signal.
 */
object PatternDetector {

    fun detect(candles: List<Candle>, config: PatternConfig = PatternConfig()): PatternAnalysis =
        detect(PriceActionStructure.analyze(candles, config.structure), config)

    /** Reuses an existing structure analysis (which must have been built with [PatternConfig.structure]). */
    fun detect(structure: StructureAnalysis, config: PatternConfig = PatternConfig()): PatternAnalysis {
        if (structure.status != AnalysisStatus.OK) {
            return PatternAnalysis(structure.status, structure.message, structure.candleCount, emptyList())
        }
        val pivots = structure.pivots.map { it.pivot }
        val found = ArrayList<DetectedPattern>()
        found += doublesAndMW(pivots, config)
        found += fourPivotPatterns(pivots, config)
        val ordered = found.sortedWith(compareBy({ it.confirmedIndex }, { it.startIndex }, { it.type }))
        return PatternAnalysis(AnalysisStatus.OK, "", structure.candleCount, ordered)
    }

    private fun same(a: Double, b: Double, c: PatternConfig) = MarketStructure.sameLevel(a, b, c.levelTolerance)

    private fun deepEnough(depth: Double, reference: Double, c: PatternConfig) =
        depth > 0.0 && depth >= c.minDepth * abs(reference)

    private fun build(type: PatternType, members: List<SwingPivot>, neckline: Double? = null): DetectedPattern {
        val last = members.maxByOrNull { it.confirmedIndex }!!
        return DetectedPattern(
            type = type,
            pivots = members,
            startIndex = members.first().index,
            endIndex = members.last().index,
            confirmedIndex = last.confirmedIndex,
            confirmedAt = last.confirmedAt,
            high = members.maxOf { it.price },
            low = members.minOf { it.price },
            neckline = neckline
        )
    }

    /**
     * Two CONSECUTIVE same-type pivots with at least one opposite pivot between them; the neckline is the most
     * extreme opposite pivot between (earliest on a tie).
     */
    private fun strictlyChronological(members: List<SwingPivot>): Boolean =
        members.zipWithNext().all { (a, b) -> a.index < b.index }

    private fun doublesAndMW(p: List<SwingPivot>, c: PatternConfig): List<DetectedPattern> {
        val out = ArrayList<DetectedPattern>()
        for (type in PivotType.values()) {
            val positions = p.indices.filter { p[it].type == type }
            for (k in 1 until positions.size) {
                val a = positions[k - 1]
                val b = positions[k]
                if (b - a < 2) continue
                val first = p[a]
                val second = p[b]
                val between = p.subList(a + 1, b)
                if (type == PivotType.HIGH) {
                    val neck = between.minWith(compareBy { it.price })
                    val depth = minOf(first.price, second.price) - neck.price
                    if (!deepEnough(depth, neck.price, c)) continue
                    val members = listOf(first, neck, second)
                    if (!strictlyChronological(members)) continue
                    if (same(first.price, second.price, c)) {
                        out += build(PatternType.DOUBLE_TOP, members, neck.price)
                    } else if (second.price < first.price &&
                        first.price - second.price <= c.maxSecondExtremeRatio * (first.price - neck.price)
                    ) {
                        out += build(PatternType.M_TOP, members, neck.price)
                    }
                } else {
                    val neck = between.maxWith(compareBy { it.price })
                    val depth = neck.price - maxOf(first.price, second.price)
                    if (!deepEnough(depth, neck.price, c)) continue
                    val members = listOf(first, neck, second)
                    if (!strictlyChronological(members)) continue
                    if (same(first.price, second.price, c)) {
                        out += build(PatternType.DOUBLE_BOTTOM, members, neck.price)
                    } else if (second.price > first.price &&
                        second.price - first.price <= c.maxSecondExtremeRatio * (neck.price - first.price)
                    ) {
                        out += build(PatternType.W_BOTTOM, members, neck.price)
                    }
                }
            }
        }
        return out
    }

    /** Rectangle, triangles and flags: every window of 4 strictly alternating consecutive pivots. */
    private fun fourPivotPatterns(p: List<SwingPivot>, c: PatternConfig): List<DetectedPattern> {
        val out = ArrayList<DetectedPattern>()
        for (i in 0..p.size - 4) {
            val w = p.subList(i, i + 4)
            if (!strictlyChronological(w)) continue
            if ((0 until 3).any { w[it].type == w[it + 1].type }) continue
            rangeShape(w, c)?.let { out += build(it, w) }
            flagShape(w, c)?.let { out += build(it, w) }
        }
        return out
    }

    private fun rangeShape(w: List<SwingPivot>, c: PatternConfig): PatternType? {
        val highs = w.filter { it.type == PivotType.HIGH }
        val lows = w.filter { it.type == PivotType.LOW }
        val h1 = highs[0].price
        val h2 = highs[1].price
        val l1 = lows[0].price
        val l2 = lows[1].price
        val ceiling = minOf(h1, h2)
        val floor = maxOf(l1, l2)
        if (!deepEnough(ceiling - floor, floor, c)) return null
        val flatHigh = same(h1, h2, c)
        val flatLow = same(l1, l2, c)
        val highFalling = !flatHigh && h2 < h1
        val lowRising = !flatLow && l2 > l1
        return when {
            flatHigh && flatLow -> PatternType.RECTANGLE
            flatHigh && lowRising -> PatternType.ASCENDING_TRIANGLE
            flatLow && highFalling -> PatternType.DESCENDING_TRIANGLE
            highFalling && lowRising -> PatternType.SYMMETRICAL_TRIANGLE
            else -> null
        }
    }

    /**
     * Pole = the first leg; flag = the pullback and the counter-high/low that follows. The flag may not last longer
     * (in bars) than the pole, may not retrace more than [PatternConfig.maxFlagRetrace] of the pole, and must stay
     * strictly inside the pole's extreme.
     */
    private fun flagShape(w: List<SwingPivot>, c: PatternConfig): PatternType? {
        val poleBars = w[1].index - w[0].index
        val flagBars = w[3].index - w[1].index
        if (flagBars > poleBars) return null
        return if (w[0].type == PivotType.LOW) {
            val start = w[0].price
            val top = w[1].price
            val pullback = w[2].price
            val counter = w[3].price
            val pole = top - start
            if (pole > 0.0 && pole >= c.poleMinMove * abs(start) &&
                pullback > start && top - pullback <= c.maxFlagRetrace * pole &&
                counter < top && counter > pullback
            ) PatternType.BULL_FLAG else null
        } else {
            val start = w[0].price
            val bottom = w[1].price
            val bounce = w[2].price
            val counter = w[3].price
            val pole = start - bottom
            if (pole > 0.0 && pole >= c.poleMinMove * abs(start) &&
                bounce < start && bounce - bottom <= c.maxFlagRetrace * pole &&
                counter > bottom && counter < bounce
            ) PatternType.BEAR_FLAG else null
        }
    }
}
