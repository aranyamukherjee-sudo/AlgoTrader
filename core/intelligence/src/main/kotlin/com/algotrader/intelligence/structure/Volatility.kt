package com.algotrader.intelligence.structure

import com.algotrader.domain.Candle
import kotlin.math.abs

/**
 * Wilder's Average True Range.
 *
 * True range of bar 0 has no previous close, so it is `high - low`; later bars use
 * `max(high-low, |high-prevClose|, |low-prevClose|)`.
 *
 * Warm-up: the result is `null` for indices `0 until period-1`. At index `period-1` it is the simple average of the
 * first `period` true ranges; afterwards `atr[i] = (atr[i-1]*(period-1) + tr[i]) / period`.
 * Each value uses only candles up to its own index, so a longer series never changes earlier values.
 * Input must already be validated (see [CandleSeries]).
 */
object Volatility {

    fun trueRange(candle: Candle, previousClose: Double?): Double {
        val range = candle.high - candle.low
        if (previousClose == null) return range
        return maxOf(range, abs(candle.high - previousClose), abs(candle.low - previousClose))
    }

    fun atr(candles: List<Candle>, period: Int): List<Double?> {
        require(period >= 1) { "period must be >= 1" }
        val out = ArrayList<Double?>(candles.size)
        var sum = 0.0
        var current = 0.0
        for (i in candles.indices) {
            val tr = trueRange(candles[i], if (i == 0) null else candles[i - 1].close)
            when {
                i < period - 1 -> { sum += tr; out += null }
                i == period - 1 -> { sum += tr; current = sum / period; out += current }
                else -> { current = (current * (period - 1) + tr) / period; out += current }
            }
        }
        return out
    }
}
