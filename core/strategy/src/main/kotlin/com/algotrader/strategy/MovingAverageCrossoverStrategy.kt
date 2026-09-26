package com.algotrader.strategy

import com.algotrader.strategy.indicator.sma

/**
 * Classic fast/slow simple-moving-average crossover.
 *
 * Entry (long): the fast SMA crosses from at-or-below the slow SMA to above it.
 * Exit (flat): the fast SMA crosses from at-or-above the slow SMA to below it.
 *
 * This strategy is long-only: a bearish crossover flattens an open long but
 * never opens a short.
 */
class MovingAverageCrossoverStrategy(
    private val fastPeriod: Int = 5,
    private val slowPeriod: Int = 10
) : Strategy {

    init {
        require(fastPeriod > 0) { "fastPeriod must be greater than zero" }
        require(slowPeriod > fastPeriod) {
            "slowPeriod must be greater than fastPeriod"
        }
    }

    override val name: String = "Moving Average Crossover"

    override val metadata = StrategyMetadata(
        description = "Goes long when the fast SMA($fastPeriod) crosses above the " +
            "slow SMA($slowPeriod), and exits when it crosses back below.",
        requiredIndicators = listOf("SMA($fastPeriod)", "SMA($slowPeriod)"),
        parameters = listOf(
            StrategyParameter("fastPeriod", fastPeriod.toDouble()),
            StrategyParameter("slowPeriod", slowPeriod.toDouble())
        ),
        direction = PositionDirection.LONG_ONLY,
        entryRule = "Fast SMA crosses above slow SMA",
        exitRule = "Fast SMA crosses below slow SMA"
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }
        if (candles.size < slowPeriod + 1) return emptyList()

        val closes = candles.map { it.close }
        val fast = sma(closes, fastPeriod)
        val slow = sma(closes, slowPeriod)

        val last = candles.lastIndex
        val previousFast = fast[last - 1] ?: return emptyList()
        val previousSlow = slow[last - 1] ?: return emptyList()
        val currentFast = fast[last] ?: return emptyList()
        val currentSlow = slow[last] ?: return emptyList()

        val signalType = when {
            previousFast <= previousSlow && currentFast > currentSlow ->
                SignalType.BUY

            previousFast >= previousSlow && currentFast < currentSlow ->
                SignalType.SELL

            else ->
                SignalType.HOLD
        }

        val currentCandle = candles[last]
        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = confidence(currentFast, currentSlow),
                reason = "Fast SMA=$currentFast, Slow SMA=$currentSlow"
            )
        )
    }

    private fun confidence(
        fast: Double,
        slow: Double
    ): Double {
        if (slow == 0.0) return 0.0

        return ((fast - slow) / slow)
            .coerceIn(-1.0, 1.0)
            .let { kotlin.math.abs(it) }
    }
}
