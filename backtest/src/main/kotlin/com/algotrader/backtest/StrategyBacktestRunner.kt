package com.algotrader.backtest

import com.algotrader.domain.Candle
import com.algotrader.strategy.Signal
import com.algotrader.strategyengine.StrategyConfiguration
import com.algotrader.strategyengine.StrategyFactory

/**
 * Application-facing bridge between strategy configuration and the backtest engine.
 *
 * The backtest module owns this integration because it depends on strategy-engine.
 * strategy-engine must remain independent of backtest.
 */
class StrategyBacktestRunner(
    private val factory: StrategyFactory = StrategyFactory()
) {

    fun run(
        configuration: StrategyConfiguration,
        candles: List<Candle>,
        backtestConfig: BacktestConfig = BacktestConfig()
    ): BacktestResult {
        require(candles.isNotEmpty()) {
            "candles must not be empty"
        }

        val strategy = factory.create(configuration)

        return BacktestEngine(backtestConfig).run(
            strategy = strategy,
            candles = candles
        )
    }

    fun run(
        strategyId: String,
        candles: List<Candle>,
        backtestConfig: BacktestConfig = BacktestConfig()
    ): BacktestResult =
        run(
            configuration = StrategyConfiguration(strategyId),
            candles = candles,
            backtestConfig = backtestConfig
        )
}
