package com.algotrader.strategy.indicator

import kotlin.math.sqrt

/**
 * Pure, dependency-free indicator math shared by every strategy in
 * strategy-engine. Every function returns one value per input bar; a null
 * entry means "not enough history yet at this index" so callers (and the
 * backtest engine) never act on an immature value.
 */

/** Simple moving average over the trailing [period] values. */
fun sma(values: List<Double>, period: Int): List<Double?> {
    require(period > 0) { "period must be greater than zero" }
    val result = MutableList<Double?>(values.size) { null }
    if (values.size < period) return result

    var windowSum = 0.0
    for (i in values.indices) {
        windowSum += values[i]
        if (i >= period) windowSum -= values[i - period]
        if (i >= period - 1) result[i] = windowSum / period
    }
    return result
}

/**
 * Exponential moving average, seeded with the simple average of the first
 * [period] values (the standard approach) rather than the first value alone,
 * so it doesn't overreact while still "warming up".
 */
fun ema(values: List<Double>, period: Int): List<Double?> {
    require(period > 0) { "period must be greater than zero" }
    val result = MutableList<Double?>(values.size) { null }
    if (values.size < period) return result

    var seed = 0.0
    for (i in 0 until period) seed += values[i]
    seed /= period
    result[period - 1] = seed

    val k = 2.0 / (period + 1)
    var previous = seed
    for (i in period until values.size) {
        previous = values[i] * k + previous * (1 - k)
        result[i] = previous
    }
    return result
}

/** Wilder's RSI (relative strength index), 0-100. */
fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
    require(period > 0) { "period must be greater than zero" }
    val result = MutableList<Double?>(values.size) { null }
    if (values.size <= period) return result

    var gainSum = 0.0
    var lossSum = 0.0
    for (i in 1..period) {
        val diff = values[i] - values[i - 1]
        if (diff >= 0) gainSum += diff else lossSum -= diff
    }

    var avgGain = gainSum / period
    var avgLoss = lossSum / period
    result[period] = rsiFromAverages(avgGain, avgLoss)

    for (i in period + 1 until values.size) {
        val diff = values[i] - values[i - 1]
        val gain = if (diff > 0) diff else 0.0
        val loss = if (diff < 0) -diff else 0.0
        avgGain = (avgGain * (period - 1) + gain) / period
        avgLoss = (avgLoss * (period - 1) + loss) / period
        result[i] = rsiFromAverages(avgGain, avgLoss)
    }
    return result
}

private fun rsiFromAverages(avgGain: Double, avgLoss: Double): Double {
    if (avgLoss == 0.0) return 100.0
    val rs = avgGain / avgLoss
    return 100.0 - 100.0 / (1.0 + rs)
}

data class MacdResult(
    val macdLine: List<Double?>,
    val signalLine: List<Double?>,
    val histogram: List<Double?>
)

/** MACD: fast EMA minus slow EMA, plus an EMA-of-that as the signal line. */
fun macd(
    values: List<Double>,
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9
): MacdResult {
    require(fastPeriod > 0) { "fastPeriod must be greater than zero" }
    require(slowPeriod > fastPeriod) { "slowPeriod must be greater than fastPeriod" }
    require(signalPeriod > 0) { "signalPeriod must be greater than zero" }

    val fastEma = ema(values, fastPeriod)
    val slowEma = ema(values, slowPeriod)

    val macdLine = values.indices.map { i ->
        val fast = fastEma[i]
        val slow = slowEma[i]
        if (fast != null && slow != null) fast - slow else null
    }

    val signalLine = MutableList<Double?>(values.size) { null }
    val firstMacdIndex = macdLine.indexOfFirst { it != null }
    if (firstMacdIndex >= 0) {
        val macdValues = macdLine.subList(firstMacdIndex, macdLine.size).map { it!! }
        val signalEma = ema(macdValues, signalPeriod)
        for (i in signalEma.indices) {
            signalLine[firstMacdIndex + i] = signalEma[i]
        }
    }

    val histogram = values.indices.map { i ->
        val m = macdLine[i]
        val s = signalLine[i]
        if (m != null && s != null) m - s else null
    }

    return MacdResult(macdLine, signalLine, histogram)
}

data class BollingerBands(
    val upper: List<Double?>,
    val middle: List<Double?>,
    val lower: List<Double?>
)

/** Bollinger Bands: an SMA middle band with upper/lower bands at +/- [stdDevMultiplier] standard deviations. */
fun bollingerBands(
    values: List<Double>,
    period: Int = 20,
    stdDevMultiplier: Double = 2.0
): BollingerBands {
    require(period > 1) { "period must be greater than one" }
    require(stdDevMultiplier > 0.0) { "stdDevMultiplier must be positive" }

    val middle = sma(values, period)
    val upper = MutableList<Double?>(values.size) { null }
    val lower = MutableList<Double?>(values.size) { null }

    for (i in values.indices) {
        val mean = middle[i] ?: continue
        var sumSquares = 0.0
        for (j in (i - period + 1)..i) {
            val diff = values[j] - mean
            sumSquares += diff * diff
        }
        val stdDev = sqrt(sumSquares / period)
        upper[i] = mean + stdDevMultiplier * stdDev
        lower[i] = mean - stdDevMultiplier * stdDev
    }

    return BollingerBands(upper, middle, lower)
}

data class DonchianChannel(
    val upper: List<Double?>,
    val lower: List<Double?>,
    val middle: List<Double?>
)

/** Donchian Channel: rolling highest-high / lowest-low over [period] bars, plus their midpoint. */
fun donchianChannel(
    highs: List<Double>,
    lows: List<Double>,
    period: Int = 20
): DonchianChannel {
    require(period > 0) { "period must be greater than zero" }
    require(highs.size == lows.size) { "highs and lows must be the same length" }

    val upper = MutableList<Double?>(highs.size) { null }
    val lower = MutableList<Double?>(highs.size) { null }
    val middle = MutableList<Double?>(highs.size) { null }

    for (i in highs.indices) {
        if (i < period - 1) continue
        var windowHigh = highs[i - period + 1]
        var windowLow = lows[i - period + 1]
        for (j in (i - period + 2)..i) {
            if (highs[j] > windowHigh) windowHigh = highs[j]
            if (lows[j] < windowLow) windowLow = lows[j]
        }
        upper[i] = windowHigh
        lower[i] = windowLow
        middle[i] = (windowHigh + windowLow) / 2.0
    }

    return DonchianChannel(upper, lower, middle)
}
