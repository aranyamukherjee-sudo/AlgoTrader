package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestEngine
import com.algotrader.backtest.BacktestLaunchPlan
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestSample
import com.algotrader.backtest.PositionSizing
import com.algotrader.backtest.ResearchCostModel
import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.Signal
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.StrategyContext
import com.algotrader.strategy.StrategyMetadata
import com.algotrader.strategyengine.StrategyConfiguration
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3P11: persistence of the assumed research-cost model and cost metrics,
 * legacy restoration, checkpoint resume, gross-metric invariance, and
 * identical assumptions for in-sample / out-of-sample jobs.
 *
 * Results come from the real BacktestEngine; expected cost figures are
 * hand-computed constants, not recomputed with a second calculator.
 */
class BacktestJobStoreCostPersistenceTest {

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private fun tempDir(): File =
        createTempFile("altrixa-p3p11-", "").also {
            check(it.delete())
            check(it.mkdirs())
        }

    private val instrument = Instrument(symbol = "NSE:TEST", exchange = "NSE", currency = "INR")

    private val zero = ResearchCostModel()
    private val costed = ResearchCostModel(
        commissionRatePercent = 0.1,
        slippageBps = 5.0,
        fixedCostPerTrade = 20.0
    )

    /** BUY on bar [buyAt], SELL on bar [sellAt]; long only. */
    private class ScriptedStrategy(private val buyAt: Int, private val sellAt: Int) : Strategy {
        override val name: String = "Scripted"
        override val metadata = StrategyMetadata(
            description = "test",
            requiredIndicators = emptyList(),
            parameters = emptyList(),
            direction = PositionDirection.LONG_ONLY,
            entryRule = "test",
            exitRule = "test"
        )

        override fun evaluate(context: StrategyContext): List<Signal> {
            val bar = context.candles.last()
            val type = when (context.candles.lastIndex) {
                buyAt -> SignalType.BUY
                sellAt -> SignalType.SELL
                else -> SignalType.HOLD
            }
            return listOf(Signal(instrument = bar.instrument, type = type, timestamp = bar.timestamp))
        }
    }

    private fun candle(index: Int, open: Double, close: Double) = Candle(
        instrument = instrument,
        timeframe = Timeframe.DAY_1,
        timestamp = Instant.EPOCH.plusSeconds(index * 86_400L),
        open = open,
        high = maxOf(open, close),
        low = minOf(open, close),
        close = close,
        volume = 100.0
    )

    /**
     * Signal on bar 0 fills at bar 1's open (105); signal on bar 2 fills at
     * bar 3's open (112). Quantity 10 => gross P&L = (112 - 105) * 10 = 70.
     * Notional = 1050 + 1120 = 2170.
     */
    private val tradeCandles = listOf(
        candle(0, 100.0, 100.0),
        candle(1, 105.0, 105.0),
        candle(2, 110.0, 108.0),
        candle(3, 112.0, 112.0)
    )

    private fun engineConfig(model: ResearchCostModel) = BacktestConfig(
        initialCapital = 10_000.0,
        positionSizing = PositionSizing.FixedQuantity(10.0),
        researchCostModel = model
    )

    private fun run(model: ResearchCostModel): BacktestResult =
        BacktestEngine(engineConfig(model)).run(ScriptedStrategy(0, 2), tradeCandles)

    private fun newJob(
        store: BacktestJobStore,
        model: ResearchCostModel = zero,
        sample: BacktestSample = BacktestSample.FULL
    ): BacktestJobStore.Job =
        store.create(
            instrument = instrument,
            timeframe = Timeframe.DAY_1,
            strategies = listOf(StrategyConfiguration("moving_average_crossover")),
            initialCapital = 10_000.0,
            positionSizing = PositionSizing.FixedQuantity(10.0),
            candleCount = 4,
            sample = sample,
            researchCostModel = model
        )

    private fun editJob(dir: File, jobId: String, mutate: (JSONObject) -> Unit) {
        val file = File(dir, "backtest_jobs.json")
        val root = JSONObject(file.readText())
        mutate(root.getJSONObject(jobId))
        file.writeText(root.toString())
    }

    private fun editFirstResult(dir: File, jobId: String, mutate: (JSONObject) -> Unit) {
        val file = File(dir, "${jobId}_results.json")
        val array = JSONArray(file.readText())
        mutate(array.getJSONObject(0))
        file.writeText(array.toString())
    }

    private fun editFirstCheckpointResult(dir: File, jobId: String, mutate: (JSONObject) -> Unit) {
        val file = File(dir, "${jobId}_checkpoint.json")
        val root = JSONObject(file.readText())
        mutate(root.getJSONArray("results").getJSONObject(0))
        file.writeText(root.toString())
    }

