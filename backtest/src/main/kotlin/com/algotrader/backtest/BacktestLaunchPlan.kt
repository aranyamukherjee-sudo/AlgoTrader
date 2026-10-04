package com.algotrader.backtest

import com.algotrader.domain.Candle
import com.algotrader.strategyengine.StrategyConfiguration

/** One independent backtest job: one strategy on one candle set. */
data class BacktestWorkUnit(
    val configuration: StrategyConfiguration,
    val sample: BacktestSample,
    val candles: List<Candle>
)

/**
 * Decides which jobs one backtest launch creates and which candles each job
 * receives. Pure, so the job layout and the no-leakage guarantee are unit-tested.
 *
 * - Standard: one FULL unit per strategy, on all candles.
 * - Out-of-sample: per strategy, one IN_SAMPLE unit that receives only the first
 *   70% of the candles and one OUT_OF_SAMPLE unit that receives only the final
 *   30%. A rejected split plans nothing, so no job is created.
 */
object BacktestLaunchPlan {

    sealed interface Outcome {
        data class Planned(val units: List<BacktestWorkUnit>) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    fun plan(
        configurations: List<StrategyConfiguration>,
        candles: List<Candle>,
        outOfSample: Boolean
    ): Outcome {
        if (!outOfSample) {
            return Outcome.Planned(
                configurations.map { BacktestWorkUnit(it, BacktestSample.FULL, candles) }
            )
        }

        return when (val split = OutOfSampleSplit.split(candles)) {
            is OutOfSampleSplit.Outcome.Rejected -> Outcome.Rejected(split.reason)
            is OutOfSampleSplit.Outcome.Split -> Outcome.Planned(
                configurations.flatMap { configuration ->
                    listOf(
                        BacktestWorkUnit(configuration, BacktestSample.IN_SAMPLE, split.inSample),
                        BacktestWorkUnit(configuration, BacktestSample.OUT_OF_SAMPLE, split.outOfSample)
                    )
                }
            )
        }
    }
}
