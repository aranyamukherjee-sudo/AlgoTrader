package com.algotrader.strategyengine

import com.algotrader.strategy.Strategy

/**
 * Small façade around StrategyRegistry.
 *
 * Keeping this as a separate type gives the application/backtest layer a
 * stable dependency point if strategy discovery later becomes dynamic.
 */
class StrategyFactory {

    fun create(configuration: StrategyConfiguration): Strategy =
        StrategyRegistry.create(configuration)

    fun create(strategyId: String): Strategy =
        create(StrategyConfiguration(strategyId))
}
