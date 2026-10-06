package com.algotrader.discovery.features

import com.algotrader.domain.Candle
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.dna.ParamValue
import com.algotrader.intelligence.dna.StrategyDna

/**
 * Evaluates one measurable market feature at the LAST candle of [candles].
 *
 * Contract (the look-ahead guard): an evaluator may only use the candles it is
 * given, which end at the bar being evaluated. It returns false when there is
 * not enough history; it never guesses.
 */
fun interface FeatureEvaluator {
    fun evaluate(params: Map<String, ParamValue>, candles: List<Candle>): Boolean
}

/**
 * Maps [FeatureRef.type] to an evaluator. New feature types are added by
 * registering an evaluator ([withFeature]); the discovery engine itself does
 * not change.
 */
class FeatureRegistry private constructor(private val evaluators: Map<String, FeatureEvaluator>) {

    fun supports(type: String): Boolean = type in evaluators

    fun types(): Set<String> = evaluators.keys

    fun evaluate(feature: FeatureRef, candles: List<Candle>): Boolean {
        val evaluator = evaluators[feature.type]
            ?: throw IllegalArgumentException("no evaluator registered for feature '${feature.type}'")
        return evaluator.evaluate(feature.params, candles)
    }

    fun withFeature(type: String, evaluator: FeatureEvaluator): FeatureRegistry {
        require(type.isNotBlank()) { "feature type must not be blank" }
        require(type !in evaluators) { "feature '$type' is already registered" }
        return FeatureRegistry(evaluators + (type to evaluator))
    }

    /** Feature types used by the DNA's rule conditions that this registry cannot evaluate. */
    fun unknownFeatures(dna: StrategyDna): Set<String> =
        listOfNotNull(dna.requiredContext, dna.entry, dna.confirmation, dna.exit, dna.invalidation)
            .flatMap { it.leaves() }
            .map { it.feature.type }
            .filterNot { supports(it) }
            .toSet()

    companion object {
        fun empty(): FeatureRegistry = FeatureRegistry(emptyMap())
        fun standard(): FeatureRegistry = FeatureRegistry(StandardFeatures.evaluators())
    }
}

/** Type names of the built-in starter features. */
object FeatureTypes {
    const val MA_RELATION = "ma_relation"
    const val RANGE_BREAKOUT = "range_breakout"
    const val RANGE_CONTRACTION = "range_contraction"
    const val RANGE_EXPANSION = "range_expansion"
    const val REJECTION_CANDLE = "rejection_candle"
}

internal fun Map<String, ParamValue>.numParam(name: String): Double {
    val value = this[name] as? ParamValue.Num
        ?: throw IllegalArgumentException("missing numeric parameter '$name'")
    return value.value
}

internal fun Map<String, ParamValue>.intParam(name: String): Int {
    val value = numParam(name)
    require(value == Math.floor(value)) { "parameter '$name' must be a whole number" }
    return value.toInt()
}

internal fun Map<String, ParamValue>.textParam(name: String): String {
    val value = this[name] as? ParamValue.Text
        ?: throw IllegalArgumentException("missing text parameter '$name'")
    return value.value
}

/**
 * The small starter vocabulary. Deliberately simple and deterministic; richer
 * structure (swing points, double bottoms, levels, open interest) is later work.
 */
internal object StandardFeatures {

    fun evaluators(): Map<String, FeatureEvaluator> = mapOf(
        FeatureTypes.MA_RELATION to maRelation,
        FeatureTypes.RANGE_BREAKOUT to rangeBreakout,
        FeatureTypes.RANGE_CONTRACTION to rangeContraction,
        FeatureTypes.RANGE_EXPANSION to rangeExpansion,
        FeatureTypes.REJECTION_CANDLE to rejectionCandle
    )

    private fun range(c: Candle): Double = c.high - c.low

    private fun meanClose(candles: List<Candle>, n: Int): Double {
        var sum = 0.0
        for (i in candles.size - n until candles.size) sum += candles[i].close
        return sum / n
    }

    private fun meanRange(candles: List<Candle>, fromInclusive: Int, toExclusive: Int): Double {
        var sum = 0.0
        for (i in fromInclusive until toExclusive) sum += range(candles[i])
        return sum / (toExclusive - fromInclusive)
    }

