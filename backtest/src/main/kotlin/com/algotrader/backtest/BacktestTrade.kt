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
    val quantity: Double,
    /** Why the trade was closed; null for results saved before exit reasons were tracked. */
    val exitReason: ExitReason? = null,
    /**
     * For [ExitReason.STRATEGY_SIGNAL]: the strategy's own explanation of the
     * signal that closed the trade (evaluated on the bar before the exit
     * fill). Null when unavailable.
     */
    val exitDetail: String? = null
) {
    val grossPnl: Double
        get() = when (direction) {
            TradeDirection.LONG -> (exitPrice - entryPrice) * quantity
            TradeDirection.SHORT -> (entryPrice - exitPrice) * quantity
        }

    /**
     * Deterministic round-trip research friction under the supplied model.
     * Historical fill prices and [grossPnl] remain unchanged.
     */
    fun researchCosts(model: ResearchCostModel): Double = model.costs(this)

    /**
     * Cost-adjusted P&L. With the default zero-cost model this equals [grossPnl].
     */
    fun netPnl(model: ResearchCostModel): Double =
        grossPnl - researchCosts(model)

    /**
     * Contract value at entry (entry price x quantity). This is exposure, NOT
     * the margin or capital required to hold the position.
     */
    val notionalExposure: Double
        get() = entryPrice * quantity

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
