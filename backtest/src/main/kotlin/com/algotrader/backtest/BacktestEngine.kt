package com.algotrader.backtest

import com.algotrader.domain.Candle
import com.algotrader.domain.Portfolio
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.StrategyContext
import java.time.Instant

/**
 * Runs a single [Strategy] against a series of historical candles and
 * produces the resulting trades, equity curve, and performance metrics.
 *
 * ## Execution convention (look-ahead prevention)
 *
 * A signal is evaluated using data **through and including a bar's close**
 * (the strategy only ever sees `candles[0..i]`), but it is only ever
 * **executed at the following bar's open**. Concretely, for bar `i`:
 *
 * 1. Any target position decided from bar `i - 1`'s signal is executed now,
 *    at `candles[i].open`.
 * 2. Equity is marked to market using `candles[i].close`.
 * 3. The strategy is evaluated on `candles[0..i]` to decide the *target*
 *    position for the *next* bar — it is not executed yet.
 *
 * This means a signal generated from the very last bar in the dataset has
 * no following bar to execute at, and is simply never executed — there is
 * no look-ahead-free way to act on it within the given data.
 *
 * ## End-of-data convention
 *
 * Any position still open once every bar has been processed is force-closed
 * at the **final bar's close price**, so final equity is never ambiguous.
 *
 * ## Position transitions
 *
 * See [resolveTargetDirection] for exactly how a signal plus the current
 * position translate into a target position (no pyramiding on repeated
 * same-direction signals; a directional flip closes the existing position
 * and opens the opposite one in a single execution).
 */
class BacktestEngine(
    private val config: BacktestConfig = BacktestConfig()
) {

    fun run(strategy: Strategy, candles: List<Candle>): BacktestResult {
        val sorted = candles.sortedBy { it.timestamp }

        val trades = mutableListOf<BacktestTrade>()
        val equityCurve = mutableListOf<EquityPoint>()

        var openPosition: OpenPosition? = null
        var realizedPnl = 0.0
        var pendingTarget: TradeDirection? = null
        var hasPending = false

        for (i in sorted.indices) {
            val bar = sorted[i]

            // Step 1: execute the action queued from the previous bar's
            // signal, at *this* bar's open.
            if (hasPending) {
                val target = pendingTarget
                if (target != openPosition?.direction) {
                    openPosition?.let { position ->
                        val trade = position.close(exitIndex = i, exitTimestamp = bar.timestamp, exitPrice = bar.open)
                        trades += trade
                        realizedPnl += trade.grossPnl
                        openPosition = null
                    }
                    if (target != null) {
                        val flatEquity = config.initialCapital + realizedPnl
                        val quantity = config.positionSizing.quantityFor(flatEquity, bar.open)
                        openPosition = OpenPosition(
                            direction = target,
                            entryIndex = i,
                            entryTimestamp = bar.timestamp,
                            entryPrice = bar.open,
                            quantity = quantity
                        )
                    }
                }
                hasPending = false
                pendingTarget = null
            }

            // Step 2: mark-to-market equity using this bar's close, after
            // any execution that just happened at this bar's open.
            val unrealized = openPosition?.unrealizedPnl(bar.close) ?: 0.0
            equityCurve += EquityPoint(i, bar.timestamp, config.initialCapital + realizedPnl + unrealized)

            // Step 3: evaluate the strategy on data through this bar (its
            // close) and decide the target position for the *next* bar's
            // open. Not executed here.
            val window = sorted.subList(0, i + 1)
            val portfolio = Portfolio(cash = config.initialCapital + realizedPnl)
            val signalType = strategy.evaluate(StrategyContext(window, portfolio))
                .firstOrNull()
                ?.type
                ?: SignalType.HOLD

            pendingTarget = resolveTargetDirection(signalType, openPosition?.direction, strategy.metadata.direction)
            hasPending = true
        }

        // Any pending target decided from the *last* bar's signal has no
        // following bar to execute at and is intentionally dropped here.

        // End-of-data convention: force-close any still-open position at the
        // final bar's close price.
        openPosition?.let { position ->
            val lastBar = sorted.last()
            val trade = position.close(
                exitIndex = sorted.lastIndex,
                exitTimestamp = lastBar.timestamp,
                exitPrice = lastBar.close
            )
            trades += trade
            realizedPnl += trade.grossPnl
            openPosition = null
            // No extra equity point is appended here: the equity point already
            // recorded for the final bar (step 2 above) marked the still-open
            // position to market at this same close price, so it already
            // equals the post-close-out equity exactly.
        }

        val finalEquity = config.initialCapital + realizedPnl
        val metrics = computePerformanceMetrics(config.initialCapital, finalEquity, trades, equityCurve)

        return BacktestResult(
            strategyName = strategy.name,
            config = config,
            finalEquity = finalEquity,
            trades = trades,
            equityCurve = equityCurve,
            metrics = metrics
        )
    }
}

private class OpenPosition(
    val direction: TradeDirection,
    val entryIndex: Int,
    val entryTimestamp: Instant,
    val entryPrice: Double,
    val quantity: Double
) {
    fun unrealizedPnl(markPrice: Double): Double = when (direction) {
        TradeDirection.LONG -> (markPrice - entryPrice) * quantity
        TradeDirection.SHORT -> (entryPrice - markPrice) * quantity
    }

    fun close(exitIndex: Int, exitTimestamp: Instant, exitPrice: Double): BacktestTrade = BacktestTrade(
        direction = direction,
        entryIndex = entryIndex,
        entryTimestamp = entryTimestamp,
        entryPrice = entryPrice,
        exitIndex = exitIndex,
        exitTimestamp = exitTimestamp,
        exitPrice = exitPrice,
        quantity = quantity
    )
}
