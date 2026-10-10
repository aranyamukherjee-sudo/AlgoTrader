package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestSample
import java.util.Locale

/**
 * CSV exports generated from persisted backtest results.
 *
 * Research costs are assumptions, not broker/statutory charges.
 * The indicative F&O turnover field is the sum of absolute realised trade
 * P&L; it is not a certified tax-turnover calculation.
 */
internal object BacktestCsvExporter {

    private fun csv(value: Any?): String {
        val text = value?.toString() ?: ""
        return "\"" + text.replace("\"", "\"\"") + "\""
    }

    private fun number(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.10f", value) else ""

    private fun row(vararg values: Any?): String =
        values.joinToString(",") { csv(it) }

    fun tradesCsv(
        instrumentSymbol: String,
        timeframe: String,
        results: List<BacktestResult>,
        isFutures: Boolean
    ): String {
        val lines = mutableListOf(
            row(
                "instrument_symbol", "timeframe", "strategy", "sample",
                "trade_number", "direction", "entry_timestamp_utc",
                "exit_timestamp_utc", "entry_price", "exit_price", "quantity",
                "gross_pnl", "entry_notional", "exit_notional",
                "research_commission_percent", "research_slippage_bps",
                "research_fixed_cost_per_trade", "research_cost",
                "cost_adjusted_pnl", "absolute_realised_pnl",
                "indicative_fno_turnover", "exit_reason", "exit_detail"
            )
        )

        results.forEach { result ->
            val model = result.config.researchCostModel
            result.trades.forEachIndexed { index, trade ->
                val costs = trade.researchCosts(model)
                lines += row(
                    instrumentSymbol,
                    timeframe,
                    result.strategyName,
                    result.sample.name,
                    index + 1,
                    trade.direction.name,
                    trade.entryTimestamp.toString(),
                    trade.exitTimestamp.toString(),
                    number(trade.entryPrice),
                    number(trade.exitPrice),
                    number(trade.quantity),
                    number(trade.grossPnl),
                    number(trade.entryPrice * trade.quantity),
                    number(trade.exitPrice * trade.quantity),
                    number(model.commissionRatePercent),
                    number(model.slippageBps),
                    number(model.fixedCostPerTrade),
                    number(costs),
                    number(trade.grossPnl - costs),
                    number(kotlin.math.abs(trade.grossPnl)),
                    if (isFutures) number(kotlin.math.abs(trade.grossPnl)) else "",
                    trade.exitReason?.name.orEmpty(),
                    trade.exitDetail.orEmpty()
                )
            }
        }

        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    fun comparisonCsv(
        instrumentSymbol: String,
        timeframe: String,
        results: List<BacktestResult>,
        isFutures: Boolean
    ): String {
        val lines = mutableListOf(
            row(
                "instrument_symbol", "timeframe", "strategy", "sample",
                "initial_capital", "final_equity", "trade_count",
                "winning_trades", "losing_trades", "breakeven_trades",
                "gross_expectancy_per_trade", "average_win_loss_ratio",
                "saved_gross_pnl", "trade_sum_gross_pnl",
                "gross_pnl_difference", "saved_research_costs",
                "recomputed_research_costs", "research_cost_difference",
                "saved_cost_adjusted_pnl", "recomputed_cost_adjusted_pnl",
                "cost_adjusted_pnl_difference",
                "sum_absolute_realised_trade_pnl",
                "indicative_fno_turnover",
                "pnl_reconciliation", "research_cost_reconciliation",
                "cost_adjusted_pnl_reconciliation",
                "turnover_note", "research_cost_note"
            )
        )

        results.forEach { result ->
            val metrics = result.metrics
            val model = result.config.researchCostModel
            val tradeGross = result.trades.sumOf { it.grossPnl }
            val recomputedCosts = result.trades.sumOf { it.researchCosts(model) }
            val recomputedAdjusted = tradeGross - recomputedCosts
            val absolutePnl = result.trades.sumOf { kotlin.math.abs(it.grossPnl) }
            val grossDiff = tradeGross - metrics.grossPnl
            val costDiff = recomputedCosts - metrics.researchCosts
            val adjustedDiff = recomputedAdjusted - metrics.costAdjustedNetProfit
            val tolerance = 0.01

            lines += row(
                instrumentSymbol,
                timeframe,
                result.strategyName,
                result.sample.name,
                number(result.config.initialCapital),
                number(result.finalEquity),
                result.trades.size,
                result.trades.count { it.grossPnl > 0.0 },
                result.trades.count { it.grossPnl < 0.0 },
                result.trades.count { it.grossPnl == 0.0 },
                number(metrics.expectancyPerTrade),
                metrics.averageWinLossRatio?.let { number(it) } ?: "",
                number(metrics.grossPnl),
                number(tradeGross),
                number(grossDiff),
                number(metrics.researchCosts),
                number(recomputedCosts),
                number(costDiff),
                number(metrics.costAdjustedNetProfit),
                number(recomputedAdjusted),
                number(adjustedDiff),
                number(absolutePnl),
                if (isFutures) number(absolutePnl) else "",
                if (kotlin.math.abs(grossDiff) <= tolerance) "MATCH" else "DIFFERENCE",
                if (kotlin.math.abs(costDiff) <= tolerance) "MATCH" else "DIFFERENCE",
                if (kotlin.math.abs(adjustedDiff) <= tolerance) "MATCH" else "DIFFERENCE",
                if (isFutures) {
                    "Indicative only: sum of absolute realised trade P&L; verify applicable tax rules"
                } else {
                    "Not applicable to this non-futures backtest"
                },
                "Assumed research friction; not actual broker or statutory charges"
            )
        }

        return lines.joinToString("\r\n", postfix = "\r\n")
    }
}
