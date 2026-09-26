package com.algotrader.backtest

/**
 * Configuration for a single backtest run: starting capital and how many
 * units to trade per entry.
 */
data class BacktestConfig(
    val initialCapital: Double = 100_000.0,
    val positionSizing: PositionSizing = PositionSizing.FixedQuantity(1.0)
) {
    init {
        require(initialCapital > 0.0) { "initialCapital must be greater than zero" }
    }
}
