package com.algotrader.backtest

import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.StrategyMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktestEngineTest {

    private fun longOnly() = StrategyMetadata(
        description = "test", requiredIndicators = emptyList(), parameters = emptyList(),
        direction = PositionDirection.LONG_ONLY, entryRule = "test", exitRule = "test"
    )

    private fun shortOnly() = StrategyMetadata(
        description = "test", requiredIndicators = emptyList(), parameters = emptyList(),
        direction = PositionDirection.SHORT_ONLY, entryRule = "test", exitRule = "test"
    )

    private fun longAndShort() = StrategyMetadata(
        description = "test", requiredIndicators = emptyList(), parameters = emptyList(),
        direction = PositionDirection.LONG_AND_SHORT, entryRule = "test", exitRule = "test"
    )

    // 1. No signals -> no trades, unchanged equity.
    @Test
    fun `no signals produce no trades and equity stays at initial capital`() {
        val candles = testCandles((0..5).map { 100.0 + it to 100.0 + it })
        val strategy = ScriptedStrategy(emptyMap())
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(0, result.trades.size)
        assertEquals(10_000.0, result.finalEquity)
        assertTrue(result.equityCurve.all { it.equity == 10_000.0 })
    }

    // 2. Simple profitable long trade.
    @Test
    fun `a simple profitable long trade is opened at next open and closed on exit signal`() {
        // Signal at bar 0 (BUY) executes at bar 1's open (105). Signal at
        // bar 2 (SELL) executes at bar 3's open (112).
        val candles = testCandles(
            listOf(100.0 to 100.0, 105.0 to 105.0, 110.0 to 108.0, 112.0 to 112.0)
        )
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longOnly()
        )
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(1, result.trades.size)
        val trade = result.trades.first()
        assertEquals(TradeDirection.LONG, trade.direction)
        assertEquals(105.0, trade.entryPrice)
        assertEquals(112.0, trade.exitPrice)
        assertEquals(7.0, trade.grossPnl, 1e-9)
        assertTrue(trade.isWin)
        assertEquals(10_007.0, result.finalEquity, 1e-9)
    }

    // 3. Simple losing long trade.
    @Test
    fun `a simple losing long trade is recorded correctly`() {
        val candles = testCandles(
            listOf(100.0 to 100.0, 105.0 to 105.0, 110.0 to 95.0, 90.0 to 90.0)
        )
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longOnly()
        )
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(1, result.trades.size)
        val trade = result.trades.first()
        assertEquals(105.0, trade.entryPrice)
        assertEquals(90.0, trade.exitPrice)
        assertEquals(-15.0, trade.grossPnl, 1e-9)
        assertTrue(!trade.isWin)
        assertEquals(9_985.0, result.finalEquity, 1e-9)
    }

    // 4. Short trade.
    @Test
    fun `a short trade profits when price falls`() {
        val candles = testCandles(
            listOf(100.0 to 100.0, 95.0 to 95.0, 90.0 to 85.0, 80.0 to 80.0)
        )
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.SELL, 2 to SignalType.BUY),
            metadata = shortOnly()
        )
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(1, result.trades.size)
        val trade = result.trades.first()
        assertEquals(TradeDirection.SHORT, trade.direction)
        assertEquals(95.0, trade.entryPrice)
        assertEquals(80.0, trade.exitPrice)
        assertEquals(15.0, trade.grossPnl, 1e-9)
        assertEquals(10_015.0, result.finalEquity, 1e-9)
    }

    // 5. Repeated same-direction signals do not pyramid.
    @Test
    fun `repeated buy signals while already long do not create repeated entries`() {
        val candles = testCandles(
            listOf(
                100.0 to 100.0, 105.0 to 105.0, 108.0 to 108.0,
                112.0 to 112.0, 115.0 to 115.0, 120.0 to 120.0
            )
        )
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 1 to SignalType.BUY, 2 to SignalType.BUY, 4 to SignalType.SELL),
            metadata = longOnly()
        )
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        // Only one trade despite three consecutive BUY signals while long.
        assertEquals(1, result.trades.size)
        val trade = result.trades.first()
        assertEquals(105.0, trade.entryPrice) // from the FIRST execution only
        assertEquals(120.0, trade.exitPrice)
        assertEquals(15.0, trade.grossPnl, 1e-9)
    }

    // 6. Long-to-short reversal.
    @Test
    fun `a sell signal while long closes the long and opens a short in one execution`() {
        val candles = testCandles(
            listOf(100.0 to 100.0, 105.0 to 105.0, 110.0 to 110.0, 108.0 to 108.0, 100.0 to 100.0)
        )
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longAndShort()
        )
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(2, result.trades.size)

        val closedLong = result.trades[0]
        assertEquals(TradeDirection.LONG, closedLong.direction)
        assertEquals(105.0, closedLong.entryPrice)
        assertEquals(108.0, closedLong.exitPrice) // reversal executes at bar 3's open
        assertEquals(3.0, closedLong.grossPnl, 1e-9)

        val openedShort = result.trades[1]
        assertEquals(TradeDirection.SHORT, openedShort.direction)
        assertEquals(108.0, openedShort.entryPrice) // same bar/price as the close above
        assertEquals(100.0, openedShort.exitPrice) // force-closed at final bar's close
        assertEquals(8.0, openedShort.grossPnl, 1e-9)

        assertEquals(10_011.0, result.finalEquity, 1e-9)
    }

    // 7. Open position force-closed at the end of the dataset.
    @Test
    fun `an open position with no exit signal is closed at the final bar's close`() {
        val candles = testCandles(listOf(100.0 to 100.0, 105.0 to 105.0, 110.0 to 110.0))
        val strategy = ScriptedStrategy(mapOf(0 to SignalType.BUY), metadata = longOnly())
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(1, result.trades.size)
        val trade = result.trades.first()
        assertEquals(2, trade.exitIndex)
        assertEquals(110.0, trade.exitPrice)
        assertEquals(10_005.0, result.finalEquity, 1e-9)
        // The forced close doesn't change equity: it was already marked to
        // market at this same close price on the last iteration.
        assertEquals(result.finalEquity, result.equityCurve.last().equity, 1e-9)
    }

    // 9. Execution never uses future/same-bar candle data (look-ahead check).
    @Test
    fun `a signal generated at bar i executes at bar i plus 1's open, never bar i's own price`() {
        // Bar 2 has a dramatic spike close (999) that a look-ahead bug might
        // execute against. Bar 3's open (200) is distinct from bar 2's open
        // (50) and close (999), so we can prove which price was actually used.
        val candles = testCandles(
            listOf(50.0 to 50.0, 50.0 to 50.0, 50.0 to 999.0, 200.0 to 200.0)
        )
        val strategy = ScriptedStrategy(mapOf(2 to SignalType.BUY), metadata = longOnly())
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(1, result.trades.size)
        val trade = result.trades.first()
        assertEquals(3, trade.entryIndex)
        assertEquals(200.0, trade.entryPrice)
    }

    // Position sizing is honored on entry.
    @Test
    fun `percent-of-equity sizing computes quantity from flat equity at execution time`() {
        val candles = testCandles(listOf(100.0 to 100.0, 50.0 to 50.0, 50.0 to 50.0))
        val strategy = ScriptedStrategy(mapOf(0 to SignalType.BUY), metadata = longOnly())
        val engine = BacktestEngine(
            BacktestConfig(initialCapital = 10_000.0, positionSizing = PositionSizing.PercentOfEquity(10.0))
        )

        val result = engine.run(strategy, candles)

        // 10% of 10,000 = 1,000; at an execution price of 50, that's 20 units.
        assertEquals(1, result.trades.size)
        assertEquals(20.0, result.trades.first().quantity, 1e-9)
    }
}
