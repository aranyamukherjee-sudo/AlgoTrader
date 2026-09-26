package com.algotrader.strategy

import com.algotrader.strategy.indicator.macd

/**
 * MACD / signal-line crossover.
 *
 * Entry (long): the MACD line crosses from at-or-below the signal line to above it.
 * Entry (short) / exit (long): the MACD line crosses from at-or-above the signal line to below it.
 * Otherwise: hold / no action.
 */
class MacdStrategy(
    private val fastPeriod: Int = 12,
    private val slowPeriod: Int = 26,
    private val signalPeriod: Int = 9
) : Strategy {

    init {
        require(fastPeriod > 0) { "fastPeriod must be greater than zero" }
        require(slowPeriod > fastPeriod) { "slowPeriod must be greater than fastPeriod" }
        require(signalPeriod > 0) { "signalPeriod must be greater than zero" }
    }

    override val name: String = "MACD"

    override val metadata = StrategyMetadata(
        description = "Goes long when the MACD($fastPeriod,$slowPeriod) line crosses " +
            "above its $signalPeriod-period signal line, and short on the opposite crossover.",
        requiredIndicators = listOf("MACD($fastPeriod,$slowPeriod,$signalPeriod)"),
        parameters = listOf(
            StrategyParameter("fastPeriod", fastPeriod.toDouble()),
            StrategyParameter("slowPeriod", slowPeriod.toDouble()),
            StrategyParameter("signalPeriod", signalPeriod.toDouble())
        ),
        direction = PositionDirection.LONG_AND_SHORT,
        entryRule = "MACD line crosses above signal line (long) or below it (short)",
        exitRule = "Opposite MACD/signal-line crossover"
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }
        if (candles.size < slowPeriod + signalPeriod + 1) return emptyList()

        val closes = candles.map { it.close }
        val result = macd(closes, fastPeriod, slowPeriod, signalPeriod)

        val last = candles.lastIndex
        val previousMacd = result.macdLine[last - 1] ?: return emptyList()
        val previousSignal = result.signalLine[last - 1] ?: return emptyList()
        val currentMacd = result.macdLine[last] ?: return emptyList()
        val currentSignal = result.signalLine[last] ?: return emptyList()

        val signalType = when {
            previousMacd <= previousSignal && currentMacd > currentSignal -> SignalType.BUY
            previousMacd >= previousSignal && currentMacd < currentSignal -> SignalType.SELL
            else -> SignalType.HOLD
        }

        val currentCandle = candles[last]
        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = 0.5,
                reason = "MACD=$currentMacd, Signal=$currentSignal"
            )
        )
    }
}
