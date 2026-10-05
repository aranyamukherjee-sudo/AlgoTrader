package com.algotrader.backtest

import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.StrategyMetadata
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Lot sizes below are arbitrary TEST values (7, 10). They are deliberately not
 * any real contract's lot size: Patch 6 has no verified NIFTY futures lot size.
 */
class FuturesPnlTest {

    private fun computed(o: FuturesPnl.Outcome): FuturesPnl.Decomposition =
        assertIs<FuturesPnl.Outcome.Computed>(o).value

    @Test
    fun `long profit is points x quantity x lots`() {
        val d = computed(FuturesPnl.compute(TradeDirection.LONG, 100.0, 110.0, contractQuantity = 7, lots = 1))
        assertEquals(10.0, d.pricePoints)
        assertEquals(70.0, d.rupeePnl)
    }

    @Test
    fun `long loss is negative`() {
        val d = computed(FuturesPnl.compute(TradeDirection.LONG, 100.0, 96.0, 7, 1))
        assertEquals(-4.0, d.pricePoints)
        assertEquals(-28.0, d.rupeePnl)
    }

    @Test
    fun `short profit when price falls`() {
        val d = computed(FuturesPnl.compute(TradeDirection.SHORT, 100.0, 90.0, 7, 1))
        assertEquals(10.0, d.pricePoints)
        assertEquals(70.0, d.rupeePnl)
    }

    @Test
    fun `short loss when price rises`() {
        val d = computed(FuturesPnl.compute(TradeDirection.SHORT, 100.0, 103.0, 7, 1))
        assertEquals(-3.0, d.pricePoints)
        assertEquals(-21.0, d.rupeePnl)
    }

    @Test
    fun `zero price movement is exactly zero for both directions`() {
        for (dir in TradeDirection.values()) {
            val d = computed(FuturesPnl.compute(dir, 250.0, 250.0, 7, 3))
            assertEquals(0.0, d.pricePoints)
            assertEquals(0.0, d.rupeePnl)
        }
    }

    @Test
    fun `multiple lots scale linearly and total quantity is lots x quantity`() {
        val d = computed(FuturesPnl.compute(TradeDirection.LONG, 100.0, 105.0, contractQuantity = 10, lots = 3))
        assertEquals(5.0 * 10 * 3, d.rupeePnl)
        assertEquals(30L, d.totalQuantity)
        assertEquals(3, d.lots)
        assertEquals(10, d.contractQuantity)
    }

    @Test
    fun `unknown lot size is unavailable and never zero`() {
        val o = FuturesPnl.compute(TradeDirection.LONG, 100.0, 110.0, contractQuantity = null, lots = 1)
        val u = assertIs<FuturesPnl.Outcome.Unavailable>(o)
        assertTrue(u.reason.contains("unknown", ignoreCase = true))
    }

    @Test
    fun `invalid lot size lots and prices are unavailable`() {
        assertIs<FuturesPnl.Outcome.Unavailable>(FuturesPnl.compute(TradeDirection.LONG, 100.0, 110.0, 0, 1))
        assertIs<FuturesPnl.Outcome.Unavailable>(FuturesPnl.compute(TradeDirection.LONG, 100.0, 110.0, 7, 0))
        assertIs<FuturesPnl.Outcome.Unavailable>(FuturesPnl.compute(TradeDirection.LONG, 0.0, 110.0, 7, 1))
        assertIs<FuturesPnl.Outcome.Unavailable>(FuturesPnl.compute(TradeDirection.LONG, 100.0, Double.NaN, 7, 1))
    }

    private fun trade(dir: TradeDirection, entry: Double, exit: Double, qty: Double) = BacktestTrade(
        direction = dir,
        entryIndex = 0, entryTimestamp = Instant.EPOCH, entryPrice = entry,
        exitIndex = 1, exitTimestamp = Instant.EPOCH.plusSeconds(60), exitPrice = exit,
        quantity = qty
    )

    @Test
    fun `decomposing an engine trade matches its grossPnl`() {
        val t = trade(TradeDirection.SHORT, 200.0, 188.5, qty = 30.0) // 3 lots of 10
        val d = computed(FuturesPnl.compute(t, contractQuantity = 10))
        assertEquals(3, d.lots)
        assertEquals(t.grossPnl, d.rupeePnl, 1e-9)
    }

    @Test
    fun `trade not sized in whole lots of the given lot size is unavailable`() {
        val t = trade(TradeDirection.LONG, 100.0, 110.0, qty = 25.0)
        assertIs<FuturesPnl.Outcome.Unavailable>(FuturesPnl.compute(t, contractQuantity = 10))
        assertIs<FuturesPnl.Outcome.Unavailable>(FuturesPnl.compute(t, contractQuantity = null))
    }

    @Test
    fun `engine run with a supplied lot size produces rupee pnl equal to the decomposition`() {
        val meta = StrategyMetadata(
            description = "t", requiredIndicators = emptyList(), parameters = emptyList(),
            direction = PositionDirection.LONG_ONLY, entryRule = "t", exitRule = "t"
        )
        // BUY signal at bar 0 -> filled at bar 1 open (105); SELL at bar 2 -> filled at bar 3 open (112).
        val candles = testCandles(listOf(100.0 to 100.0, 105.0 to 105.0, 110.0 to 108.0, 112.0 to 112.0))
        val strategy = ScriptedStrategy(mapOf(0 to SignalType.BUY, 2 to SignalType.SELL), metadata = meta)
        val config = BacktestConfig(
            initialCapital = 100_000.0,
            positionSizing = PositionSizing.FixedLots(2),
            lotSize = 10 // test value, not a real contract size
        )

        val result = BacktestEngine(config).run(strategy, candles)

        val trade = result.trades.single()
        assertEquals(20.0, trade.quantity) // 2 lots x 10
        val d = computed(FuturesPnl.compute(trade, contractQuantity = config.lotSize))
        assertEquals(2, d.lots)
        assertEquals((112.0 - 105.0) * 10 * 2, d.rupeePnl, 1e-9)
        assertEquals(trade.grossPnl, d.rupeePnl, 1e-9)
        assertEquals(100_000.0 + d.rupeePnl, result.finalEquity, 1e-9)
    }

    // ---- Patch 7: the supplied (verified) lot size is the only quantity basis ----

    @Test
    fun `rupee pnl uses exactly the supplied lot size`() {
        val a = computed(FuturesPnl.compute(TradeDirection.LONG, 100.0, 104.0, contractQuantity = 7, lots = 2))
        val b = computed(FuturesPnl.compute(TradeDirection.LONG, 100.0, 104.0, contractQuantity = 11, lots = 2))
        assertEquals(4.0 * 7 * 2, a.rupeePnl)
        assertEquals(4.0 * 11 * 2, b.rupeePnl)
        // Neither a 1-unit fallback nor any other quantity is involved.
        assertTrue(a.rupeePnl != 4.0 * 1 * 2)
        assertEquals(14L, a.totalQuantity)
        assertEquals(22L, b.totalQuantity)
    }

    @Test
    fun `no fallback quantity exists when the lot size is unknown`() {
        for (dir in TradeDirection.values()) {
            assertIs<FuturesPnl.Outcome.Unavailable>(
                FuturesPnl.compute(dir, 100.0, 110.0, contractQuantity = null, lots = 5)
            )
        }
    }
}
