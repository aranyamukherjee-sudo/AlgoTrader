package com.algotrader.backtest

/** Summary statistics for a completed backtest run. */
data class PerformanceMetrics(
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    val netProfit: Double,
    val totalReturnPercent: Double,
    val maxDrawdown: Double,
    val maxDrawdownPercent: Double,
    val averageTradePnl: Double,
    /** Gross profit divided by the magnitude of gross loss; null when there are no losing trades. */
    val profitFactor: Double?,
    val averageWinningTrade: Double?,
    val averageLosingTrade: Double?
)

/**
 * Computes [PerformanceMetrics] from a completed set of trades and the
 * per-candle equity curve. Pure and side-effect free, so it can be tested
 * directly without running the engine.
 *
 * Max drawdown is measured against the full per-candle equity curve (which
 * includes unrealized, intra-trade swings), not just the equity at trade
 * close — a more realistic figure than looking only at realized P&L.
 */
fun computePerformanceMetrics(
    initialCapital: Double,
    finalEquity: Double,
    trades: List<BacktestTrade>,
    equityCurve: List<EquityPoint>
): PerformanceMetrics {
    val winners = trades.filter { it.isWin }
    val losers = trades.filterNot { it.isWin }

    val grossProfit = winners.sumOf { it.grossPnl }
    val grossLoss = losers.sumOf { it.grossPnl } // <= 0.0
    val netProfit = finalEquity - initialCapital
    val totalReturnPercent = if (initialCapital == 0.0) 0.0 else netProfit / initialCapital * 100.0
    val winRate = if (trades.isEmpty()) 0.0 else winners.size.toDouble() / trades.size
    val averageTradePnl = if (trades.isEmpty()) 0.0 else trades.sumOf { it.grossPnl } / trades.size
    val profitFactor = if (grossLoss == 0.0) null else grossProfit / -grossLoss
    val averageWinningTrade = if (winners.isEmpty()) null else grossProfit / winners.size
    val averageLosingTrade = if (losers.isEmpty()) null else grossLoss / losers.size

    var peak = equityCurve.firstOrNull()?.equity ?: initialCapital
    var maxDrawdown = 0.0
    var maxDrawdownPercent = 0.0
    for (point in equityCurve) {
        if (point.equity > peak) peak = point.equity
        val drawdown = peak - point.equity
        if (drawdown > maxDrawdown) maxDrawdown = drawdown
        if (peak > 0.0) {
            val drawdownPercent = drawdown / peak * 100.0
            if (drawdownPercent > maxDrawdownPercent) maxDrawdownPercent = drawdownPercent
        }
    }

    return PerformanceMetrics(
        totalTrades = trades.size,
        winningTrades = winners.size,
        losingTrades = losers.size,
        winRate = winRate,
        grossProfit = grossProfit,
        grossLoss = grossLoss,
        netProfit = netProfit,
        totalReturnPercent = totalReturnPercent,
        maxDrawdown = maxDrawdown,
        maxDrawdownPercent = maxDrawdownPercent,
        averageTradePnl = averageTradePnl,
        profitFactor = profitFactor,
        averageWinningTrade = averageWinningTrade,
        averageLosingTrade = averageLosingTrade
    )
}
