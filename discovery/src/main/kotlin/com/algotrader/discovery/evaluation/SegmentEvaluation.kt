package com.algotrader.discovery.evaluation

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestEngine
import com.algotrader.backtest.BacktestTrade
import com.algotrader.discovery.split.SegmentRole
import com.algotrader.domain.Candle
import com.algotrader.strategy.Strategy

/**
 * What one candidate did on ONE data segment. Every number comes from the
 * existing backtest engine's result; nothing is re-derived here except
 * [periodConsistency].
 *
 * Not modelled: transaction costs, slippage, margin (the engine has none).
 */
data class SegmentEvaluation(
    val role: SegmentRole,
    val bars: Int,
    val trades: Int,
    val netProfit: Double,
    val returnPercent: Double,
    val maxDrawdownPercent: Double,
    /** Null when there were no losing trades (undefined, not "infinite"). */
    val profitFactor: Double?,
    val winRate: Double,
    val averageTradePnl: Double,
    /** Share (0..1) of equal time buckets in the segment whose trades had positive total P&L. */
    val periodConsistency: Double
) {
    init {
        require(bars >= 0 && trades >= 0) { "bars and trades must not be negative" }
        require(periodConsistency in 0.0..1.0) { "periodConsistency must be within 0..1" }
    }
}

class SegmentEvaluator(
    private val config: BacktestConfig = BacktestConfig(),
    private val consistencyPeriods: Int = 4
) {
    init {
        require(consistencyPeriods >= 1) { "consistencyPeriods must be at least 1" }
    }

    fun evaluate(role: SegmentRole, strategy: Strategy, candles: List<Candle>): SegmentEvaluation {
        val result = BacktestEngine(config).run(strategy, candles)
        val m = result.metrics
        return SegmentEvaluation(
            role = role,
            bars = candles.size,
            trades = m.totalTrades,
            netProfit = m.netProfit,
            returnPercent = m.totalReturnPercent,
            maxDrawdownPercent = m.maxDrawdownPercent,
            profitFactor = m.profitFactor,
            winRate = m.winRate,
            averageTradePnl = m.averageTradePnl,
            periodConsistency = periodConsistency(result.trades, candles.size, consistencyPeriods)
        )
    }

    companion object {
        /** Buckets trades by exit bar into [periods] equal slices of [bars]; returns the share with positive P&L. */
        internal fun periodConsistency(trades: List<BacktestTrade>, bars: Int, periods: Int): Double {
            if (bars <= 0 || periods <= 0) return 0.0
            val sums = DoubleArray(periods)
            for (trade in trades) {
                val bucket = minOf(periods - 1, trade.exitIndex * periods / bars)
                sums[bucket] += trade.grossPnl
            }
            return sums.count { it > 0.0 }.toDouble() / periods
        }
    }
}
