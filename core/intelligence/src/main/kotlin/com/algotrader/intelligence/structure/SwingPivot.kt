package com.algotrader.intelligence.structure

import com.algotrader.domain.Candle
import java.time.Instant

enum class PivotType { HIGH, LOW }

/**
 * A confirmed swing high/low.
 *
 * @property index position in the analysed candle list.
 * @property confirmedIndex index of the candle at which the pivot became knowable (`index + right`).
 *   Nothing derived from this pivot may be used before that candle.
 * @property strength consecutive bars to the left that this pivot strictly dominates (>= `left`, capped).
 * @property prominence price depth of the swing within `[index-left, index+right]`; always >= 0.
 */
data class SwingPivot(
    val type: PivotType,
    val index: Int,
    val timestamp: Instant,
    val price: Double,
    val confirmedIndex: Int,
    val confirmedAt: Instant,
    val strength: Int,
    val prominence: Double
)

/**
 * Deterministic swing detection.
 *
 * Equal prices: a pivot HIGH needs `high > every high of the `left` bars before it` and
 * `high >= every high of the `right` bars after it`. So a flat run of equal highs yields exactly ONE pivot,
 * the EARLIEST of the run (later equals are not strictly greater than their left window). Lows mirror this.
 * Only full windows count, so the first `left` bars and last `right` bars can never be pivots.
 *
 * Input must already be validated (see [CandleSeries]).
 */
internal object SwingDetector {

    /** Ordered by [SwingPivot.index], then HIGH before LOW (a bar can be both). */
    fun detect(candles: List<Candle>, config: StructureConfig): List<SwingPivot> {
        val n = candles.size
        if (n < config.minCandles) return emptyList()
        val result = ArrayList<SwingPivot>()
        for (i in config.left..(n - 1 - config.right)) {
            if (isPivotHigh(candles, i, config)) result += build(candles, i, PivotType.HIGH, config)
            if (isPivotLow(candles, i, config)) result += build(candles, i, PivotType.LOW, config)
        }
        return result
    }

    private fun isPivotHigh(c: List<Candle>, i: Int, cfg: StructureConfig): Boolean {
        val h = c[i].high
        for (j in i - cfg.left until i) if (c[j].high >= h) return false
        for (j in i + 1..i + cfg.right) if (c[j].high > h) return false
        return true
    }

    private fun isPivotLow(c: List<Candle>, i: Int, cfg: StructureConfig): Boolean {
        val l = c[i].low
        for (j in i - cfg.left until i) if (c[j].low <= l) return false
        for (j in i + 1..i + cfg.right) if (c[j].low < l) return false
        return true
    }

    private fun build(c: List<Candle>, i: Int, type: PivotType, cfg: StructureConfig): SwingPivot {
        val confirmed = i + cfg.right
        var run = 0
        var j = i - 1
        while (j >= 0 && run < cfg.maxStrength &&
            (if (type == PivotType.HIGH) c[j].high < c[i].high else c[j].low > c[i].low)
        ) {
            run++
            j--
        }
        val from = i - cfg.left
        val prominence = if (type == PivotType.HIGH) {
            val leftMin = (from..i).minOf { c[it].low }
            val rightMin = (i..confirmed).minOf { c[it].low }
            c[i].high - maxOf(leftMin, rightMin)
        } else {
            val leftMax = (from..i).maxOf { c[it].high }
            val rightMax = (i..confirmed).maxOf { c[it].high }
            minOf(leftMax, rightMax) - c[i].low
        }
        return SwingPivot(
            type = type,
            index = i,
            timestamp = c[i].timestamp,
            price = if (type == PivotType.HIGH) c[i].high else c[i].low,
            confirmedIndex = confirmed,
            confirmedAt = c[confirmed].timestamp,
            strength = run,
            prominence = prominence
        )
    }
}
