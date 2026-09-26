package com.algotrader.strategy

import com.algotrader.strategy.indicator.donchianChannel
import com.algotrader.strategy.indicator.ema

/**
 * Donchian breakout confirmed by an EMA trend filter — the same
 * look-ahead-safe breakout logic as [DonchianChannelStrategy] (using the
 * *prior* bar's channel), but a breakout only counts if it agrees with the
 * direction of the EMA.
 *
 * Entry (long): close breaks above the prior upper channel AND close is above the EMA.
 * Entry (short): close breaks below the prior lower channel AND close is below the EMA.
 * Otherwise: hold / no action.
 */
class DonchianEmaTrendStrategy(
    private val donchianPeriod: Int = 20,
    private val emaPeriod: Int = 50
) : Strategy {

    init {
        require(donchianPeriod > 1) { "donchianPeriod must be greater than one" }
        require(emaPeriod > 0) { "emaPeriod must be greater than zero" }
    }

    override val name: String = "Donchian + EMA Trend"

    override val metadata = StrategyMetadata(
        description = "Enters long on a close above the prior $donchianPeriod-period " +
            "Donchian upper channel while above EMA($emaPeriod), and short on a close " +
            "below the prior lower channel while below the EMA.",
        requiredIndicators = listOf("Donchian($donchianPeriod)", "EMA($emaPeriod)"),
        parameters = listOf(
            StrategyParameter("donchianPeriod", donchianPeriod.toDouble()),
            StrategyParameter("emaPeriod", emaPeriod.toDouble())
        ),
        direction = PositionDirection.LONG_AND_SHORT,
        entryRule = "Close breaks above the prior upper channel while above the EMA (long); " +
            "close breaks below the prior lower channel while below the EMA (short)",
        exitRule = "Opposite breakout, or price crosses back through the EMA"
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }
        val minSize = maxOf(donchianPeriod + 1, emaPeriod)
        if (candles.size < minSize) return emptyList()

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }
        val channel = donchianChannel(highs, lows, donchianPeriod)
        val emaValues = ema(closes, emaPeriod)

        val last = candles.lastIndex
        // Same anti-look-ahead reasoning as DonchianChannelStrategy: use the
        // *previous* bar's channel, never the current bar's own.
        val priorUpper = channel.upper[last - 1] ?: return emptyList()
        val priorLower = channel.lower[last - 1] ?: return emptyList()
        val currentEma = emaValues[last] ?: return emptyList()
        val currentClose = closes[last]

        val signalType = when {
            currentClose > priorUpper && currentClose > currentEma -> SignalType.BUY
            currentClose < priorLower && currentClose < currentEma -> SignalType.SELL
            else -> SignalType.HOLD
        }

        val currentCandle = candles[last]
        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = 0.5,
                reason = "Close=$currentClose, PriorUpper=$priorUpper, PriorLower=$priorLower, EMA=$currentEma"
            )
        )
    }
}
