package com.algotrader.strategyengine

/**
 * Runtime configuration for a strategy.
 *
 * Parameter values are supplied by the caller/UI/backtest configuration.
 * Strategy-specific defaults remain owned by the concrete strategy classes.
 */
data class StrategyConfiguration(
    val strategyId: String,
    val parameters: Map<String, Double> = emptyMap()
)