    /** Average close of the last `fast` bars is above/below that of the last `slow` bars. */
    private val maRelation = FeatureEvaluator { p, c ->
        val fast = p.intParam("fast")
        val slow = p.intParam("slow")
        val relation = p.textParam("relation")
        require(fast >= 1 && fast < slow) { "ma_relation needs 1 <= fast < slow" }
        require(relation == "above" || relation == "below") { "relation must be 'above' or 'below'" }
        if (c.size < slow) {
            false
        } else {
            val f = meanClose(c, fast)
            val s = meanClose(c, slow)
            if (relation == "above") f > s else f < s
        }
    }

    /**
     * Close breaks above the highest high (direction "up") or below the lowest
     * low ("down") of the PRIOR `lookback` bars. The current bar is excluded.
     */
    private val rangeBreakout = FeatureEvaluator { p, c ->
        val lookback = p.intParam("lookback")
        val direction = p.textParam("direction")
        require(lookback >= 1) { "lookback must be at least 1" }
        require(direction == "up" || direction == "down") { "direction must be 'up' or 'down'" }
        if (c.size < lookback + 1) {
            false
        } else {
            val current = c[c.size - 1]
            val from = c.size - 1 - lookback
            if (direction == "up") {
                var highest = Double.NEGATIVE_INFINITY
                for (i in from until c.size - 1) highest = maxOf(highest, c[i].high)
                current.close > highest
            } else {
                var lowest = Double.POSITIVE_INFINITY
                for (i in from until c.size - 1) lowest = minOf(lowest, c[i].low)
                current.close < lowest
            }
        }
    }

    /**
     * Volatility contraction immediately BEFORE the current bar: the average
     * range of the `shortBars` bars before it is below `ratio` times the
     * average range of the `longBars` bars before those.
     */
    private val rangeContraction = FeatureEvaluator { p, c ->
        val shortBars = p.intParam("shortBars")
        val longBars = p.intParam("longBars")
        val ratio = p.numParam("ratio")
        require(shortBars >= 1 && longBars >= 1) { "window sizes must be at least 1" }
        require(ratio > 0.0) { "ratio must be positive" }
        if (c.size < 1 + shortBars + longBars) {
            false
        } else {
            val current = c.size - 1
            val recent = meanRange(c, current - shortBars, current)
            val prior = meanRange(c, current - shortBars - longBars, current - shortBars)
            prior > 0.0 && recent < ratio * prior
        }
    }

    /** The current bar's range exceeds `multiplier` times the average range of the prior `bars` bars. */
    private val rangeExpansion = FeatureEvaluator { p, c ->
        val bars = p.intParam("bars")
        val multiplier = p.numParam("multiplier")
        require(bars >= 1) { "bars must be at least 1" }
        require(multiplier > 0.0) { "multiplier must be positive" }
        if (c.size < bars + 1) {
            false
        } else {
            val current = c.size - 1
            val average = meanRange(c, current - bars, current)
            average > 0.0 && range(c[current]) > multiplier * average
        }
    }

    /**
     * A rejection candle: a long wick of at least `wickRatio` of the bar's
     * range, closing in the far half. "bullish" = long lower wick, close in
     * the upper half; "bearish" = long upper wick, close in the lower half.
     */
    private val rejectionCandle = FeatureEvaluator { p, c ->
        val direction = p.textParam("direction")
        val wickRatio = p.numParam("wickRatio")
        require(direction == "bullish" || direction == "bearish") { "direction must be 'bullish' or 'bearish'" }
        require(wickRatio > 0.0 && wickRatio <= 1.0) { "wickRatio must be within (0, 1]" }
        if (c.isEmpty()) {
            false
        } else {
            val bar = c[c.size - 1]
            val r = range(bar)
            if (r <= 0.0) {
                false
            } else if (direction == "bullish") {
                val lowerWick = minOf(bar.open, bar.close) - bar.low
                lowerWick >= wickRatio * r && bar.close >= bar.low + r / 2.0
            } else {
                val upperWick = bar.high - maxOf(bar.open, bar.close)
                upperWick >= wickRatio * r && bar.close <= bar.low + r / 2.0
            }
        }
    }
}
