package com.algotrader.strategy

import com.algotrader.strategy.indicator.donchianChannel

/**
 * Turtle-style breakout using the *previous completed bar's* Donchian
 * Channel — never the current bar's own channel.
 *
 * [donchianChannel] computes upper/lower[i] from the window ending at and
 * including index i, so it includes the current bar's own high/low. Naively
 * comparing the current bar against `channel.upper[currentIndex]` (or,
 * worse, comparing the current bar's own high against it) would let the
 * current bar's own extreme define — and in the high-vs-inclusive-upper
 * case, trivially satisfy — its own breakout threshold. To avoid that
 * look-ahead, this strategy always reads the channel at the *previous*
 * index (the channel as it stood before the current bar printed) and
 * compares the current close against that.
 *
 * Entry (long): the close breaks above the previous bar's upper channel.
 * Entry (short): the close breaks below the previous bar's lower channel.
 * Otherwise: hold / no action.
 */
class DonchianChannelStrategy(
    private val period: Int = 20
) : Strategy {

    init {
        require(period > 1) { "period must be greater than one" }
    }

    override val name: String = "Donchian Channel Breakout"

    override val metadata = StrategyMetadata(
        description = "Enters long on a close above the prior $period-period Donchian " +
            "upper channel, and short on a close below the prior lower channel.",
        requiredIndicators = listOf("Donchian($period)"),
        parameters = listOf(StrategyParameter("period", period.toDouble())),
        direction = PositionDirection.LONG_AND_SHORT,
        entryRule = "Close breaks above the prior upper channel (long) or below the prior lower channel (short)",
        exitRule = "Opposite breakout"
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }
        if (candles.size < period + 1) return emptyList()

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }
        val channel = donchianChannel(highs, lows, period)

        val last = candles.lastIndex
        // Deliberately last - 1: the channel as of the *previous* bar, so the
        // current bar's own high/low can never define its own breakout level.
        val priorUpper = channel.upper[last - 1] ?: return emptyList()
        val priorLower = channel.lower[last - 1] ?: return emptyList()
        val currentClose = closes[last]

        val signalType = when {
            currentClose > priorUpper -> SignalType.BUY
            currentClose < priorLower -> SignalType.SELL
            else -> SignalType.HOLD
        }

        val currentCandle = candles[last]
        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = 0.5,
                reason = "Close=$currentClose, PriorUpper=$priorUpper, PriorLower=$priorLower"
            )
        )
    }
}
