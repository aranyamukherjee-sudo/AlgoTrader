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

    // 10. Signals on the final bar cannot execute because there is no
    // following bar open.
    @Test
    fun `signal on final bar is not executed`() {
        val candles = testCandles(
            listOf(
                100.0 to 100.0,
                105.0 to 105.0,
                110.0 to 110.0
            )
        )
        val strategy = ScriptedStrategy(
            mapOf(2 to SignalType.BUY),
            metadata = longOnly()
        )
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, candles)

        assertEquals(0, result.trades.size)
        assertEquals(10_000.0, result.finalEquity, 1e-9)
    }

    // 11. Input candles are sorted before strategy evaluation/execution.
    @Test
    fun `unsorted candles are processed chronologically`() {
        val chronological = listOf(
            100.0 to 100.0,
            105.0 to 105.0,
            110.0 to 110.0,
            115.0 to 115.0
        )

        val sortedCandles = testCandles(chronological)
        val unsortedCandles = sortedCandles.reversed()

        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longOnly()
        )
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val sortedResult = engine.run(strategy, sortedCandles)
        val unsortedResult = engine.run(strategy, unsortedCandles)

        assertEquals(sortedResult.trades.size, unsortedResult.trades.size)
        assertEquals(
            sortedResult.finalEquity,
            unsortedResult.finalEquity,
            1e-9
        )
        assertEquals(
            sortedResult.trades.first().entryPrice,
            unsortedResult.trades.first().entryPrice,
            1e-9
        )
        assertEquals(
            sortedResult.trades.first().exitPrice,
            unsortedResult.trades.first().exitPrice,
            1e-9
        )
    }

    // 12. An empty candle set is a valid direct-engine no-op.
    @Test
    fun `empty candle input returns unchanged initial capital`() {
        val strategy = ScriptedStrategy(emptyMap(), metadata = longOnly())
        val engine = BacktestEngine(BacktestConfig(initialCapital = 10_000.0))

        val result = engine.run(strategy, emptyList())

        assertEquals(0, result.trades.size)
        assertEquals(0, result.equityCurve.size)
        assertEquals(10_000.0, result.finalEquity, 1e-9)
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

    // ---- Exit reasons ----

    @Test
    fun `an exit caused by an opposing signal records the strategy signal reason`() {
        val candles = testCandles(
            listOf(100.0 to 100.0, 105.0 to 105.0, 110.0 to 108.0, 112.0 to 112.0)
        )
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longOnly()
        )

        val trade = BacktestEngine(BacktestConfig(initialCapital = 10_000.0)).run(strategy, candles).trades.single()

        assertEquals(ExitReason.STRATEGY_SIGNAL, trade.exitReason)
    }

    @Test
    fun `a position force-closed at the end of data records the end-of-data reason`() {
        val candles = testCandles(listOf(100.0 to 100.0, 105.0 to 105.0, 110.0 to 110.0))
        val strategy = ScriptedStrategy(mapOf(0 to SignalType.BUY), metadata = longOnly())

        val trade = BacktestEngine(BacktestConfig(initialCapital = 10_000.0)).run(strategy, candles).trades.single()

        assertEquals(ExitReason.END_OF_DATA, trade.exitReason)
    }

    // ---- Lot-based (F&O) sizing ----

    private fun niftyCandles() = testCandles(
        listOf(22_000.0 to 22_000.0, 22_000.0 to 22_050.0, 22_080.0 to 22_090.0, 22_100.0 to 22_100.0)
    )

    @Test
    fun `one lot of a 65-unit contract trades 65 units and pnl uses that quantity`() {
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longOnly()
        )
        val engine = BacktestEngine(
            BacktestConfig(
                initialCapital = 100_000.0,
                positionSizing = PositionSizing.FixedLots(1),
                lotSize = 65
            )
        )

        val trade = engine.run(strategy, niftyCandles()).trades.single()

        assertEquals(65.0, trade.quantity)
        assertEquals(22_000.0, trade.entryPrice)
        assertEquals(22_100.0, trade.exitPrice)
        assertEquals(100.0 * 65.0, trade.grossPnl, 1e-9)
        assertEquals(22_000.0 * 65.0, trade.notionalExposure, 1e-9)
    }

    @Test
    fun `two lots trade 130 units`() {
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longOnly()
        )
        val engine = BacktestEngine(
            BacktestConfig(
                initialCapital = 100_000.0,
                positionSizing = PositionSizing.FixedLots(2),
                lotSize = 65
            )
        )

        assertEquals(130.0, engine.run(strategy, niftyCandles()).trades.single().quantity)
    }

    @Test
    fun `an entry smaller than one lot is skipped rather than opened fractionally`() {
        val strategy = ScriptedStrategy(
            mapOf(0 to SignalType.BUY, 2 to SignalType.SELL),
            metadata = longOnly()
        )
        val engine = BacktestEngine(
            BacktestConfig(
                initialCapital = 100_000.0,
                positionSizing = PositionSizing.PercentOfEquity(1.0),
                lotSize = 65
            )
        )

        val result = engine.run(strategy, niftyCandles())

        assertEquals(0, result.trades.size)
        assertEquals(100_000.0, result.finalEquity)
    }
}
