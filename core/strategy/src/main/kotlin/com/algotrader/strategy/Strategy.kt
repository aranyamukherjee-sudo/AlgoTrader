package com.algotrader.strategy

/**
 * A trading strategy: something that looks at candle history (and, in
 * future, other indicator/context data) and produces a signal.
 *
 * Signal convention used throughout the engine: BUY means "I want to be
 * long, or flat a short"; SELL means "I want to be short (if the strategy's
 * [StrategyMetadata.direction] allows it), or flat a long". A strategy does
 * not need to know about position state — the backtest/execution layer
 * translates these signals into actual position changes.
 */
interface Strategy {

    val name: String

    /** Static description of what this strategy needs and how it behaves. */
    val metadata: StrategyMetadata

    fun evaluate(context: StrategyContext): List<Signal>
}
