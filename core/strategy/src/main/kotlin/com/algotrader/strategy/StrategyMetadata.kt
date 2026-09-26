package com.algotrader.strategy

/**
 * Everything about a strategy other than the signal-generation logic itself:
 * what it needs, how it's configured, and how positions should be managed
 * around it. The backtest engine and any future UI both read this rather
 * than having strategy-specific knowledge baked into them.
 */
data class StrategyMetadata(
    val description: String,
    val requiredIndicators: List<String>,
    val parameters: List<StrategyParameter>,
    val direction: PositionDirection,
    val entryRule: String,
    val exitRule: String,
    /** Optional fixed stop-loss, as a percent away from entry price. Null if unused. */
    val stopLossPercent: Double? = null,
    /** Optional fixed take-profit, as a percent away from entry price. Null if unused. */
    val takeProfitPercent: Double? = null
)
