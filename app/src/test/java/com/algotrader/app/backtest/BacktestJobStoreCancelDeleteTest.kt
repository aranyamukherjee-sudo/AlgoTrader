package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestModeLabel
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestSample
import com.algotrader.backtest.PerformanceMetrics
import com.algotrader.backtest.PositionSizing
import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.strategyengine.StrategyConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * JVM tests of the persisted cancel / delete rules. They use the real
 * BacktestJobStore on a temporary directory (a second instance over the same
 * directory simulates "reload").
 */
class BacktestJobStoreCancelDeleteTest {

    private lateinit var dir: File
    private lateinit var store: BacktestJobStore

    private val instrument = Instrument("NSE:NIFTY50-INDEX", "NSE")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("altrixa-store-test").toFile()
        store = BacktestJobStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun reload() = BacktestJobStore(dir)

    private fun candles(n: Int) = (0 until n).map {
        Candle(
            instrument, Timeframe.DAY_1, Instant.EPOCH.plusSeconds(it * 86_400L),
            100.0, 101.0, 99.0, 100.0, 10.0
        )
    }

    private fun result(sample: BacktestSample) = BacktestResult(
        strategyName = "Moving Average Crossover",
        config = BacktestConfig(),
        finalEquity = 100_000.0,
        trades = emptyList(),
        equityCurve = emptyList(),
        metrics = PerformanceMetrics(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, null),
        sample = sample
    )

    private fun newJob(runId: String?, sample: BacktestSample) = store.create(
        instrument = instrument,
        timeframe = Timeframe.DAY_1,
        strategies = listOf(StrategyConfiguration("moving_average_crossover")),
        initialCapital = 100_000.0,
        positionSizing = PositionSizing.FixedQuantity(1.0),
        candleCount = 60,
        runId = runId,
        sample = sample
    ).also { store.saveCandles(it.id, candles(60)) }

    /** Completed job with candles + results (checkpoint left behind on purpose). */
    private fun completedJob(runId: String?, sample: BacktestSample): BacktestJobStore.Job {
        val job = newJob(runId, sample)
        store.saveCheckpoint(job.id, 1L, listOf(result(sample)))
        store.saveResults(job.id, listOf(result(sample)))
        return store.update(job.id, BacktestJobStore.Status.COMPLETED, 100, "Complete")!!
    }

    private fun filesOf(id: String) = dir.listFiles()!!.filter { it.name.startsWith(id) }

    // ---------------- cancellation ----------------

    @Test
    fun standardLaunchCancelsEveryJobOfTheLaunch() {
        val a = newJob("RUN", BacktestSample.FULL)
        val b = newJob("RUN", BacktestSample.FULL)
        store.update(b.id, BacktestJobStore.Status.RUNNING, 40, "Processing")

        val cancelled = store.cancelRun(a.id)

        assertEquals(setOf(a.id, b.id), cancelled.toSet())
        assertEquals(BacktestJobStore.Status.CANCELLED, store.get(a.id)!!.status)
        assertEquals(BacktestJobStore.Status.CANCELLED, store.get(b.id)!!.status)
    }

    @Test
    fun oosLaunchCancelsBothInSampleAndOutOfSampleJobs() {
        val inSample = newJob("OOS-RUN", BacktestSample.IN_SAMPLE)
        val outSample = newJob("OOS-RUN", BacktestSample.OUT_OF_SAMPLE)

        // Cancelling from either segment cancels the whole shared runId.
        store.cancelRun(outSample.id)

        assertEquals(BacktestJobStore.Status.CANCELLED, store.get(inSample.id)!!.status)
        assertEquals(BacktestJobStore.Status.CANCELLED, store.get(outSample.id)!!.status)
    }

    @Test
    fun unrelatedRunsAreNotCancelled() {
        val mine = newJob("A", BacktestSample.FULL)
        val other = newJob("B", BacktestSample.FULL)
        val legacy = newJob(null, BacktestSample.FULL)

        store.cancelRun(mine.id)

        assertEquals(BacktestJobStore.Status.CANCELLED, store.get(mine.id)!!.status)
        assertEquals(BacktestJobStore.Status.QUEUED, store.get(other.id)!!.status)
        assertEquals(BacktestJobStore.Status.QUEUED, store.get(legacy.id)!!.status)
    }

