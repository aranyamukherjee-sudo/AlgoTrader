package com.algotrader.strategy

/** A single configurable numeric input for a strategy (e.g. "fastPeriod" = 5.0). */
data class StrategyParameter(
    val name: String,
    val value: Double
)
