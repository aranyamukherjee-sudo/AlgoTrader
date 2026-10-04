package com.algotrader.backtest

/**
 * Which slice of the historical candles a backtest ran on.
 *
 * FULL is a standard backtest over all loaded candles. IN_SAMPLE and
 * OUT_OF_SAMPLE are the chronological first and final segments of an
 * out-of-sample run (see [OutOfSampleSplit]). Each is a completely separate
 * backtest execution.
 */
enum class BacktestSample {
    FULL,
    IN_SAMPLE,
    OUT_OF_SAMPLE
}