    private fun stripCostFields(result: JSONObject) {
        result.getJSONObject("config").remove("researchCostModel")
        result.getJSONObject("metrics").apply {
            remove("researchCosts")
            remove("costAdjustedNetProfit")
            remove("costAdjustedReturnPercent")
        }
    }

    // ---------------------------------------------------------------
    // The engine figures the persistence tests rely on
    // ---------------------------------------------------------------

    @Test
    fun fixtureProducesTheExpectedGrossAndAssumedCostFigures() {
        val gross = run(zero)
        assertEquals(1, gross.trades.size)
        assertEquals(70.0, gross.trades[0].grossPnl, 1e-9)
        assertEquals(10_070.0, gross.finalEquity, 1e-9)
        assertEquals(0.0, gross.metrics.researchCosts, 0.0)
        assertEquals(70.0, gross.metrics.costAdjustedNetProfit, 1e-9)

        // commission 2170 * 0.1% = 2.17; slippage 2170 * 5bp = 1.085; fixed 20.
        val costedResult = run(costed)
        assertEquals(23.255, costedResult.metrics.researchCosts, 1e-9)
        assertEquals(46.745, costedResult.metrics.costAdjustedNetProfit, 1e-9)
        assertEquals(0.46745, costedResult.metrics.costAdjustedReturnPercent, 1e-9)
    }

    // ---------------------------------------------------------------
    // Gross invariance
    // ---------------------------------------------------------------

    @Test
    fun grossTradesEquityDrawdownAndPnlAreInvariantToTheCostModel() {
        val gross = run(zero)
        val costedResult = run(costed)

        // Everything except the config's cost model and the three cost fields
        // must be identical: trades, equity curve, final equity, drawdown,
        // netProfit, grossProfit/Loss, returns, win rate, ...
        val normalised = costedResult.copy(
            config = gross.config,
            metrics = costedResult.metrics.copy(
                researchCosts = gross.metrics.researchCosts,
                costAdjustedNetProfit = gross.metrics.costAdjustedNetProfit,
                costAdjustedReturnPercent = gross.metrics.costAdjustedReturnPercent
            )
        )
        assertEquals(gross, normalised)

        // Spelled out for the key figures.
        assertEquals(gross.trades, costedResult.trades)
        assertEquals(gross.equityCurve, costedResult.equityCurve)
        assertEquals(gross.finalEquity, costedResult.finalEquity, 0.0)
        assertEquals(gross.metrics.netProfit, costedResult.metrics.netProfit, 0.0)
        assertEquals(gross.metrics.maxDrawdown, costedResult.metrics.maxDrawdown, 0.0)
        assertEquals(gross.metrics.maxDrawdownPercent, costedResult.metrics.maxDrawdownPercent, 0.0)
        assertEquals(gross.metrics.totalReturnPercent, costedResult.metrics.totalReturnPercent, 0.0)
    }

    @Test
    fun grossFieldsSurviveSaveAndRestoreUnchangedWhenCostsAreAssumed() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            val job = newJob(store, costed)
            val original = run(costed)
            store.saveResults(job.id, listOf(original))

            val restored = BacktestJobStore(dir).getResults(job.id).single()

            assertEquals(original.finalEquity, restored.finalEquity, 0.0)
            assertEquals(original.trades.map { it.grossPnl }, restored.trades.map { it.grossPnl })
            assertEquals(original.metrics.netProfit, restored.metrics.netProfit, 0.0)
            assertEquals(original.metrics.grossProfit, restored.metrics.grossProfit, 0.0)
            assertEquals(original.metrics.maxDrawdown, restored.metrics.maxDrawdown, 0.0)
            assertEquals(original.metrics.totalReturnPercent, restored.metrics.totalReturnPercent, 0.0)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // Persistence of the complete model
    // ---------------------------------------------------------------

