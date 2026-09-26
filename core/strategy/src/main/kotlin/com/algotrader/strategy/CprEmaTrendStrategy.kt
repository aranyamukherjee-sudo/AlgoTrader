package com.algotrader.strategy

import com.algotrader.strategy.indicator.dailyCentralPivotRange
import com.algotrader.strategy.indicator.ema
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Combines a trend filter (EMA) with a level filter (the prior day's
 * Central Pivot Range) so a trade only triggers when both agree — a common
 * way to cut down on false signals from either indicator alone.
 *
 * Entry (long): close is above both the EMA and the prior day's CPR top-central (TC).
 * Entry (short): close is below both the EMA and the prior day's CPR bottom-central (BC).
 * Otherwise: hold / no action.
 *
 * [zone] controls how candle timestamps are grouped into calendar days for
 * the CPR calculation; it should match the exchange's local trading day.
 */
class CprEmaTrendStrategy(
    private val emaPeriod: Int = 20,
    private val zone: ZoneId = ZoneOffset.UTC
) : Strategy {

    init {
        require(emaPeriod > 0) { "emaPeriod must be greater than zero" }
    }

    override val name: String = "CPR + EMA Trend"

    override val metadata = StrategyMetadata(
        description = "Goes long when price closes above both EMA($emaPeriod) and the " +
            "prior day's CPR top-central, and short when it closes below both the EMA " +
            "and the prior day's CPR bottom-central.",
        requiredIndicators = listOf("EMA($emaPeriod)", "CPR(daily)"),
        parameters = listOf(StrategyParameter("emaPeriod", emaPeriod.toDouble())),
        direction = PositionDirection.LONG_AND_SHORT,
        entryRule = "Close above EMA and above the prior day's CPR top-central (long); " +
            "close below EMA and below the prior day's CPR bottom-central (short)",
        exitRule = "Close no longer satisfies the entry condition for the current side"
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }
        if (candles.size < emaPeriod) return emptyList()

        val closes = candles.map { it.close }
        val emaValues = ema(closes, emaPeriod)
        val cprValues = dailyCentralPivotRange(candles, zone)

        val last = candles.lastIndex
        val currentEma = emaValues[last] ?: return emptyList()
        val currentCpr = cprValues[last] ?: return emptyList()
        val currentClose = closes[last]

        val signalType = when {
            currentClose > currentEma && currentClose > currentCpr.topCentral -> SignalType.BUY
            currentClose < currentEma && currentClose < currentCpr.bottomCentral -> SignalType.SELL
            else -> SignalType.HOLD
        }

        val currentCandle = candles[last]
        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = 0.5,
                reason = "Close=$currentClose, EMA=$currentEma, " +
                    "CPR.TC=${currentCpr.topCentral}, CPR.BC=${currentCpr.bottomCentral}"
            )
        )
    }
}
