package com.algotrader.strategy

import com.algotrader.strategy.indicator.bollingerBands

/**
 * Simple band-touch mean reversion — a level check, not a crossover: it
 * reacts to where the close currently sits relative to the bands.
 *
 * Entry (long): the close is at or below the lower band.
 * Entry (short) / exit (long): the close is at or above the upper band.
 * Otherwise: hold / no action.
 */
class BollingerBandsStrategy(
    private val period: Int = 20,
    private val stdDevMultiplier: Double = 2.0
) : Strategy {

    init {
        require(period > 1) { "period must be greater than one" }
        require(stdDevMultiplier > 0.0) { "stdDevMultiplier must be positive" }
    }

    override val name: String = "Bollinger Bands"

    override val metadata = StrategyMetadata(
        description = "Signals long when price closes at or below the lower " +
            "Bollinger Band($period, ${stdDevMultiplier}x) and short when it closes " +
            "at or above the upper band.",
        requiredIndicators = listOf("BollingerBands($period, $stdDevMultiplier)"),
        parameters = listOf(
            StrategyParameter("period", period.toDouble()),
            StrategyParameter("stdDevMultiplier", stdDevMultiplier)
        ),
        direction = PositionDirection.LONG_AND_SHORT,
        entryRule = "Close at/below the lower band (long) or at/above the upper band (short)",
        exitRule = "Close no longer beyond the entry band"
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }
        if (candles.size < period) return emptyList()

        val closes = candles.map { it.close }
        val bands = bollingerBands(closes, period, stdDevMultiplier)

        val last = candles.lastIndex
        val upper = bands.upper[last] ?: return emptyList()
        val lower = bands.lower[last] ?: return emptyList()
        val currentClose = closes[last]

        val signalType = when {
            currentClose <= lower -> SignalType.BUY
            currentClose >= upper -> SignalType.SELL
            else -> SignalType.HOLD
        }

        val currentCandle = candles[last]
        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = 0.5,
                reason = "Close=$currentClose, Upper=$upper, Lower=$lower"
            )
        )
    }
}