    @Test
    fun jobPersistsTheCompleteCostModelAcrossStoreInstances() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), costed)
            assertEquals(costed, job.researchCostModel)

            val restored = BacktestJobStore(dir).get(job.id)
            assertNotNull(restored)
            assertEquals(costed, restored!!.researchCostModel)
            assertEquals(
                costed,
                BacktestJobStore(dir).list().single { it.id == job.id }.researchCostModel
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resultPersistsConfigModelAndCostMetricsExactly() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), costed)
            val original = run(costed)
            BacktestJobStore(dir).saveResults(job.id, listOf(original))

            val restored = BacktestJobStore(dir).getResults(job.id).single()

            assertEquals(costed, restored.config.researchCostModel)
            assertEquals(original.metrics, restored.metrics)
            assertEquals(23.255, restored.metrics.researchCosts, 1e-9)
            assertEquals(46.745, restored.metrics.costAdjustedNetProfit, 1e-9)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zeroCostJobAndResultRoundTripAsZero() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), zero)
            val original = run(zero)
            BacktestJobStore(dir).saveResults(job.id, listOf(original))

            assertEquals(zero, BacktestJobStore(dir).get(job.id)!!.researchCostModel)
            val restored = BacktestJobStore(dir).getResults(job.id).single()
            assertEquals(zero, restored.config.researchCostModel)
            assertEquals(original.metrics, restored.metrics)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // Legacy restoration
    // ---------------------------------------------------------------

    @Test
    fun legacyJobWithoutCostModelRestoresAsZeroCost() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), costed)
            editJob(dir, job.id) { it.remove("researchCostModel") }

            val restored = BacktestJobStore(dir).get(job.id)
            assertNotNull(restored)
            assertEquals(zero, restored!!.researchCostModel)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyResultRestoresZeroCostWithZeroCostIdentities() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), zero)
            val original = run(zero)
            BacktestJobStore(dir).saveResults(job.id, listOf(original))
            editFirstResult(dir, job.id) { stripCostFields(it) }

            val restored = BacktestJobStore(dir).getResults(job.id).single()

            assertEquals(zero, restored.config.researchCostModel)
            assertEquals(0.0, restored.metrics.researchCosts, 0.0)
            // Zero-cost identities: cost-adjusted equals gross, NOT the
            // PerformanceMetrics default of 0.0 for the return percentage.
            assertEquals(70.0, restored.metrics.netProfit, 1e-9)
            assertEquals(restored.metrics.netProfit, restored.metrics.costAdjustedNetProfit, 0.0)
            assertEquals(0.7, restored.metrics.totalReturnPercent, 1e-9)
            assertEquals(
                restored.metrics.totalReturnPercent,
                restored.metrics.costAdjustedReturnPercent,
                0.0
            )
            // And the UI shows no cost card for it.
            assertNull(BacktestCostPresentation.card(restored, isFutures = false))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // Corrupt data is rejected, never silently zeroed
    // ---------------------------------------------------------------

    @Test
    fun invalidPersistedJobCostModelIsMalformedNotZeroed() {
        val variants: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("researchCostModel").put("commissionRatePercent", -0.1) },
            { it.getJSONObject("researchCostModel").put("commissionRatePercent", 50.0) },
            { it.getJSONObject("researchCostModel").put("slippageBps", 501.0) },
            { it.getJSONObject("researchCostModel").put("fixedCostPerTrade", 100_000.5) },
            { it.getJSONObject("researchCostModel").remove("slippageBps") },
            { it.getJSONObject("researchCostModel").put("fixedCostPerTrade", "abc") },
            { it.put("researchCostModel", "oops") },
            { it.put("researchCostModel", JSONObject.NULL) }
        )

        variants.forEachIndexed { index, mutate ->
            val dir = tempDir()
            try {
                val job = newJob(BacktestJobStore(dir), costed)
                editJob(dir, job.id, mutate)

                // Same treatment as any other malformed job record.
                assertNull("variant $index", BacktestJobStore(dir).get(job.id))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun resultWithModelButNoCostMetricsIsMalformed() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), costed)
            BacktestJobStore(dir).saveResults(job.id, listOf(run(costed)))
            editFirstResult(dir, job.id) {
                it.getJSONObject("metrics").remove("costAdjustedNetProfit")
            }

            assertThrows(IllegalStateException::class.java) {
                BacktestJobStore(dir).getResults(job.id)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resultWithInvalidModelIsMalformed() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), costed)
            BacktestJobStore(dir).saveResults(job.id, listOf(run(costed)))
            editFirstResult(dir, job.id) {
                it.getJSONObject("config").getJSONObject("researchCostModel")
                    .put("commissionRatePercent", -1.0)
            }

            assertThrows(IllegalStateException::class.java) {
                BacktestJobStore(dir).getResults(job.id)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // Input validation at job creation
    // ---------------------------------------------------------------

    @Test
    fun createRejectsInvalidModelsAndWritesNoJob() {
        val dir = tempDir()
        try {
            val store = BacktestJobStore(dir)
            listOf(
                ResearchCostModel(commissionRatePercent = 5.01),
                ResearchCostModel(slippageBps = 500.01),
                ResearchCostModel(fixedCostPerTrade = 100_000.01),
                ResearchCostModel(commissionRatePercent = Double.POSITIVE_INFINITY),
                ResearchCostModel(fixedCostPerTrade = Double.POSITIVE_INFINITY)
            ).forEach { model ->
                assertThrows(IllegalArgumentException::class.java) { newJob(store, model) }
            }
            assertTrue(store.list().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // Checkpoint resume
    // ---------------------------------------------------------------

    @Test
    fun checkpointResumeRestoresIdenticalAssumptionsAndResults() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), costed)
            val completed = run(costed)
            BacktestJobStore(dir).saveCheckpoint(job.id, startedAtMillis = 1_234L, results = listOf(completed))

            // A fresh store instance, as a restarted Worker would use.
            val resumedStore = BacktestJobStore(dir)
            val resumedJob = resumedStore.get(job.id)!!
            val checkpoint = resumedStore.getCheckpoint(job.id)!!

            assertEquals(costed, resumedJob.researchCostModel)
            assertEquals(1, checkpoint.results.size)
            assertEquals(costed, checkpoint.results.single().config.researchCostModel)
            assertEquals(completed.metrics, checkpoint.results.single().metrics)
            assertTrue(
                BacktestCostAssumptions.checkpointConsistent(
                    resumedJob.researchCostModel,
                    checkpoint.results
                )
            )

            // The config the Worker builds on resume carries the same model, so
            // the remaining strategies are costed identically to the finished one.
            val resumedRun = BacktestEngine(engineConfig(resumedJob.researchCostModel))
                .run(ScriptedStrategy(0, 2), tradeCandles)
            assertEquals(checkpoint.results.single().metrics, resumedRun.metrics)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun checkpointFromDifferentAssumptionsIsDetectedAsInconsistent() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), costed)
            // Simulate a checkpoint written under zero cost for a costed job.
            BacktestJobStore(dir).saveCheckpoint(job.id, 1L, listOf(run(zero)))

            val checkpoint = BacktestJobStore(dir).getCheckpoint(job.id)!!
            assertTrue(
                !BacktestCostAssumptions.checkpointConsistent(
                    BacktestJobStore(dir).get(job.id)!!.researchCostModel,
                    checkpoint.results
                )
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyCheckpointWithoutCostFieldsResumesUnderLegacyZeroCostJob() {
        val dir = tempDir()
        try {
            val job = newJob(BacktestJobStore(dir), zero)
            BacktestJobStore(dir).saveCheckpoint(job.id, 1L, listOf(run(zero)))
            editJob(dir, job.id) { it.remove("researchCostModel") }
            editFirstCheckpointResult(dir, job.id) { stripCostFields(it) }

            val store = BacktestJobStore(dir)
            val legacyJob = store.get(job.id)!!
            val checkpoint = store.getCheckpoint(job.id)!!

            assertEquals(zero, legacyJob.researchCostModel)
            assertEquals(1, checkpoint.results.size)
            assertEquals(zero, checkpoint.results.single().config.researchCostModel)
            assertTrue(
                BacktestCostAssumptions.checkpointConsistent(
                    legacyJob.researchCostModel,
                    checkpoint.results
                )
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------
    // In-sample / out-of-sample use identical assumptions
    // ---------------------------------------------------------------

    @Test
    fun inSampleAndOutOfSampleJobsCarryAndApplyIdenticalAssumptions() {
        val dir = tempDir()
        try {
            val candles = (0 until 200).map { i ->
                val price = 100.0 + i
                candle(i, open = price, close = price)
            }
            val units = (BacktestLaunchPlan.plan(
                configurations = listOf(StrategyConfiguration("moving_average_crossover")),
                candles = candles,
                outOfSample = true
            ) as BacktestLaunchPlan.Outcome.Planned).units
            assertEquals(
                listOf(BacktestSample.IN_SAMPLE, BacktestSample.OUT_OF_SAMPLE),
                units.map { it.sample }
            )

            // One model for the whole launch, as the launch code does.
            val store = BacktestJobStore(dir)
            val jobs = units.map { unit ->
                store.create(
                    instrument = instrument,
                    timeframe = Timeframe.DAY_1,
                    strategies = listOf(unit.configuration),
                    initialCapital = 10_000.0,
                    positionSizing = PositionSizing.FixedQuantity(10.0),
                    candleCount = unit.candles.size,
                    sample = unit.sample,
                    researchCostModel = costed
                )
            }

            val restoredJobs = jobs.map { BacktestJobStore(dir).get(it.id)!! }
            assertEquals(listOf(costed, costed), restoredJobs.map { it.researchCostModel })

            // Each segment, run with its own job's persisted model.
            val results = restoredJobs.zip(units).map { (job, unit) ->
                BacktestEngine(engineConfig(job.researchCostModel))
                    .run(ScriptedStrategy(10, 30), unit.candles)
            }
            results.forEach { result ->
                assertEquals(costed, result.config.researchCostModel)
                assertEquals(1, result.trades.size)
                assertTrue(result.metrics.researchCosts > 0.0)
            }

            // Gross P&L for each segment is the same as with no assumed costs.
            units.forEachIndexed { index, unit ->
                val gross = BacktestEngine(engineConfig(zero))
                    .run(ScriptedStrategy(10, 30), unit.candles)
                assertEquals(gross.trades, results[index].trades)
                assertEquals(gross.metrics.netProfit, results[index].metrics.netProfit, 0.0)
                assertEquals(gross.metrics.maxDrawdown, results[index].metrics.maxDrawdown, 0.0)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
