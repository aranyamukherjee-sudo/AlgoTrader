package com.algotrader.strategyengine

import com.algotrader.domain.Candle
import com.algotrader.domain.Portfolio
import com.algotrader.strategy.Signal
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.StrategyContext

/**
 * Executes one strategy against a candle history and portfolio snapshot.
 *
 * The runner deliberately does not mutate portfolio state and does not
 * execute orders. It only translates application inputs into StrategyContext
 * and returns the strategy's signals.
 */
class StrategyRunner(
    private val factory: StrategyFactory = StrategyFactory()
) {

    fun evaluate(
        configuration: StrategyConfiguration,
        candles: List<Candle>,
        portfolio: Portfolio
    ): List<Signal> {
        val strategy = factory.create(configuration)
        return evaluate(strategy, candles, portfolio)
    }

    fun evaluate(
        strategy: Strategy,
        candles: List<Candle>,
        portfolio: Portfolio
    ): List<Signal> {
        if (candles.isEmpty()) return emptyList()

        val orderedCandles = candles.sortedBy { it.timestamp }

        return strategy.evaluate(
            StrategyContext(
                candles = orderedCandles,
                portfolio = portfolio
            )
        )
    }
}
