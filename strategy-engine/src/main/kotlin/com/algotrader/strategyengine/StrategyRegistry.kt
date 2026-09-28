package com.algotrader.strategyengine

import com.algotrader.strategy.BollingerBandsStrategy
import com.algotrader.strategy.CprEmaTrendStrategy
import com.algotrader.strategy.DonchianChannelStrategy
import com.algotrader.strategy.DonchianEmaTrendStrategy
import com.algotrader.strategy.MacdStrategy
import com.algotrader.strategy.MovingAverageCrossoverStrategy
import com.algotrader.strategy.RsiStrategy
import com.algotrader.strategy.Strategy

/**
 * Central catalogue of strategies available to the application.
 *
 * The UI and backtest layer should use stable IDs rather than constructing
 * concrete strategy classes directly.
 */
object StrategyRegistry {

    const val MOVING_AVERAGE_CROSSOVER = "moving_average_crossover"
    const val RSI = "rsi"
    const val MACD = "macd"
    const val BOLLINGER_BANDS = "bollinger_bands"
    const val DONCHIAN_CHANNEL = "donchian_channel"
    const val DONCHIAN_EMA = "donchian_ema"
    const val CPR_EMA = "cpr_ema"

    val ids: List<String> = listOf(
        MOVING_AVERAGE_CROSSOVER,
        RSI,
        MACD,
        BOLLINGER_BANDS,
        DONCHIAN_CHANNEL,
        DONCHIAN_EMA,
        CPR_EMA
    )

    fun create(configuration: StrategyConfiguration): Strategy =
        when (configuration.strategyId) {
            MOVING_AVERAGE_CROSSOVER -> MovingAverageCrossoverStrategy(
                fastPeriod = intParameter(
                    configuration,
                    "fastPeriod",
                    5
                ),
                slowPeriod = intParameter(
                    configuration,
                    "slowPeriod",
                    10
                )
            )

            RSI -> RsiStrategy(
                period = intParameter(configuration, "period", 14),
                oversold = doubleParameter(configuration, "oversold", 30.0),
                overbought = doubleParameter(configuration, "overbought", 70.0)
            )

            MACD -> MacdStrategy(
                fastPeriod = intParameter(configuration, "fastPeriod", 12),
                slowPeriod = intParameter(configuration, "slowPeriod", 26),
                signalPeriod = intParameter(configuration, "signalPeriod", 9)
            )

            BOLLINGER_BANDS -> BollingerBandsStrategy(
                period = intParameter(configuration, "period", 20),
                stdDevMultiplier = doubleParameter(
                    configuration,
                    "stdDevMultiplier",
                    2.0
                )
            )

            DONCHIAN_CHANNEL -> DonchianChannelStrategy(
                period = intParameter(configuration, "period", 20)
            )

            DONCHIAN_EMA -> DonchianEmaTrendStrategy(
                donchianPeriod = intParameter(
                    configuration,
                    "donchianPeriod",
                    20
                ),
                emaPeriod = intParameter(
                    configuration,
                    "emaPeriod",
                    50
                )
            )

            CPR_EMA -> CprEmaTrendStrategy(
                emaPeriod = intParameter(
                    configuration,
                    "emaPeriod",
                    20
                )
            )

            else -> error(
                "Unknown strategy ID: ${configuration.strategyId}"
            )
        }

    fun all(): List<Strategy> =
        ids.map { create(StrategyConfiguration(it)) }

    private fun intParameter(
        configuration: StrategyConfiguration,
        name: String,
        default: Int
    ): Int {
        val value = configuration.parameters[name] ?: default.toDouble()

        require(value.isFinite()) {
            "$name must be finite"
        }

        require(value >= 1.0) {
            "$name must be at least 1"
        }

        require(value == value.toInt().toDouble()) {
            "$name must be a whole number"
        }

        return value.toInt()
    }

    private fun doubleParameter(
        configuration: StrategyConfiguration,
        name: String,
        default: Double
    ): Double {
        val value = configuration.parameters[name] ?: default

        require(value.isFinite()) {
            "$name must be finite"
        }

        return value
    }
}