    @Test
    fun cancelledJobsStayCancelledAfterReload() {
        val job = newJob("A", BacktestSample.FULL)
        store.cancelRun(job.id)

        assertEquals(BacktestJobStore.Status.CANCELLED, reload().get(job.id)!!.status)
    }

    @Test
    fun completedJobsCannotBeCancelled() {
        val done = completedJob("A", BacktestSample.FULL)
        val running = newJob("A", BacktestSample.FULL)

        val cancelled = store.cancelRun(done.id)

        assertEquals(listOf(running.id), cancelled)
        assertEquals(BacktestJobStore.Status.COMPLETED, store.get(done.id)!!.status)
        assertEquals(BacktestJobStore.Status.COMPLETED, store.cancel(done.id)!!.status)
        assertEquals(1, store.getResults(done.id).size)
    }

    @Test
    fun lateWorkerWritesCannotResurrectACancelledJob() {
        val job = newJob("A", BacktestSample.FULL)
        store.cancelRun(job.id)

        val afterProgress = store.update(job.id, BacktestJobStore.Status.RUNNING, 50, "Processing")
        val afterComplete = store.update(job.id, BacktestJobStore.Status.COMPLETED, 100, "Complete")
        store.saveCheckpoint(job.id, 1L, listOf(result(BacktestSample.FULL)))
        store.saveResults(job.id, listOf(result(BacktestSample.FULL)))

        assertEquals(BacktestJobStore.Status.CANCELLED, afterProgress!!.status)
        assertEquals(BacktestJobStore.Status.CANCELLED, afterComplete!!.status)
        assertEquals(BacktestJobStore.Status.CANCELLED, reload().get(job.id)!!.status)
        assertNull(store.getCheckpoint(job.id))
        assertTrue(store.getResults(job.id).isEmpty())
    }

    @Test
    fun cancelRemovesPartialCheckpoint() {
        val job = newJob("A", BacktestSample.FULL)
        store.saveCheckpoint(job.id, 1L, listOf(result(BacktestSample.FULL)))
        assertNotNull(store.getCheckpoint(job.id))

        store.cancelRun(job.id)

        assertNull(store.getCheckpoint(job.id))
    }

    // ---------------- deletion ----------------

    @Test
    fun standardDeletionRemovesEveryPersistedArtifact() {
        val job = completedJob("A", BacktestSample.FULL)
        assertTrue(filesOf(job.id).isNotEmpty())

        val outcome = store.deleteRun(job.id)

        assertEquals(BacktestJobStore.DeleteOutcome.Deleted(listOf(job.id)), outcome)
        assertNull(store.get(job.id))
        assertTrue(store.list().none { it.id == job.id })
        assertTrue(store.getResults(job.id).isEmpty())
        assertTrue(store.getCandles(job.id).isEmpty())
        assertNull(store.getCheckpoint(job.id))
        assertTrue("files left behind: ${filesOf(job.id)}", filesOf(job.id).isEmpty())
        assertFalse(File(dir, "backtest_jobs.json").readText().contains(job.id))
    }

    @Test
    fun oosDeletionRemovesEverySiblingOfTheRunId() {
        val inSample = completedJob("OOS-RUN", BacktestSample.IN_SAMPLE)
        val outSample = completedJob("OOS-RUN", BacktestSample.OUT_OF_SAMPLE)

        val outcome = store.deleteRun(inSample.id) as BacktestJobStore.DeleteOutcome.Deleted

        assertEquals(setOf(inSample.id, outSample.id), outcome.jobIds.toSet())
        assertTrue(store.list().isEmpty())
        assertTrue(filesOf(inSample.id).isEmpty())
        assertTrue(filesOf(outSample.id).isEmpty())
    }

