package com.algotrader.app.backtest

import com.algotrader.app.Instruments
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.ExitReason
import com.algotrader.backtest.PositionSizing
import com.algotrader.backtest.TradeDirection
import com.algotrader.domain.Timeframe
import com.algotrader.strategyengine.StrategyFactory
import java.util.Locale
import kotlin.math.abs

/**
 * Presentation-only formatting helpers shared by the Backtest UI and the
 * backtest notifications. Nothing here reads or writes persisted state.
 */
object BacktestFormat {

    fun money(value: Double): String =
        String.format(Locale.US, "\u20b9%,.2f", value)

    fun signedMoney(value: Double): String {
        val sign = if (value > 0.0) "+" else if (value < 0.0) "\u2212" else ""
        return sign + money(abs(value))
    }

    fun percent(value: Double): String =
        String.format(Locale.US, "%.2f%%", value)

    fun signedPercent(value: Double): String {
        val sign = if (value > 0.0) "+" else if (value < 0.0) "\u2212" else ""
        return sign + percent(abs(value))
    }

    fun quantity(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    fun timeframeLabel(timeframe: Timeframe): String = when (timeframe) {
        Timeframe.MINUTE_1 -> "1m"
        Timeframe.MINUTE_5 -> "5m"
        Timeframe.MINUTE_15 -> "15m"
        Timeframe.MINUTE_30 -> "30m"
        Timeframe.HOUR_1 -> "1h"
        Timeframe.HOUR_4 -> "4h"
        Timeframe.DAY_1 -> "1D"
    }

    /** Maps a backend symbol such as NSE:NIFTY50-INDEX to its display name. */
    fun instrumentName(symbol: String): String =
        Instruments.all.firstOrNull { it.backendSymbol == symbol }?.displayName ?: symbol

    /** Canonical strategy display name for a registry id. */
    fun strategyName(strategyId: String): String =
        runCatching { StrategyFactory().create(strategyId).name }.getOrDefault(strategyId)

    /** "fastPeriod" -> "Fast Period". */
    fun parameterLabel(name: String): String {
        val spaced = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        return spaced.replaceFirstChar { it.uppercase() }
    }

    fun parameterValue(value: Double): String = quantity(value)

    fun sizingLabel(sizing: PositionSizing, lotSize: Int = 1): String = when (sizing) {
        is PositionSizing.FixedQuantity -> "Fixed \u00b7 ${quantity(sizing.quantity)} units"
        is PositionSizing.PercentOfEquity -> "${quantity(sizing.percent)}% of equity"
        is PositionSizing.FixedLots -> {
            val noun = if (sizing.lots == 1) "lot" else "lots"
            if (lotSize > 1) {
                "Fixed \u00b7 ${sizing.lots} $noun (${sizing.lots * lotSize} units)"
            } else {
                "Fixed \u00b7 ${sizing.lots} $noun"
            }
        }
    }

    /** Contract size (units per lot) for a backend symbol; 1 if the instrument has none configured. */
    fun lotSizeFor(symbol: String): Int =
        Instruments.all.firstOrNull { it.backendSymbol == symbol }?.lotSize ?: 1

    /** "1 lot" / "2 lots" for a traded quantity, or null when the instrument has no lots. */
    fun lotsLabel(quantity: Double, lotSize: Int): String? {
        if (lotSize <= 1) return null
        val lots = Math.round(quantity / lotSize)
        return if (lots == 1L) "1 lot" else "$lots lots"
    }

    /**
     * Human-readable exit reason taken from what the engine recorded.
     * Results saved before reasons were tracked say so instead of guessing.
     */
    fun exitReasonLabel(trade: BacktestTrade): String = when (trade.exitReason) {
        ExitReason.STRATEGY_SIGNAL -> {
            val signal = if (trade.direction == TradeDirection.LONG) "SELL" else "BUY"
            "Strategy signal ($signal)"
        }
        ExitReason.END_OF_DATA -> "End of test data \u2014 closed at final bar's close"
        null -> "Not recorded (saved before exit reasons were tracked)"
    }

    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return when {
            h > 0L -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0L -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> String.format(Locale.US, "%ds", s)
        }
    }
}
