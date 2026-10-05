package com.algotrader.app.backtest

import com.algotrader.domain.Timeframe

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestSample
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.EquityPoint
import com.algotrader.backtest.PerformanceMetrics
import com.algotrader.backtest.PositionSizing
import com.algotrader.backtest.TradeDirection
import java.time.Instant
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesBacktestAccountingTest {

    private fun assertBigDecimalEquals(
        expected: BigDecimal,
        actual: BigDecimal?
    ) {
        assertTrue(
            "expected <$expected> but was <$actual>",
            actual != null && expected.compareTo(actual) == 0
        )
    }

    private val expiry = 1792713600L

    private fun contract(
        contractId: String? = "NSE:NIFTY26OCTFUT",
        lotSize: Int? = 65,
        lotSizeSource: String? = "FYERS_FUTURES_CHAIN"
    ) = FuturesContractConfig(
        underlying = "NIFTY",
        contractMonth = "2026-10",
        expiry = "2026-10-27",
        contractId = contractId,
        lotSize = lotSize,
        expiryEpochSeconds = expiry,
        lotSizeSource = lotSizeSource
    )

    private fun trade(
        direction: TradeDirection = TradeDirection.LONG,
        entry: Double = 100.0,
        exit: Double = 200.0,
        quantity: Double = 65.0,
        index: Int = 0
    ) = BacktestTrade(
        direction = direction,
        entryIndex = index,
        entryTimestamp = Instant.ofEpochSecond(1_000L + index),
        entryPrice = entry,
        exitIndex = index + 1,
        exitTimestamp = Instant.ofEpochSecond(1_060L + index),
        exitPrice = exit,
        quantity = quantity
    )

    private fun result(trades: List<BacktestTrade>) =
        BacktestResult(
            strategyName = "test",
            config = BacktestConfig(
                initialCapital = 100_000.0,
                positionSizing = PositionSizing.FixedQuantity(1.0),
                lotSize = 65
            ),
            finalEquity = 100_000.0 + trades.sumOf { it.grossPnl },
            trades = trades,
            equityCurve = emptyList<EquityPoint>(),
            metrics = PerformanceMetrics(
                totalTrades = trades.size,
                winningTrades = trades.count { it.grossPnl > 0.0 },
                losingTrades = trades.count { it.grossPnl <= 0.0 },
                winRate = 0.0,
                grossProfit = 0.0,
                grossLoss = 0.0,
                netProfit = trades.sumOf { it.grossPnl },
                totalReturnPercent = 0.0,
                maxDrawdown = 0.0,
                maxDrawdownPercent = 0.0,
                averageTradePnl = 0.0,
                profitFactor = null,
                averageWinningTrade = null,
                averageLosingTrade = null
            ),
            sample = BacktestSample.FULL
        )

    @Test
    fun oneLot65_computesGrossPnlAndEntryNotional() {
        val block = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade(entry = 100.0, exit = 200.0, quantity = 65.0))
        )

        assertBigDecimalEquals(
            BigDecimal("6500"),
            block.calculatorGrossPnl
        )
        assertBigDecimalEquals(
            BigDecimal("6500"),
            block.engineGrossPnl
        )
        assertBigDecimalEquals(
            BigDecimal("6500"),
            block.contractNotional
        )
        assertEquals(
            FuturesBacktestAccounting.GrossPnlComparison.MATCH,
            block.grossPnlComparison
        )
        assertEquals(
            FuturesBacktestAccounting.Status.COMPUTED,
            block.status
        )
    }

    @Test
    fun multipleTradesAndLots_areAggregatedExactly() {
        val trades = listOf(
            trade(entry = 100.0, exit = 120.0, quantity = 130.0),
            trade(
                direction = TradeDirection.SHORT,
                entry = 300.0,
                exit = 280.0,
                quantity = 65.0,
                index = 2
            )
        )

        val block = FuturesBacktestAccounting.compute(contract(), trades)

        assertBigDecimalEquals(BigDecimal("3900"), block.calculatorGrossPnl)
        assertBigDecimalEquals(BigDecimal("3900"), block.engineGrossPnl)
        assertBigDecimalEquals(BigDecimal("32500"), block.contractNotional)
        assertEquals(2, block.tradeCount)
        assertEquals(2, block.computedTradeCount)
    }

    @Test
    fun longAndShort_signsArePreserved() {
        val longBlock = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade(TradeDirection.LONG, 200.0, 100.0, 65.0))
        )
        val shortBlock = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade(TradeDirection.SHORT, 100.0, 200.0, 65.0))
        )

        assertBigDecimalEquals(
            BigDecimal("-6500"),
            longBlock.calculatorGrossPnl
        )
        assertBigDecimalEquals(
            BigDecimal("-6500"),
            shortBlock.calculatorGrossPnl
        )
    }

    @Test
    fun missingZeroAndNegativeLotSize_neverFallbackToOne() {
        val missing = FuturesBacktestAccounting.compute(
            contract(lotSize = null, lotSizeSource = null),
            listOf(trade())
        )
        val zero = FuturesBacktestAccounting.compute(
            contract(lotSize = 0),
            listOf(trade())
        )
        val negative = FuturesBacktestAccounting.compute(
            contract(lotSize = -65),
            listOf(trade())
        )

        assertEquals(FuturesBacktestAccounting.Status.NOT_COMPUTED, missing.status)
        assertNull(missing.lotSize)
        assertNull(missing.calculatorGrossPnl)

        assertEquals(FuturesBacktestAccounting.Status.INVALID_INPUT, zero.status)
        assertEquals(FuturesBacktestAccounting.Status.INVALID_INPUT, negative.status)
        assertNull(zero.calculatorGrossPnl)
        assertNull(negative.calculatorGrossPnl)
    }

    @Test
    fun nonWholeQuantity_isRejectedWithoutRounding() {
        val block = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade(quantity = 65.5))
        )

        assertEquals(FuturesBacktestAccounting.Status.INVALID_INPUT, block.status)
        assertNull(block.calculatorGrossPnl)
        assertNull(block.contractNotional)
    }

    @Test
    fun nonMultipleQuantity_isRejectedWithoutRounding() {
        val block = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade(quantity = 66.0))
        )

        assertEquals(FuturesBacktestAccounting.Status.INVALID_INPUT, block.status)
        assertNull(block.calculatorGrossPnl)
        assertNull(block.contractNotional)
    }

    @Test
    fun nonFiniteTradeValues_areRejected() {
        val block = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade(entry = Double.NaN))
        )

        assertEquals(FuturesBacktestAccounting.Status.INVALID_INPUT, block.status)
        assertNull(block.calculatorGrossPnl)
        assertNull(block.contractNotional)
    }

    @Test
    fun oneBadTrade_doesNotProducePartialTotal() {
        val trades = listOf(
            trade(entry = 100.0, exit = 200.0, quantity = 65.0),
            trade(entry = Double.NaN, exit = 200.0, quantity = 65.0)
        )

        val block = FuturesBacktestAccounting.compute(contract(), trades)

        assertEquals(FuturesBacktestAccounting.Status.INVALID_INPUT, block.status)
        assertEquals(1, block.computedTradeCount)
        assertEquals(2, block.tradeCount)
        assertNull(block.calculatorGrossPnl)
        assertNull(block.contractNotional)
    }

    @Test
    fun zeroTrades_isNotComputed_notZero() {
        val block = FuturesBacktestAccounting.compute(contract(), emptyList())

        assertEquals(FuturesBacktestAccounting.Status.NOT_COMPUTED, block.status)
        assertNull(block.calculatorGrossPnl)
        assertNull(block.contractNotional)
        assertEquals(0, block.tradeCount)
        assertEquals(0, block.computedTradeCount)
    }

    @Test
    fun blankContractId_isInvalid() {
        val block = FuturesBacktestAccounting.compute(
            contract(contractId = "   "),
            listOf(trade())
        )

        assertEquals(FuturesBacktestAccounting.Status.INVALID_INPUT, block.status)
        assertNull(block.calculatorGrossPnl)
    }

    @Test
    fun suppliedEvidence_preservesSourceAndExpiry() {
        val block = FuturesBacktestAccounting.compute(
            contract(
                lotSize = 65,
                lotSizeSource = "FYERS_FUTURES_CHAIN"
            ),
            listOf(trade())
        )

        assertEquals("FYERS_FUTURES_CHAIN", block.lotSizeSource)
        assertEquals(
            FuturesBacktestAccounting.LOT_SIZE_EVIDENCE,
            block.lotSizeEvidence
        )
        assertEquals(expiry, block.expiryEpochSeconds)
    }

    @Test
    fun unknownLotSize_hasNoEvidence() {
        val block = FuturesBacktestAccounting.compute(
            contract(lotSize = null, lotSizeSource = null),
            listOf(trade())
        )

        assertNull(block.lotSizeEvidence)
        assertNull(block.lotSizeSource)
        assertEquals(expiry, block.expiryEpochSeconds)
        assertEquals(FuturesBacktestAccounting.Status.NOT_COMPUTED, block.status)
    }

    @Test
    fun leverageChargesNetPnlAndBreakEven_areNotModelled() {
        val block = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade())
        )

        assertEquals(
            FuturesBacktestAccounting.NOT_MODELLED,
            block.leverage
        )
        assertEquals(
            FuturesBacktestAccounting.NOT_MODELLED,
            block.charges
        )
        assertEquals(
            FuturesBacktestAccounting.NOT_MODELLED,
            block.netPnl
        )
        assertEquals(
            FuturesBacktestAccounting.NOT_MODELLED,
            block.breakEven
        )
    }

    @Test
    fun resultFinalEquity_doesNotOverrideTradeDerivedEngineGross() {
        val trade = trade(entry = 100.0, exit = 200.0, quantity = 65.0)

        val block = FuturesBacktestAccounting.compute(
            contract(),
            listOf(trade)
        )

        // The adapter's engine gross P&L is derived from the persisted
        // BacktestTrade values. BacktestResult.finalEquity is not an
        // accounting input and must not override that value.
        val result = result(listOf(trade)).copy(
            finalEquity = 101_000.0
        )
        val compared = FuturesBacktestAccounting.compute(
            contract(),
            result.trades
        )

        // Direct compute compares against the trade-derived engine gross.
        assertBigDecimalEquals(BigDecimal("6500"), compared.engineGrossPnl)
        assertBigDecimalEquals(BigDecimal("6500"), compared.calculatorGrossPnl)
        assertBigDecimalEquals(BigDecimal.ZERO, compared.grossPnlDifference)
        assertEquals(
            FuturesBacktestAccounting.GrossPnlComparison.MATCH,
            compared.grossPnlComparison
        )

        // Preserve the API-level diagnostic block as computed; the adapter
        // never mutates the engine's BacktestResult.
        assertEquals(101_000.0, result.finalEquity, 0.0)
        assertBigDecimalEquals(BigDecimal("6500"), block.calculatorGrossPnl)
    }

    @Test
    fun indexJob_returnsNullAccounting() {
        val job = BacktestJobStore.Job(
            id = "index-job",
            status = BacktestJobStore.Status.COMPLETED,
            instrumentSymbol = "NSE:NIFTY50-INDEX",
            instrumentExchange = "NSE",
            instrumentCurrency = "INR",
            instrumentType = BacktestInstrumentType.INDEX,
            futuresContract = null,
            timeframe = com.algotrader.domain.Timeframe.MINUTE_5,
            strategies = emptyList(),
            initialCapital = 100_000.0,
            positionSizing = PositionSizing.FixedQuantity(1.0),
            candleCount = 10,
            progress = 100,
            currentStep = "Completed",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH
        )

        val accounting = FuturesBacktestAccounting.forJob(
            job,
            listOf(result(listOf(trade())))
        )

        assertEquals(listOf(null), accounting)
    }

    @Test
    fun futuresJobWithoutContract_isNotComputed() {
        val job = BacktestJobStore.Job(
            id = "futures-job",
            status = BacktestJobStore.Status.COMPLETED,
            instrumentSymbol = "NSE:NIFTY26OCTFUT",
            instrumentExchange = "NSE",
            instrumentCurrency = "INR",
            instrumentType = BacktestInstrumentType.FUTURES,
            futuresContract = null,
            timeframe = com.algotrader.domain.Timeframe.MINUTE_5,
            strategies = emptyList(),
            initialCapital = 100_000.0,
            positionSizing = PositionSizing.FixedQuantity(1.0),
            candleCount = 10,
            progress = 100,
            currentStep = "Completed",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH
        )

        val accounting = FuturesBacktestAccounting.forJob(
            job,
            listOf(result(listOf(trade())))
        )

        val restoredAccounting =
            requireNotNull(requireNotNull(accounting).single())
        assertEquals(
            FuturesBacktestAccounting.Status.NOT_COMPUTED,
            restoredAccounting.status
        )
        assertNull(restoredAccounting.calculatorGrossPnl)
    }
}
