package com.algotrader.backtest

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerformanceMetricsTest {

    private fun trade(entry: Double, exit: Double): BacktestTrade = BacktestTrade(
        direction = TradeDirection.LONG,
        entryIndex = 0,
        entryTimestamp = Instant.EPOCH,
        entryPrice = entry,
        exitIndex = 1,
        exitTimestamp = Instant.EPOCH.plusSeconds(86_400),
        exitPrice = exit,
        quantity = 1.0
    )

    private fun equityPoint(index: Int, equity: Double) =
        EquityPoint(index, Instant.EPOCH.plusSeconds(index * 86_400L), equity)

    @Test
    fun `metrics with no trades are all zero and do not divide by zero`() {
        val equityCurve = listOf(equityPoint(0, 1000.0))
        val metrics = computePerformanceMetrics(1000.0, 1000.0, emptyList(), equityCurve)

        assertEquals(0, metrics.totalTrades)
        assertEquals(0.0, metrics.winRate)
        assertEquals(0.0, metrics.averageTradePnl)
        assertNull(metrics.profitFactor)
        assertNull(metrics.averageWinningTrade)
        assertNull(metrics.averageLosingTrade)
    }

    @Test
    fun `metrics are calculated correctly for a mix of winning and losing trades`() {
        // One winner (+100), one loser (-40).
        val trades = listOf(trade(100.0, 200.0), trade(100.0, 60.0))
        // Equity rises to a peak of 1100 after the win, then drawns down to
        // 1060 after the loss.
        val equityCurve = listOf(
            equityPoint(0, 1000.0),
            equityPoint(1, 1100.0),
            equityPoint(2, 1060.0)
        )

        val metrics = computePerformanceMetrics(
            initialCapital = 1000.0,
            finalEquity = 1060.0,
            trades = trades,
            equityCurve = equityCurve
        )

        assertEquals(2, metrics.totalTrades)
        assertEquals(1, metrics.winningTrades)
        assertEquals(1, metrics.losingTrades)
        assertEquals(0.5, metrics.winRate)
        assertEquals(100.0, metrics.grossProfit, 1e-9)
        assertEquals(-40.0, metrics.grossLoss, 1e-9)
        assertEquals(60.0, metrics.netProfit, 1e-9)
        assertEquals(6.0, metrics.totalReturnPercent, 1e-9)
        assertEquals(30.0, metrics.averageTradePnl, 1e-9)
        assertEquals(2.5, metrics.profitFactor)
        assertEquals(100.0, metrics.averageWinningTrade)
        assertEquals(-40.0, metrics.averageLosingTrade)
        assertEquals(40.0, metrics.maxDrawdown, 1e-9)
        assertEquals(40.0 / 1100.0 * 100.0, metrics.maxDrawdownPercent, 1e-9)
    }

    @Test
    fun `profit factor is null when there are no losing trades`() {
        val trades = listOf(trade(100.0, 120.0))
        val equityCurve = listOf(equityPoint(0, 1000.0), equityPoint(1, 1020.0))

        val metrics = computePerformanceMetrics(1000.0, 1020.0, trades, equityCurve)

        assertNull(metrics.profitFactor)
        assertEquals(20.0, metrics.grossProfit, 1e-9)
    }
}
