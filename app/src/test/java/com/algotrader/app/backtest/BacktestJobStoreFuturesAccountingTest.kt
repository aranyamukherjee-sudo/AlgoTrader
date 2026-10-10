package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestSample
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.EquityPoint
import com.algotrader.backtest.PerformanceMetrics
import com.algotrader.backtest.PositionSizing
import com.algotrader.strategyengine.StrategyConfiguration
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.backtest.TradeDirection
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestJobStoreFuturesAccountingTest {

    private fun tempDir(): File =
        createTempFile("altrixa-p3p10-", "").also {
            check(it.delete())
            check(it.mkdirs())
        }

    private val instrument = Instrument(
        symbol = "NSE:NIFTY26OCTFUT",
        exchange = "NSE",
        currency = "INR"
    )

    private fun futuresContract(
        lotSize: Int? = 65,
        lotSizeSource: String? = "FYERS_FUTURES_CHAIN"
    ) = FuturesContractConfig(
        underlying = "NIFTY",
        contractMonth = "2026-10",
        expiry = "2026-10-27",
        contractId = "NSE:NIFTY26OCTFUT",
        lotSize = lotSize,
        expiryEpochSeconds = 1792713600L,
        lotSizeSource = lotSizeSource
    )

    private fun job(
        store: BacktestJobStore,
        futuresContract: FuturesContractConfig? = futuresContract()
    ): BacktestJobStore.Job =
        store.create(
            instrument = instrument,
            timeframe = Timeframe.MINUTE_5,
            strategies = listOf(
                StrategyConfiguration("moving_average_crossover")
            ),
            initialCapital = 100_000.0,
            positionSizing = PositionSizing.FixedQuantity(1.0),
            candleCount = 10,
            instrumentType = BacktestInstrumentType.FUTURES,
            futuresContract = futuresContract
        )


    private fun result() =
        BacktestResult(
            strategyName = "test",
            config = BacktestConfig(
                initialCapital = 100_000.0,
                positionSizing = PositionSizing.FixedQuantity(1.0),
                lotSize = 65
            ),
            finalEquity = 106_500.0,
            trades = listOf(
                BacktestTrade(
                    direction = TradeDirection.LONG,
                    entryIndex = 0,
                    entryTimestamp = Instant.ofEpochSecond(1_000),
                    entryPrice = 100.0,
                    exitIndex = 1,
                    exitTimestamp = Instant.ofEpochSecond(1_060),
                    exitPrice = 200.0,
                    quantity = 65.0
                )
            ),
            equityCurve = emptyList<EquityPoint>(),
            metrics = PerformanceMetrics(
                totalTrades = 1,
                winningTrades = 1,
                losingTrades = 0,
                winRate = 1.0,
                grossProfit = 6500.0,
                grossLoss = 0.0,
                netProfit = 6500.0,
                totalReturnPercent = 6.5,
                maxDrawdown = 0.0,
                maxDrawdownPercent = 0.0,
                averageTradePnl = 6500.0,
                profitFactor = null,
                averageWinningTrade = 6500.0,
                averageLosingTrade = null
            ),
            sample = BacktestSample.FULL
        )

    private fun block(): FuturesBacktestAccounting.Block =
        FuturesBacktestAccounting.Block(
            contractId = "NSE:NIFTY26OCTFUT",
            lotSize = 65,
            lotSizeSource = "FYERS_FUTURES_CHAIN",
            lotSizeEvidence = FuturesBacktestAccounting.LOT_SIZE_EVIDENCE,
            expiryEpochSeconds = 1792713600L,
            contractNotional = BigDecimal("6500"),
            tradeCount = 1,
            computedTradeCount = 1,
            calculatorGrossPnl = BigDecimal("6500"),
            engineGrossPnl = BigDecimal("6500"),
            grossPnlDifference = BigDecimal.ZERO,
            grossPnlComparison =
                FuturesBacktestAccounting.GrossPnlComparison.MATCH,
            status = FuturesBacktestAccounting.Status.COMPUTED,
            reason = null
        )

    @Test
    fun roundTrip_preservesAllAccountingFields() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val job = job(store)

            store.saveResults(job.id, listOf(result()), listOf(block()))

            val restored = store.getFuturesAccounting(job.id)
            assertEquals(1, restored.size)

            val present =
                restored.single() as BacktestJobStore.RestoredFuturesAccounting.Present
            val actual = present.block

            assertEquals(block(), actual)
            assertEquals(BigDecimal("6500"), actual.contractNotional)
            assertEquals(BigDecimal("6500"), actual.calculatorGrossPnl)
            assertEquals(BigDecimal.ZERO, actual.grossPnlDifference)
            assertEquals("FYERS_FUTURES_CHAIN", actual.lotSizeSource)
            assertEquals(
                FuturesBacktestAccounting.LOT_SIZE_EVIDENCE,
                actual.lotSizeEvidence
            )
            assertEquals(
                FuturesBacktestAccounting.NOT_MODELLED,
                actual.leverage
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun accountingDisplayStrings_roundTripWithoutBeingForcedToNotModelled() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val displayValues = block().copy(
                leverage = "TEST_ONLY_LEVERAGE",
                charges = "TEST_ONLY_CHARGES",
                netPnl = "TEST_ONLY_NET_PNL",
                breakEven = "TEST_ONLY_BREAK_EVEN"
            )
            val createdJob = job(store)

            store.saveResults(
                createdJob.id,
                listOf(result()),
                listOf(displayValues)
            )

            val restored =
                store.getFuturesAccounting(createdJob.id).single()
                    as BacktestJobStore.RestoredFuturesAccounting.Present

            assertEquals(displayValues, restored.block)
            assertEquals("TEST_ONLY_LEVERAGE", restored.block.leverage)
            assertEquals("TEST_ONLY_CHARGES", restored.block.charges)
            assertEquals("TEST_ONLY_NET_PNL", restored.block.netPnl)
            assertEquals("TEST_ONLY_BREAK_EVEN", restored.block.breakEven)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nullableEvidence_roundTripsWhenLotSizeIsUnknown() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val unknown = block().copy(
                lotSize = null,
                lotSizeSource = null,
                lotSizeEvidence = null,
                expiryEpochSeconds = 1792713600L,
                contractNotional = null,
                status = FuturesBacktestAccounting.Status.NOT_COMPUTED,
                calculatorGrossPnl = null,
                grossPnlDifference = null,
                grossPnlComparison =
                    FuturesBacktestAccounting.GrossPnlComparison.NOT_COMPARABLE
            )

            val createdJob = job(store, futuresContract = null)
            store.saveResults(createdJob.id, listOf(result()), listOf(unknown))

            val restored = store.getFuturesAccounting(createdJob.id)
                .single() as BacktestJobStore.RestoredFuturesAccounting.Present

            assertEquals(unknown, restored.block)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun negativeDifference_isPreservedExactly() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val mismatch = block().copy(
                calculatorGrossPnl = BigDecimal("6400"),
                engineGrossPnl = BigDecimal("6500"),
                grossPnlDifference = BigDecimal("-100"),
                grossPnlComparison =
                    FuturesBacktestAccounting.GrossPnlComparison.MISMATCH
            )

            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()), listOf(mismatch))

            val restored =
                store.getFuturesAccounting(createdJob.id)
                    .single() as BacktestJobStore.RestoredFuturesAccounting.Present

            assertEquals(BigDecimal("-100"), restored.block.grossPnlDifference)
            assertEquals(
                FuturesBacktestAccounting.GrossPnlComparison.MISMATCH,
                restored.block.grossPnlComparison
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savingAccounting_doesNotChangeBacktestResult() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val original = result()

            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(original), listOf(block()))

            val restoredResult = store.getResults(createdJob.id).single()

            assertEquals(original, restoredResult)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nullAccounting_isPersistedAsAbsent() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)

            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()), listOf(null))

            assertEquals(
                BacktestJobStore.RestoredFuturesAccounting.Absent,
                store.getFuturesAccounting(createdJob.id).single()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyResultWithoutAccounting_restoresAsAbsent() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)

            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()))

            assertEquals(
                BacktestJobStore.RestoredFuturesAccounting.Absent,
                store.getFuturesAccounting(createdJob.id).single()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun misalignedAccountingList_isRejected() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)

            var rejected = false
            try {
                val createdJob = job(store)
                store.saveResults(
                    createdJob.id,
                    listOf(result()),
                    listOf(block(), block())
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }

            assertTrue(rejected)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun rewriteAccounting(
        dir: File,
        jobId: String,
        mutate: (JSONObject) -> Unit
    ) {
        val file = File(dir, "${jobId}_results.json")
        val root = JSONArray(file.readText())
        mutate(root.getJSONObject(0).getJSONObject("futuresAccounting"))
        file.writeText(root.toString())
    }

    @Test
    fun malformedDecimal_isReportedAsMalformed() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()), listOf(block()))

            rewriteAccounting(dir, createdJob.id) {
                it.put("calculatorGrossPnl", "not-a-decimal")
            }

            val restored = store.getFuturesAccounting(createdJob.id).single()

            assertTrue(
                restored is BacktestJobStore.RestoredFuturesAccounting.Malformed
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedEnum_isReportedAsMalformed() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()), listOf(block()))

            rewriteAccounting(dir, createdJob.id) {
                it.put("status", "NOT_A_REAL_STATUS")
            }

            val restored = store.getFuturesAccounting(createdJob.id).single()

            assertTrue(
                restored is BacktestJobStore.RestoredFuturesAccounting.Malformed
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inconsistentLotSizeAndEvidence_isMalformed() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()), listOf(block()))

            rewriteAccounting(dir, createdJob.id) {
                it.put("lotSize", JSONObject.NULL)
            }

            val restored = store.getFuturesAccounting(createdJob.id).single()

            assertTrue(
                restored is BacktestJobStore.RestoredFuturesAccounting.Malformed
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedSchemaVersion_isReportedAsMalformed() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()), listOf(block()))

            rewriteAccounting(dir, createdJob.id) {
                it.put("schemaVersion", 999)
            }

            val restored = store.getFuturesAccounting(createdJob.id).single()

            assertTrue(
                restored is BacktestJobStore.RestoredFuturesAccounting.Malformed
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedEvidenceConstant_isReportedAsMalformed() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val createdJob = job(store)
            store.saveResults(createdJob.id, listOf(result()), listOf(block()))

            rewriteAccounting(dir, createdJob.id) {
                it.put("lotSizeEvidence", "VERIFIED")
            }

            val restored = store.getFuturesAccounting(createdJob.id).single()

            assertTrue(
                restored is BacktestJobStore.RestoredFuturesAccounting.Malformed
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
