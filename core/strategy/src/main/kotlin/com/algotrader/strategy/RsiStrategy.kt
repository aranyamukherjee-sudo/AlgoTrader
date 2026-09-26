package com.algotrader.strategy

import com.algotrader.strategy.indicator.rsi

/**
 * Simple RSI level strategy — deliberately a level check, not a crossover:
 * it reacts to where RSI currently *is*, not how it got there.
 *
 * Entry (long) / exit (short): RSI($period) is below [oversold].
 * Entry (short) / exit (long): RSI($period) is above [overbought].
 * Otherwise: hold / no action.
 *
 * This does not track any state across bars (no "wait for RSI to cross back
 * through 50" or similar) — one deterministic level check per bar, as
 * specified.
 */
class RsiStrategy(
    private val period: Int = 14,
    private val oversold: Double = 30.0,
    private val overbought: Double = 70.0
) : Strategy {

    init {
        require(period > 0) { "period must be greater than zero" }
        require(oversold in 0.0..100.0) { "oversold must be between 0 and 100" }
        require(overbought in 0.0..100.0) { "overbought must be between 0 and 100" }
        require(overbought > oversold) { "overbought must be greater than oversold" }
    }

    override val name: String = "RSI"

    override val metadata = StrategyMetadata(
        description = "Signals long while RSI($period) is below $oversold (oversold) " +
            "and short while RSI($period) is above $overbought (overbought).",
        requiredIndicators = listOf("RSI($period)"),
        parameters = listOf(
            StrategyParameter("period", period.toDouble()),
            StrategyParameter("oversold", oversold),
            StrategyParameter("overbought", overbought)
        ),
        direction = PositionDirection.LONG_AND_SHORT,
        entryRule = "RSI below $oversold (long) or above $overbought (short)",
        exitRule = "RSI no longer beyond the entry threshold"
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }
        if (candles.size <= period) return emptyList()

        val closes = candles.map { it.close }
        val values = rsi(closes, period)

        val last = candles.lastIndex
        val currentRsi = values[last] ?: return emptyList()

        val signalType = when {
            currentRsi < oversold -> SignalType.BUY
            currentRsi > overbought -> SignalType.SELL
            else -> SignalType.HOLD
        }

        val currentCandle = candles[last]
        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = (kotlin.math.abs(currentRsi - 50.0) / 50.0).coerceIn(0.0, 1.0),
                reason = "RSI=$currentRsi"
            )
        )
    }
}
