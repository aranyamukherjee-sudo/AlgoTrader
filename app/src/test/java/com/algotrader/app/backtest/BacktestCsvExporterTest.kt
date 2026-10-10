package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.EquityPoint
import com.algotrader.backtest.ResearchCostModel
import com.algotrader.backtest.TradeDirection
import com.algotrader.backtest.computePerformanceMetrics
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestCsvExporterTest {

    private fun result(): BacktestResult {
        val trades = listOf(
            BacktestTrade(
                direction = TradeDirection.LONG,
                entryIndex = 1,
                entryTimestamp = Instant.parse("2026-01-01T03:45:00Z"),
                entryPrice = 100.0,
                exitIndex = 2,
                exitTimestamp = Instant.parse("2026-01-01T04:00:00Z"),
                exitPrice = 110.0,
                quantity = 2.0,
                exitDetail = "Signal, \"confirmed\""
            ),
            BacktestTrade(
                direction = TradeDirection.SHORT,
                entryIndex = 3,
                entryTimestamp = Instant.parse("2026-01-01T04:15:00Z"),
                entryPrice = 120.0,
                exitIndex = 4,
                exitTimestamp = Instant.parse("2026-01-01T04:30:00Z"),
                exitPrice = 125.0,
                quantity = 2.0
            )
        )
        val costs = ResearchCostModel(
            commissionRatePercent = 0.03,
            slippageBps = 5.0,
            fixedCostPerTrade = 20.0
        )
        val initial = 100_000.0
        val final = initial + trades.sumOf { it.grossPnl }
        val curve = listOf(
            EquityPoint(0, trades.first().entryTimestamp, initial),
            EquityPoint(4, trades.last().exitTimestamp, final)
        )
        val metrics = computePerformanceMetrics(
            initialCapital = initial,
            finalEquity = final,
            trades = trades,
            equityCurve = curve,
            researchCostModel = costs
        )
        return BacktestResult(
            strategyName = "MACD, test",
            config = BacktestConfig(
                initialCapital = initial,
                researchCostModel = costs
            ),
            finalEquity = final,
            trades = trades,
            equityCurve = curve,
            metrics = metrics
        )
    }

    @Test
    fun `trade export includes every trade and escapes CSV fields`() {
        val csv = BacktestCsvExporter.tradesCsv(
            "NSE:NIFTY260CTFUT", "5m", listOf(result()), true
        )
        assertEquals(3, csv.trim().lines().size)
        assertTrue(csv.contains("\"NSE:NIFTY260CTFUT\""))
        assertTrue(csv.contains("\"LONG\""))
        assertTrue(csv.contains("\"SHORT\""))
        assertTrue(csv.contains("\"Signal, \"\"confirmed\"\"\""))
        assertTrue(csv.contains("\"indicative_fno_turnover\""))
        assertTrue(csv.contains("\"20.0000000000\""))
    }

    @Test
    fun `comparison reconciles trade PnL and research costs`() {
        val csv = BacktestCsvExporter.comparisonCsv(
            "NSE:NIFTY260CTFUT", "5m", listOf(result()), true
        )
        assertEquals(2, csv.trim().lines().size)
        assertTrue(csv.contains("\"pnl_reconciliation\""))
        assertTrue(csv.contains("\"MATCH\""))
        assertTrue(csv.contains("\"research_cost_reconciliation\""))
        assertTrue(csv.contains("\"cost_adjusted_pnl_reconciliation\""))
        assertTrue(csv.contains("Indicative only"))
        assertTrue(csv.contains("not actual broker or statutory charges"))
    }

    @Test
    fun `empty results still produce valid header-only exports`() {
        assertEquals(
            1,
            BacktestCsvExporter.tradesCsv("NSE:X", "5m", emptyList(), false)
                .trim().lines().size
        )
        assertEquals(
            1,
            BacktestCsvExporter.comparisonCsv("NSE:X", "5m", emptyList(), false)
                .trim().lines().size
        )
    }
}
