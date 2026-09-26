package com.algotrader.backtest

import java.time.Instant

/**
 * One completed round-trip trade produced by the backtest engine.
 *
 * [entryIndex]/[exitIndex] are positions in the candle list that was
 * backtested and are always reliable; [entryTimestamp]/[exitTimestamp] are
 * preserved from the underlying candles for display and for computing a
 * wall-clock holding duration where that's meaningful.
 */
data class BacktestTrade(
    val direction: TradeDirection,
    val entryIndex: Int,
    val entryTimestamp: Instant,
    val entryPrice: Double,
    val exitIndex: Int,
    val exitTimestamp: Instant,
    val exitPrice: Double,
    val quantity: Double
) {
    val grossPnl: Double
        get() = when (direction) {
            TradeDirection.LONG -> (exitPrice - entryPrice) * quantity
            TradeDirection.SHORT -> (entryPrice - exitPrice) * quantity
        }

    val returnPercent: Double
        get() {
            val notional = entryPrice * quantity
            return if (notional == 0.0) 0.0 else grossPnl / notional * 100.0
        }

    /** Number of bars the position was held; 0 if opened and closed on the same bar. */
    val holdingPeriodBars: Int
        get() = exitIndex - entryIndex

    val isWin: Boolean
        get() = grossPnl > 0.0
}
