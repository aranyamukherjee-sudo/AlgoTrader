package com.algotrader.backtest

import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.StrategyMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class ResearchCostModelTest {

    @Test
    fun `zero cost preserves gross pnl`() {
        val trade = BacktestTrade(
            direction = TradeDirection.LONG,
            entryIndex = 1,
            entryTimestamp = java.time.Instant.ofEpochSecond(1),
            entryPrice = 100.0,
            exitIndex = 2,
            exitTimestamp = java.time.Instant.ofEpochSecond(2),
            exitPrice = 110.0,
            quantity = 10.0
        )

        val model = ResearchCostModel()

        assertEquals(100.0, trade.grossPnl, 1e-9)
        assertEquals(0.0, trade.researchCosts(model), 1e-9)
        assertEquals(100.0, trade.netPnl(model), 1e-9)
    }

    @Test
    fun `percentage commission is charged on entry and exit notional`() {
        val trade = BacktestTrade(
            direction = TradeDirection.LONG,
            entryIndex = 1,
            entryTimestamp = java.time.Instant.ofEpochSecond(1),
            entryPrice = 100.0,
            exitIndex = 2,
            exitTimestamp = java.time.Instant.ofEpochSecond(2),
            exitPrice = 110.0,
            quantity = 10.0
        )

        val model = ResearchCostModel(
            commissionRatePercent = 1.0
        )

        // Entry 1000 + exit 1100 = 2100; 1% = 21.
        assertEquals(21.0, trade.researchCosts(model), 1e-9)
        assertEquals(79.0, trade.netPnl(model), 1e-9)
    }

    @Test
    fun `slippage and fixed cost are deterministic`() {
        val trade = BacktestTrade(
            direction = TradeDirection.SHORT,
            entryIndex = 1,
            entryTimestamp = java.time.Instant.ofEpochSecond(1),
            entryPrice = 100.0,
            exitIndex = 2,
            exitTimestamp = java.time.Instant.ofEpochSecond(2),
            exitPrice = 90.0,
            quantity = 10.0
        )

        val model = ResearchCostModel(
            slippageBps = 10.0,
            fixedCostPerTrade = 5.0
        )

        // Combined notional = 1900; 10 bps = 1.9; + fixed 5.
        assertEquals(6.9, trade.researchCosts(model), 1e-9)
        assertEquals(93.1, trade.netPnl(model), 1e-9)
    }
}