    @Test
    fun oosDeletionDoesNotAffectAnotherRunId() {
        val a1 = completedJob("A", BacktestSample.IN_SAMPLE)
        val a2 = completedJob("A", BacktestSample.OUT_OF_SAMPLE)
        val b1 = completedJob("B", BacktestSample.IN_SAMPLE)
        val b2 = completedJob("B", BacktestSample.OUT_OF_SAMPLE)

        store.deleteRun(a2.id)

        assertEquals(setOf(b1.id, b2.id), store.list().map { it.id }.toSet())
        assertEquals(1, store.getResults(b1.id).size)
        assertEquals(60, store.getCandles(b2.id).size)
        assertTrue(filesOf(a1.id).isEmpty() && filesOf(a2.id).isEmpty())
    }

    @Test
    fun legacyJobWithoutRunIdIsDeletedIndividually() {
        val legacy1 = completedJob(null, BacktestSample.FULL)
        val legacy2 = completedJob(null, BacktestSample.FULL)

        val outcome = store.deleteRun(legacy1.id) as BacktestJobStore.DeleteOutcome.Deleted

        assertEquals(listOf(legacy1.id), outcome.jobIds)
        assertEquals(listOf(legacy2.id), store.list().map { it.id })
    }

    @Test
    fun deletedJobsDoNotReappearAfterReload() {
        val kept = completedJob("KEEP", BacktestSample.FULL)
        val gone = completedJob("GONE", BacktestSample.FULL)

        store.deleteRun(gone.id)

        val reloaded = reload()
        assertEquals(listOf(kept.id), reloaded.list().map { it.id })
        assertNull(reloaded.get(gone.id))
        assertTrue(reloaded.getResults(gone.id).isEmpty())
    }

    @Test
    fun activeRunIsNotDeletedFromUnderARunningWorker() {
        val done = completedJob("A", BacktestSample.IN_SAMPLE)
        val running = newJob("A", BacktestSample.OUT_OF_SAMPLE)
        store.update(running.id, BacktestJobStore.Status.RUNNING, 30, "Processing")

        assertEquals(BacktestJobStore.DeleteOutcome.StillActive, store.deleteRun(done.id))
        assertEquals(2, store.list().size)

        // After cancelling the run it can be deleted.
        store.cancelRun(done.id)
        assertTrue(store.deleteRun(done.id) is BacktestJobStore.DeleteOutcome.Deleted)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun deletingAnUnknownJobIsANoOp() {
        assertEquals(BacktestJobStore.DeleteOutcome.NotFound, store.deleteRun("missing"))
    }

    // ---------------- mode labels ----------------

    @Test
    fun jobModeLabelsAreStdAndOos() {
        val std = newJob("A", BacktestSample.FULL)
        val inSample = newJob("B", BacktestSample.IN_SAMPLE)
        val outSample = newJob("B", BacktestSample.OUT_OF_SAMPLE)

        assertEquals("STD", BacktestModeLabel.forSample(reload().get(std.id)!!.sample))
        assertEquals("OOS", BacktestModeLabel.forSample(reload().get(inSample.id)!!.sample))
        assertEquals("OOS", BacktestModeLabel.forSample(reload().get(outSample.id)!!.sample))
        assertEquals("In-Sample", BacktestModeLabel.segmentLabel(inSample.sample))
        assertEquals("Out-of-Sample", BacktestModeLabel.segmentLabel(outSample.sample))
    }

    @Test
    fun legacyJobWithoutSampleKeyIsStd() {
        val job = newJob(null, BacktestSample.FULL)
        // Simulate a pre-OOS record: strip the "sample" key from the persisted JSON.
        val rootFile = File(dir, "backtest_jobs.json")
        val root = org.json.JSONObject(rootFile.readText())
        root.getJSONObject(job.id).remove("sample")
        rootFile.writeText(root.toString())

        val loaded = reload().get(job.id)!!
        assertEquals(BacktestSample.FULL, loaded.sample)
        assertEquals("STD", BacktestModeLabel.forSample(loaded.sample))
    }
}
