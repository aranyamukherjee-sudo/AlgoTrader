package com.algotrader.backtest

/** The full output of one backtest run: trades, the equity curve, and summary metrics. */
data class BacktestResult(
    val strategyName: String,
    val config: BacktestConfig,
    val finalEquity: Double,
    val trades: List<BacktestTrade>,
    val equityCurve: List<EquityPoint>,
    val metrics: PerformanceMetrics,
    /** FULL for a standard backtest; IN_SAMPLE / OUT_OF_SAMPLE for the two segments of an OOS run. */
    val sample: BacktestSample = BacktestSample.FULL
)
