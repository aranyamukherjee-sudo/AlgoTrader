package com.algotrader.backtest

/**
 * Deterministic generic execution-friction model for research robustness.
 *
 * This is deliberately broker/instrument agnostic. It is intended to answer
 * whether a strategy remains viable after assumed execution friction.
 *
 * [commissionRatePercent] applies to the combined entry and exit notional.
 * [slippageBps] is a deterministic execution-friction charge in basis points
 * applied to the combined entry and exit notional. It does not modify the
 * recorded historical fill prices; it is a research adjustment used only
 * for cost-adjusted P&L.
 *
 * [fixedCostPerTrade] is charged once per completed round trip.
 *
 * All defaults are zero, preserving the historical backtest behavior.
 */
data class ResearchCostModel(
    val commissionRatePercent: Double = 0.0,
    val slippageBps: Double = 0.0,
    val fixedCostPerTrade: Double = 0.0
) {
    init {
        require(commissionRatePercent >= 0.0) {
            "commissionRatePercent must be non-negative"
        }
        require(slippageBps >= 0.0) {
            "slippageBps must be non-negative"
        }
        require(fixedCostPerTrade >= 0.0) {
            "fixedCostPerTrade must be non-negative"
        }
    }

    fun costs(trade: BacktestTrade): Double {
        val entryNotional = trade.entryPrice * trade.quantity
        val exitNotional = trade.exitPrice * trade.quantity

        val commission =
            (entryNotional + exitNotional) * commissionRatePercent / 100.0

        val slippageRate = slippageBps / 10_000.0
        val slippage = (entryNotional + exitNotional) * slippageRate

        return commission + slippage + fixedCostPerTrade
    }
}
