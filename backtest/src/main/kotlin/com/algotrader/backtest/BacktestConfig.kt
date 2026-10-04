package com.algotrader.backtest

/**
 * Configuration for a single backtest run: starting capital and how many
 * units to trade per entry.
 *
 * [lotSize] is the instrument's contract size in units (e.g. 65 for NIFTY
 * futures). It is supplied per instrument by the caller; 1 means the
 * instrument trades in single units. When greater than 1, every quantity the
 * engine produces is a whole number of lots. Margin is not modelled.
 */
data class BacktestConfig(
    val initialCapital: Double = 100_000.0,
    val positionSizing: PositionSizing = PositionSizing.FixedQuantity(1.0),
    val lotSize: Int = 1
) {
    init {
        require(initialCapital > 0.0) { "initialCapital must be greater than zero" }
        require(lotSize >= 1) { "lotSize must be at least 1" }
    }
}
