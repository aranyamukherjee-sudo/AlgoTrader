package com.algotrader.app.backtest

import java.io.File
import com.algotrader.backtest.BacktestConfig

import android.content.Context
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestRunScope
import com.algotrader.backtest.BacktestSample
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.EquityPoint
import com.algotrader.backtest.ExitReason
import com.algotrader.backtest.PositionSizing
import com.algotrader.backtest.TradeDirection
import com.algotrader.backtest.PerformanceMetrics
import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.strategyengine.StrategyConfiguration
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Durable local storage for Phase 3 backtest jobs.
 *
 * The Activity creates jobs, while WorkManager owns their execution.
 * No Activity/View references are stored here.
 */
private const val CHECKPOINTS_KEY = "backtest_checkpoints"
private const val RESULTS_KEY = "__results__"
private const val CANDLES_KEY = "__candles__"

/*
 * Activity and WorkManager each create their own BacktestJobStore instance.
 * @Synchronized only locks one instance, so root read/modify/write operations
 * could previously race between those two instances.
 */
private val BACKTEST_STORAGE_LOCK = Any()

/**
 * Explicit instrument classification for persisted backtests.
 *
 * INDEX represents the existing index market-data flow.
 * FUTURES represents a real futures contract and must not silently
 * substitute index candles.
 */
enum class BacktestInstrumentType {
    INDEX,
    FUTURES
}

/**
 * Identity/configuration for a futures contract.
 *
 * Nullable fields are intentional: unknown futures metadata must remain
 * unknown rather than being fabricated or defaulted.
 */
data class FuturesContractConfig(
    val underlying: String? = null,
    val contractMonth: String? = null,
    val expiry: String? = null,
    val contractId: String? = null,
    val lotSize: Int? = null,
    /** Expiry exactly as returned by the FYERS futures chain (epoch seconds); null if not supplied. */
    val expiryEpochSeconds: Long? = null,
    /** Authoritative source of [lotSize]; null when the lot size is unknown. */
    val lotSizeSource: String? = null
)

class BacktestJobStore internal constructor(
    private val storageDir: File
) {

    /** Production constructor: app-private files directory. */
    constructor(context: Context) : this(
        context.applicationContext.getFileStreamPath(FILE_NAME).parentFile
            ?: context.applicationContext.filesDir
    )

    // The internal primary constructor takes a directory so the storage rules
    // (cancel / delete / reload) can be unit-tested on the JVM without Android.

    enum class Status {
        QUEUED,
        PREPARING,
        RUNNING,
        CALCULATING,
        SAVING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class Checkpoint(
        val startedAtMillis: Long,
        val results: List<BacktestResult>
    )

    data class Job(
        val id: String,
        /**
         * Shared identifier for strategies launched by the same Backtest run.
         *
         * Nullable for backwards compatibility with jobs created before
         * multi-strategy result grouping existed.
         */
        val runId: String? = null,
        /** FULL for a standard job; IN_SAMPLE / OUT_OF_SAMPLE for the two segments of an OOS launch. */
        val sample: BacktestSample = BacktestSample.FULL,
        val status: Status,
        val instrumentSymbol: String,
        val instrumentExchange: String,
        val instrumentCurrency: String,
        val instrumentType: BacktestInstrumentType = BacktestInstrumentType.INDEX,
        val futuresContract: FuturesContractConfig? = null,
        val timeframe: Timeframe,
        val strategies: List<StrategyConfiguration>,
        val initialCapital: Double,
        val positionSizing: PositionSizing,
        val candleCount: Int,
        val progress: Int,
        val currentStep: String,
        val createdAt: Instant,
        val updatedAt: Instant,
        val completedAt: Instant? = null,
        val errorMessage: String? = null
    )

    private val file = File(storageDir, FILE_NAME)

    init {
        synchronized(BACKTEST_STORAGE_LOCK) {
            migrateLegacyStorageIfNeeded()
        }
    }

    @Synchronized
    fun create(
        instrument: Instrument,
        timeframe: Timeframe,
        strategies: List<StrategyConfiguration>,
        initialCapital: Double,
        positionSizing: PositionSizing,
        candleCount: Int,
        instrumentType: BacktestInstrumentType = BacktestInstrumentType.INDEX,
        futuresContract: FuturesContractConfig? = null,
        runId: String? = null,
        sample: BacktestSample = BacktestSample.FULL
    ): Job {
        require(strategies.isNotEmpty()) {
            "At least one strategy is required for a backtest job"
        }

        val now = Instant.now()

        val job = Job(
            id = UUID.randomUUID().toString(),
            runId = runId,
            sample = sample,
            status = Status.QUEUED,
            instrumentSymbol = instrument.symbol,
            instrumentExchange = instrument.exchange,
            instrumentCurrency = instrument.currency,
            instrumentType = instrumentType,
            futuresContract = futuresContract,
            timeframe = timeframe,
            strategies = strategies,
            initialCapital = initialCapital,
            positionSizing = positionSizing,
            candleCount = candleCount,
            progress = 0,
            currentStep = "Queued",
            createdAt = now,
            updatedAt = now
        )

        writeJob(job)
        return job
    }

    @Synchronized
    fun update(
        jobId: String,
        status: Status? = null,
        progress: Int? = null,
        currentStep: String? = null,
        errorMessage: String? = null,
        completedAt: Instant? = null
    ): Job? = synchronized(BACKTEST_STORAGE_LOCK) {
        val existing = get(jobId) ?: return@synchronized null

        // A cancelled job is final. The Worker may still be mid-flight when the
        // user cancels; its late progress/complete/fail writes must never
        // resurrect the job. The unchanged CANCELLED job is returned so the
        // caller can see the cancellation and stop.
        if (existing.status == Status.CANCELLED) return@synchronized existing

        val updated = existing.copy(
            status = status ?: existing.status,
            progress = progress?.coerceIn(0, 100) ?: existing.progress,
            currentStep = currentStep ?: existing.currentStep,
            updatedAt = Instant.now(),
            completedAt = completedAt ?: existing.completedAt,
            errorMessage = errorMessage
        )

        writeJob(updated)
        updated
    }

    @Synchronized
    fun complete(jobId: String): Job? {
        return update(
            jobId = jobId,
            status = Status.COMPLETED,
            progress = 100,
            currentStep = "Complete",
            completedAt = Instant.now(),
            errorMessage = null
        )
    }

    @Synchronized
    fun clearCheckpointAndComplete(jobId: String): Job? {
        val existing = get(jobId) ?: return null
        if (existing.status == Status.CANCELLED) return existing
        val now = Instant.now()

        deleteStorageFile(checkpointFile(jobId))

        val updated = existing.copy(
            status = Status.COMPLETED,
            progress = 100,
            currentStep = "Complete",
            updatedAt = now,
            completedAt = now,
            errorMessage = null
        )

        writeJob(updated)
        return updated
    }


    @Synchronized
    fun fail(jobId: String, message: String): Job? {
        return update(
            jobId = jobId,
            status = Status.FAILED,
            currentStep = "Failed",
            errorMessage = message
        )
    }

    /**
     * Persists a single job as CANCELLED. Reuses the existing CANCELLED status.
     *
     * Only an active job can be cancelled: COMPLETED / FAILED / CANCELLED jobs
     * are returned unchanged, so a finished result can never be turned into a
     * cancelled one.
     */
    @Synchronized
    fun cancel(jobId: String): Job? = synchronized(BACKTEST_STORAGE_LOCK) {
        val existing = get(jobId) ?: return@synchronized null
        if (existing.status in TERMINAL_STATUSES) return@synchronized existing

        val cancelled = existing.copy(
            status = Status.CANCELLED,
            currentStep = "Cancelled",
            updatedAt = Instant.now(),
            errorMessage = null
        )
        writeJob(cancelled)
        // Partial strategy results must never be resumed or presented.
        deleteStorageFile(checkpointFile(jobId))
        cancelled
    }

    /**
     * Cancels the whole logical launch that [jobId] belongs to: every job
     * sharing its runId (standard: all strategy jobs; OOS: every In-Sample and
     * Out-of-Sample job). A legacy job without runId cancels only itself.
     * Jobs of other runs are never touched. Already-finished siblings keep
     * their status.
     *
     * @return the ids of the jobs that were actually moved to CANCELLED.
     */
    @Synchronized
    fun cancelRun(jobId: String): List<String> = synchronized(BACKTEST_STORAGE_LOCK) {
        val target = get(jobId) ?: return@synchronized emptyList()
        runMembers(target)
            .filter { it.status !in TERMINAL_STATUSES }
            .mapNotNull { member ->
                cancel(member.id)?.takeIf { it.status == Status.CANCELLED }?.id
            }
    }

    /** True when any job of the launch [jobId] belongs to is still active. */
    @Synchronized
    fun isRunActive(jobId: String): Boolean {
        val target = get(jobId) ?: return false
        return runMembers(target).any { it.status !in TERMINAL_STATUSES }
    }

    /** All jobs of the logical launch [target] belongs to (itself only for legacy jobs). */
    @Synchronized
    fun runMembers(target: Job): List<Job> =
        BacktestRunScope.members(list(), target, { it.id }, { it.runId })

    sealed interface DeleteOutcome {
        /** The ids of every job removed (a whole run for runId jobs). */
        data class Deleted(val jobIds: List<String>) : DeleteOutcome
        /** Nothing removed: part of the run is still running; cancel it first. */
        object StillActive : DeleteOutcome
        object NotFound : DeleteOutcome
    }

    /**
     * Permanently deletes a saved test. For a runId job this is the whole
     * logical run (never one segment), so no In-Sample / Out-of-Sample sibling
     * is orphaned. A legacy job without runId is deleted individually.
     *
     * Removes, per job: the root job-index entry, saved results (dedicated file
     * and the copy inside the root), candles and checkpoint. Active runs are
     * refused rather than deleted from under a running Worker.
     */
    @Synchronized
    fun deleteRun(jobId: String): DeleteOutcome = synchronized(BACKTEST_STORAGE_LOCK) {
        val target = get(jobId) ?: return@synchronized DeleteOutcome.NotFound
        val members = runMembers(target)

        if (members.any { it.status !in TERMINAL_STATUSES }) {
            return@synchronized DeleteOutcome.StillActive
        }

        val ids = members.map { it.id }
        val root = readRoot()
        ids.forEach { id -> root.remove(id) }
        root.optJSONObject(RESULTS_KEY)?.let { results -> ids.forEach { results.remove(it) } }
        root.optJSONObject(CANDLES_KEY)?.let { candles -> ids.forEach { candles.remove(it) } }
        root.optJSONObject(CHECKPOINTS_KEY)?.let { cps -> ids.forEach { cps.remove(it) } }
        writeRoot(root)

        ids.forEach { id ->
            deleteStorageFile(resultsFile(id))
            deleteStorageFile(candlesFile(id))
            deleteStorageFile(checkpointFile(id))
        }

        DeleteOutcome.Deleted(ids)
    }

    @Synchronized
    fun get(jobId: String): Job? {
        if (!file.exists()) return null

        val json = readRoot().optJSONObject(jobId) ?: return null
        return runCatching { jobFromJson(json) }.getOrNull()
    }

    @Synchronized
    fun list(): List<Job> {
        if (!file.exists()) return emptyList()

        val root = readRoot()
        val jobs = mutableListOf<Job>()

        root.keys().forEach { key ->
            root.optJSONObject(key)?.let { json ->
                runCatching { jobFromJson(json) }
                    .getOrNull()
                    ?.let(jobs::add)
            }
        }

        return jobs.sortedByDescending { it.createdAt }
    }

    private fun isCancelledOrMissing(jobId: String): Boolean {
        val job = get(jobId)
        return job == null || job.status == Status.CANCELLED
    }

    @Synchronized
    fun saveCheckpoint(
        jobId: String,
        startedAtMillis: Long,
        results: List<BacktestResult>
    ) {
        // Never (re)create files for a job that was cancelled or deleted.
        if (isCancelledOrMissing(jobId)) return

        val checkpoint = JSONObject().apply {
            put("startedAtMillis", startedAtMillis)

            val resultsArray = JSONArray()
            results.forEach { resultsArray.put(resultToJson(it)) }
            put("results", resultsArray)
        }

        writeJsonFile(checkpointFile(jobId), checkpoint)
    }

    @Synchronized
    fun getCheckpoint(jobId: String): Checkpoint? {
        val checkpoint = readJsonFile(checkpointFile(jobId)) ?: return null

        val resultsArray = checkpoint.optJSONArray("results") ?: JSONArray()

        val results = buildList {
            for (index in 0 until resultsArray.length()) {
                runCatching {
                    add(resultFromJson(resultsArray.getJSONObject(index)))
                }
            }
        }

        return Checkpoint(
            startedAtMillis = checkpoint.optLong(
                "startedAtMillis",
                System.currentTimeMillis()
            ),
            results = results
        )
    }

    @Synchronized
    fun clearCheckpoint(jobId: String) {
        deleteStorageFile(checkpointFile(jobId))
    }

    @Synchronized
    fun saveResults(
        jobId: String,
        results: List<BacktestResult>,
        /** Optional per-result futures accounting, index-aligned with [results]. */
        futuresAccounting: List<FuturesBacktestAccounting.Block?> = emptyList()
    ) {
        synchronized(BACKTEST_STORAGE_LOCK) {
            require(results.isNotEmpty()) {
                "Cannot save empty backtest results"
            }

            require(
                futuresAccounting.isEmpty() ||
                    futuresAccounting.size == results.size
            ) {
                "Futures accounting must be index-aligned with results"
            }

            // Never (re)create results for a job that was cancelled or deleted.
            if (isCancelledOrMissing(jobId)) return

            val array = JSONArray()

            results.forEachIndexed { index, result ->
                val json = resultToJson(result)
                futuresAccounting.getOrNull(index)?.let {
                    json.put(
                        FUTURES_ACCOUNTING_KEY,
                        futuresAccountingToJson(it)
                    )
                }
                array.put(json)
            }

            /*
             * Keep the dedicated result file for normal operation.
             * Also persist the result array inside the main job store so a
             * completed job can always restore its results from the same
             * persistent record as its metadata.
             *
             * The shared storage lock covers the entire transaction because
             * Activity and Worker use different BacktestJobStore instances.
             */
            writeJsonFile(resultsFile(jobId), array)

            val root = readRoot()
            val resultsRoot = root.optJSONObject(RESULTS_KEY) ?: JSONObject()
            resultsRoot.put(jobId, array)
            root.put(RESULTS_KEY, resultsRoot)
            writeRoot(root)

            val dedicatedFile = resultsFile(jobId)
            if (!dedicatedFile.exists() || dedicatedFile.length() == 0L) {
                throw IllegalStateException(
                    "Backtest results were not persisted: ${dedicatedFile.absolutePath}"
                )
            }

            val persistedRoot = readRoot()
                .optJSONObject(RESULTS_KEY)
                ?.optJSONArray(jobId)

            if (persistedRoot == null || persistedRoot.length() == 0) {
                throw IllegalStateException(
                    "Backtest results were not persisted in the job store: $jobId"
                )
            }
        }
    }

    fun debugResultsStorage(jobId: String): String {
        val target = resultsFile(jobId)
        return "path=${target.absolutePath}, exists=${target.exists()}, size=${if (target.exists()) target.length() else 0L}"
    }

    @Synchronized
    fun getResults(jobId: String): List<BacktestResult> {
        /*
         * Activity and Worker use separate BacktestJobStore instances.
         * Therefore @Synchronized alone does not coordinate result reads
         * with saveResults(). Use the same process-wide storage lock for
         * the complete read transaction.
         */
        synchronized(BACKTEST_STORAGE_LOCK) {
            val dedicated = readJsonArrayFile(resultsFile(jobId))
            val array = dedicated ?: readRoot()
                .optJSONObject(RESULTS_KEY)
                ?.optJSONArray(jobId)
                ?: return emptyList()

            return buildList {
                for (index in 0 until array.length()) {
                    try {
                        add(resultFromJson(array.getJSONObject(index)))
                    } catch (e: Exception) {
                        throw IllegalStateException(
                            "Saved result $index could not be restored: ${e.javaClass.simpleName}: ${e.message}",
                            e
                        )
                    }
                }
            }
        }
    }

    private fun readResultsArray(jobId: String): JSONArray? =
        readJsonArrayFile(resultsFile(jobId))
            ?: readRoot()
                .optJSONObject(RESULTS_KEY)
                ?.optJSONArray(jobId)

    sealed interface RestoredFuturesAccounting {
        object Absent : RestoredFuturesAccounting

        data class Present(
            val block: FuturesBacktestAccounting.Block
        ) : RestoredFuturesAccounting

        data class Malformed(
            val reason: String
        ) : RestoredFuturesAccounting
    }

    @Synchronized
    fun getFuturesAccounting(
        jobId: String
    ): List<RestoredFuturesAccounting> {
        synchronized(BACKTEST_STORAGE_LOCK) {
            val array = readResultsArray(jobId)
                ?: return emptyList()

            return buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index)

                    add(
                        when {
                            entry == null ->
                                RestoredFuturesAccounting.Absent

                            !entry.has(FUTURES_ACCOUNTING_KEY) ->
                                RestoredFuturesAccounting.Absent

                            else ->
                                futuresAccountingFromJson(
                                    entry.opt(FUTURES_ACCOUNTING_KEY)
                                )
                        }
                    )
                }
            }
        }
    }

    private fun futuresAccountingToJson(
        b: FuturesBacktestAccounting.Block
    ): JSONObject =
        JSONObject().apply {
            put("schemaVersion", b.schemaVersion)
            put("contractId", b.contractId ?: JSONObject.NULL)
            put("lotSize", b.lotSize ?: JSONObject.NULL)
            put("lotSizeSource", b.lotSizeSource ?: JSONObject.NULL)
            put(
                "lotSizeEvidence",
                b.lotSizeEvidence ?: JSONObject.NULL
            )
            put(
                "expiryEpochSeconds",
                b.expiryEpochSeconds ?: JSONObject.NULL
            )
            put("notionalBasis", b.notionalBasis)
            put(
                "contractNotional",
                b.contractNotional?.toPlainString()
                    ?: JSONObject.NULL
            )
            put("tradeCount", b.tradeCount)
            put("computedTradeCount", b.computedTradeCount)
            put(
                "calculatorGrossPnl",
                b.calculatorGrossPnl?.toPlainString()
                    ?: JSONObject.NULL
            )
            put(
                "engineGrossPnl",
                b.engineGrossPnl?.toPlainString()
                    ?: JSONObject.NULL
            )
            put(
                "grossPnlDifference",
                b.grossPnlDifference?.toPlainString()
                    ?: JSONObject.NULL
            )
            put(
                "grossPnlComparison",
                b.grossPnlComparison.name
            )
            put("status", b.status.name)
            put("reason", b.reason ?: JSONObject.NULL)
            put("leverage", b.leverage)
            put("charges", b.charges)
            put("netPnl", b.netPnl)
            put("breakEven", b.breakEven)
        }

    private fun futuresAccountingFromJson(
        raw: Any?
    ): RestoredFuturesAccounting {
        val json = raw as? JSONObject
            ?: return RestoredFuturesAccounting.Malformed(
                "accounting block is not an object"
            )

        return try {
            fun key(k: String): Any {
                if (!json.has(k)) {
                    throw IllegalArgumentException("missing $k")
                }
                return json.get(k)
            }

            fun isNull(k: String): Boolean =
                key(k) == JSONObject.NULL

            fun str(k: String): String =
                key(k) as? String
                    ?: throw IllegalArgumentException(
                        "$k is not a string"
                    )

            fun strOrNull(k: String): String? =
                if (isNull(k)) null else str(k)

            fun long(k: String): Long =
                when (val v = key(k)) {
                    is Int -> v.toLong()
                    is Long -> v
                    else ->
                        throw IllegalArgumentException(
                            "$k is not an integer"
                        )
                }

            fun int(k: String): Int {
                val v = long(k)
                if (
                    v < Int.MIN_VALUE ||
                    v > Int.MAX_VALUE
                ) {
                    throw IllegalArgumentException(
                        "$k out of range"
                    )
                }
                return v.toInt()
            }

            fun decimalOrNull(k: String): java.math.BigDecimal? =
                if (isNull(k)) {
                    null
                } else {
                    java.math.BigDecimal(str(k))
                }

            fun constant(
                k: String,
                expected: String
            ): String {
                val v = str(k)
                if (v != expected) {
                    throw IllegalArgumentException(
                        "$k must be $expected, got $v"
                    )
                }
                return v
            }

            val schema = int("schemaVersion")

            if (
                schema !=
                    FuturesBacktestAccounting.SCHEMA_VERSION
            ) {
                throw IllegalArgumentException(
                    "unsupported schemaVersion $schema"
                )
            }

            val lotSize =
                if (isNull("lotSize")) {
                    null
                } else {
                    int("lotSize")
                }

            val lotSizeEvidence =
                if (isNull("lotSizeEvidence")) {
                    null
                } else {
                    constant(
                        "lotSizeEvidence",
                        FuturesBacktestAccounting.LOT_SIZE_EVIDENCE
                    )
                }

            if (
                (lotSize == null) !=
                    (lotSizeEvidence == null)
            ) {
                throw IllegalArgumentException(
                    "lotSize and lotSizeEvidence must both be present or both be null"
                )
            }

            RestoredFuturesAccounting.Present(
                FuturesBacktestAccounting.Block(
                    schemaVersion = schema,
                    contractId = strOrNull("contractId"),
                    lotSize = lotSize,
                    lotSizeSource = strOrNull("lotSizeSource"),
                    lotSizeEvidence = lotSizeEvidence,
                    expiryEpochSeconds =
                        if (isNull("expiryEpochSeconds")) {
                            null
                        } else {
                            long("expiryEpochSeconds")
                        },
                    notionalBasis =
                        constant(
                            "notionalBasis",
                            FuturesBacktestAccounting.NOTIONAL_BASIS
                        ),
                    contractNotional =
                        decimalOrNull("contractNotional"),
                    tradeCount = int("tradeCount"),
                    computedTradeCount =
                        int("computedTradeCount"),
                    calculatorGrossPnl =
                        decimalOrNull("calculatorGrossPnl"),
                    engineGrossPnl =
                        decimalOrNull("engineGrossPnl"),
                    grossPnlDifference =
                        decimalOrNull("grossPnlDifference"),
                    grossPnlComparison =
                        FuturesBacktestAccounting.GrossPnlComparison
                            .valueOf(str("grossPnlComparison")),
                    status =
                        FuturesBacktestAccounting.Status
                            .valueOf(str("status")),
                    reason = strOrNull("reason"),
                    leverage =
                        constant(
                            "leverage",
                            FuturesBacktestAccounting.NOT_MODELLED
                        ),
                    charges =
                        constant(
                            "charges",
                            FuturesBacktestAccounting.NOT_MODELLED
                        ),
                    netPnl =
                        constant(
                            "netPnl",
                            FuturesBacktestAccounting.NOT_MODELLED
                        ),
                    breakEven =
                        constant(
                            "breakEven",
                            FuturesBacktestAccounting.NOT_MODELLED
                        )
                )
            )
        } catch (e: Exception) {
            RestoredFuturesAccounting.Malformed(
                "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun strategiesToJson(
        strategies: List<StrategyConfiguration>
    ): JSONArray {
        return JSONArray().apply {
            strategies.forEach { strategy ->
                put(
                    JSONObject().apply {
                        put("strategyId", strategy.strategyId)

                        val parameters = JSONObject()
                        strategy.parameters.forEach { (key, value) ->
                            parameters.put(key, value)
                        }

                        put("parameters", parameters)
                    }
                )
            }
        }
    }

    private fun strategiesFromJson(
        array: JSONArray
    ): List<StrategyConfiguration> {
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                val parametersJson = json.optJSONObject("parameters")
                val parameters = mutableMapOf<String, Double>()

                parametersJson?.keys()?.forEach { key ->
                    parameters[key] = parametersJson.getDouble(key)
                }

                add(
                    StrategyConfiguration(
                        strategyId = json.getString("strategyId"),
                        parameters = parameters
                    )
                )
            }
        }
    }

    private fun positionSizingToJson(
        sizing: PositionSizing
    ): JSONObject {
        return when (sizing) {
            is PositionSizing.FixedQuantity -> JSONObject().apply {
                put("type", "fixed_quantity")
                put("quantity", sizing.quantity)
            }

            is PositionSizing.PercentOfEquity -> JSONObject().apply {
                put("type", "percent_of_equity")
                put("percent", sizing.percent)
            }

            is PositionSizing.FixedLots -> JSONObject().apply {
                put("type", "fixed_lots")
                put("lots", sizing.lots)
            }
        }
    }

    private fun positionSizingFromJson(
        json: JSONObject
    ): PositionSizing {
        return when (json.getString("type")) {
            "fixed_quantity" -> PositionSizing.FixedQuantity(
                json.getDouble("quantity")
            )

            "percent_of_equity" -> PositionSizing.PercentOfEquity(
                json.getDouble("percent")
            )

            "fixed_lots" -> PositionSizing.FixedLots(
                json.getInt("lots")
            )

            else -> error(
                "Unknown position sizing type: ${json.getString("type")}"
            )
        }
    }

    private fun resultToJson(result: BacktestResult): JSONObject {
        return JSONObject().apply {
            put("strategyName", result.strategyName)
            put("sample", result.sample.name)
            put("finalEquity", result.finalEquity)

            put(
                "config",
                JSONObject().apply {
                    put("initialCapital", result.config.initialCapital)
                    put(
                        "positionSizing",
                        positionSizingToJson(result.config.positionSizing)
                    )
                    put("lotSize", result.config.lotSize)
                }
            )

            put(
                "trades",
                JSONArray().apply {
                    result.trades.forEach { trade ->
                        put(
                            JSONObject().apply {
                                put("direction", trade.direction.name)
                                put("entryIndex", trade.entryIndex)
                                put(
                                    "entryTimestamp",
                                    trade.entryTimestamp.toString()
                                )
                                put("entryPrice", trade.entryPrice)
                                put("exitIndex", trade.exitIndex)
                                put(
                                    "exitTimestamp",
                                    trade.exitTimestamp.toString()
                                )
                                put("exitPrice", trade.exitPrice)
                                put("quantity", trade.quantity)
                                trade.exitReason?.let {
                                    put("exitReason", it.name)
                                }
                                trade.exitDetail?.let {
                                    put("exitDetail", it)
                                }
                                put("grossPnl", trade.grossPnl)
                                put("returnPercent", trade.returnPercent)
                                put(
                                    "holdingPeriodBars",
                                    trade.holdingPeriodBars
                                )
                                put("isWin", trade.isWin)
                            }
                        )
                    }
                }
            )

            put(
                "equityCurve",
                JSONArray().apply {
                    persistedEquityCurve(result.equityCurve)
                        .forEach { point ->
                            put(
                                JSONObject().apply {
                                    put("index", point.index)
                                    put(
                                        "timestamp",
                                        point.timestamp.toString()
                                    )
                                    put("equity", point.equity)
                                }
                            )
                        }
                }
            )

            put(
                "metrics",
                JSONObject().apply {
                    put("totalTrades", result.metrics.totalTrades)
                    put("winningTrades", result.metrics.winningTrades)
                    put("losingTrades", result.metrics.losingTrades)
                    put("winRate", result.metrics.winRate)
                    put("grossProfit", result.metrics.grossProfit)
                    put("grossLoss", result.metrics.grossLoss)
                    put("netProfit", result.metrics.netProfit)
                    put(
                        "totalReturnPercent",
                        result.metrics.totalReturnPercent
                    )
                    put("maxDrawdown", result.metrics.maxDrawdown)
                    put(
                        "maxDrawdownPercent",
                        result.metrics.maxDrawdownPercent
                    )
                    put("averageTradePnl", result.metrics.averageTradePnl)

                    result.metrics.profitFactor?.let {
                        put("profitFactor", it)
                    }
                    result.metrics.averageWinningTrade?.let {
                        put("averageWinningTrade", it)
                    }
                    result.metrics.averageLosingTrade?.let {
                        put("averageLosingTrade", it)
                    }
                }
            )
        }
    }

    private fun resultFromJson(json: JSONObject): BacktestResult {
        val configJson = json.getJSONObject("config")

        val trades = buildList {
            val array = json.optJSONArray("trades") ?: JSONArray()
            for (index in 0 until array.length()) {
                val trade = array.getJSONObject(index)

                add(
                    BacktestTrade(
                        direction = TradeDirection.valueOf(
                            trade.getString("direction")
                        ),
                        entryIndex = trade.getInt("entryIndex"),
                        entryTimestamp = Instant.parse(
                            trade.getString("entryTimestamp")
                        ),
                        entryPrice = trade.getDouble("entryPrice"),
                        exitIndex = trade.getInt("exitIndex"),
                        exitTimestamp = Instant.parse(
                            trade.getString("exitTimestamp")
                        ),
                        exitPrice = trade.getDouble("exitPrice"),
                        quantity = trade.getDouble("quantity"),
                        exitReason = trade.optString("exitReason", "")
                            .takeIf { it.isNotBlank() }
                            ?.let { name ->
                                runCatching { ExitReason.valueOf(name) }.getOrNull()
                            },
                        exitDetail = trade.optString("exitDetail", "")
                            .takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        val equityCurve = buildList {
            val array = json.optJSONArray("equityCurve") ?: JSONArray()
            for (index in 0 until array.length()) {
                val point = array.getJSONObject(index)

                add(
                    EquityPoint(
                        index = point.getInt("index"),
                        timestamp = Instant.parse(
                            point.getString("timestamp")
                        ),
                        equity = point.getDouble("equity")
                    )
                )
            }
        }

        val metricsJson = json.getJSONObject("metrics")

        val metrics = PerformanceMetrics(
            totalTrades = metricsJson.getInt("totalTrades"),
            winningTrades = metricsJson.getInt("winningTrades"),
            losingTrades = metricsJson.getInt("losingTrades"),
            winRate = metricsJson.getDouble("winRate"),
            grossProfit = metricsJson.getDouble("grossProfit"),
            grossLoss = metricsJson.getDouble("grossLoss"),
            netProfit = metricsJson.getDouble("netProfit"),
            totalReturnPercent = metricsJson.getDouble("totalReturnPercent"),
            maxDrawdown = metricsJson.getDouble("maxDrawdown"),
            maxDrawdownPercent = metricsJson.getDouble("maxDrawdownPercent"),
            averageTradePnl = metricsJson.getDouble("averageTradePnl"),
            profitFactor = metricsJson.optDoubleOrNull("profitFactor"),
            averageWinningTrade =
                metricsJson.optDoubleOrNull("averageWinningTrade"),
            averageLosingTrade =
                metricsJson.optDoubleOrNull("averageLosingTrade")
        )

        return BacktestResult(
            strategyName = json.getString("strategyName"),
            config = BacktestConfig(
                initialCapital = configJson.getDouble("initialCapital"),
                positionSizing = positionSizingFromJson(
                    configJson.getJSONObject("positionSizing")
                ),
                lotSize = configJson.optInt("lotSize", 1).coerceAtLeast(1)
            ),
            finalEquity = json.getDouble("finalEquity"),
            trades = trades,
            equityCurve = equityCurve,
            metrics = metrics,
            // Results saved before out-of-sample testing have no "sample" key.
            sample = runCatching {
                BacktestSample.valueOf(
                    json.optString("sample", BacktestSample.FULL.name)
                )
            }.getOrDefault(BacktestSample.FULL)
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeUnless { it.isNaN() }
    }

    @Synchronized
    fun saveCandles(jobId: String, candles: List<Candle>) {
        val array = JSONArray()

        candles.forEach { candle ->
            array.put(
                JSONObject().apply {
                    put("symbol", candle.instrument.symbol)
                    put("exchange", candle.instrument.exchange)
                    put("currency", candle.instrument.currency)
                    put("timeframe", candle.timeframe.name)
                    put("timestamp", candle.timestamp.toString())
                    put("open", candle.open)
                    put("high", candle.high)
                    put("low", candle.low)
                    put("close", candle.close)
                    put("volume", candle.volume)
                }
            )
        }

        writeJsonFile(candlesFile(jobId), array)
    }

    @Synchronized
    fun getCandles(jobId: String): List<Candle> {
        val array = readJsonArrayFile(candlesFile(jobId)) ?: return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)

                add(
                    Candle(
                        instrument = Instrument(
                            symbol = json.getString("symbol"),
                            exchange = json.getString("exchange"),
                            currency = json.optString("currency", "INR")
                        ),
                        timeframe = Timeframe.valueOf(json.getString("timeframe")),
                        timestamp = Instant.parse(json.getString("timestamp")),
                        open = json.getDouble("open"),
                        high = json.getDouble("high"),
                        low = json.getDouble("low"),
                        close = json.getDouble("close"),
                        volume = json.getDouble("volume")
                    )
                )
            }
        }
    }

    private fun writeJob(job: Job) {
        synchronized(BACKTEST_STORAGE_LOCK) {
            val root = readRoot()
            root.put(job.id, jobToJson(job))
            writeRoot(root)
        }
    }

    private fun jobToJson(job: Job): JSONObject {
        return JSONObject().apply {
            put("id", job.id)
            job.runId?.let { put("runId", it) }
            put("sample", job.sample.name)
            put("status", job.status.name)
            put("instrumentSymbol", job.instrumentSymbol)
            put("instrumentExchange", job.instrumentExchange)
            put("instrumentCurrency", job.instrumentCurrency)
            put("instrumentType", job.instrumentType.name)

            job.futuresContract?.let { contract ->
                put("futuresContract", JSONObject().apply {
                    contract.underlying?.let { put("underlying", it) }
                    contract.contractMonth?.let { put("contractMonth", it) }
                    contract.expiry?.let { put("expiry", it) }
                    contract.contractId?.let { put("contractId", it) }
                    contract.lotSize?.let { put("lotSize", it) }
                    contract.expiryEpochSeconds?.let { put("expiryEpochSeconds", it) }
                    contract.lotSizeSource?.let { put("lotSizeSource", it) }
                })
            }

            put("timeframe", job.timeframe.name)
            put("strategies", strategiesToJson(job.strategies))
            put("initialCapital", job.initialCapital)
            put("positionSizing", positionSizingToJson(job.positionSizing))
            put("candleCount", job.candleCount)
            put("progress", job.progress)
            put("currentStep", job.currentStep)
            put("createdAt", job.createdAt.toString())
            put("updatedAt", job.updatedAt.toString())
            job.completedAt?.let { put("completedAt", it.toString()) }
            job.errorMessage?.let { put("errorMessage", it) }
        }
    }

    private fun jobFromJson(json: JSONObject): Job {
        return Job(
            id = json.getString("id"),
            runId = json.optString("runId", "")
                .takeIf { it.isNotEmpty() },
            // Jobs saved before out-of-sample testing have no "sample" key:
            // they are standard (FULL) jobs.
            sample = runCatching {
                BacktestSample.valueOf(
                    json.optString("sample", BacktestSample.FULL.name)
                )
            }.getOrDefault(BacktestSample.FULL),
            status = Status.valueOf(json.getString("status")),
            instrumentSymbol = json.getString("instrumentSymbol"),
            instrumentExchange = json.getString("instrumentExchange"),
            instrumentCurrency = json.optString("instrumentCurrency", "INR"),

            // Existing saved jobs predate F&O classification, so they are
            // explicitly restored as INDEX rather than guessed as futures.
            instrumentType = runCatching {
                BacktestInstrumentType.valueOf(
                    json.optString(
                        "instrumentType",
                        BacktestInstrumentType.INDEX.name
                    )
                )
            }.getOrDefault(BacktestInstrumentType.INDEX),

            futuresContract = json.optJSONObject("futuresContract")?.let { contract ->
                FuturesContractConfig(
                    underlying = contract.optString("underlying", "")
                        .takeIf { it.isNotEmpty() },
                    contractMonth = contract.optString("contractMonth", "")
                        .takeIf { it.isNotEmpty() },
                    expiry = contract.optString("expiry", "")
                        .takeIf { it.isNotEmpty() },
                    contractId = contract.optString("contractId", "")
                        .takeIf { it.isNotEmpty() },
                    lotSize = if (contract.has("lotSize") && !contract.isNull("lotSize")) {
                        contract.optInt("lotSize", 0).takeIf { it > 0 }
                    } else {
                        null
                    },
                    lotSizeSource = contract.optString("lotSizeSource", "")
                        .takeIf { it.isNotEmpty() },
                    expiryEpochSeconds =
                        if (contract.has("expiryEpochSeconds") && !contract.isNull("expiryEpochSeconds")) {
                            contract.optLong("expiryEpochSeconds", 0L).takeIf { it > 0L }
                        } else {
                            null
                        }
                )
            },

            timeframe = Timeframe.valueOf(json.getString("timeframe")),
            strategies = strategiesFromJson(json.getJSONArray("strategies")),
            initialCapital = json.getDouble("initialCapital"),
            positionSizing = positionSizingFromJson(
                json.getJSONObject("positionSizing")
            ),
            candleCount = json.getInt("candleCount"),
            progress = json.getInt("progress"),
            currentStep = json.getString("currentStep"),
            createdAt = Instant.parse(json.getString("createdAt")),
            updatedAt = Instant.parse(json.getString("updatedAt")),
            completedAt = json.optString("completedAt", "")
                .takeIf { it.isNotEmpty() }
                ?.let(Instant::parse),
            errorMessage = json.optString("errorMessage", "")
                .takeIf { it.isNotEmpty() }
        )
    }

    private fun resultsFile(jobId: String): File =
        File(storageDir, "${jobId}_results.json")

    private fun candlesFile(jobId: String): File =
        File(storageDir, "${jobId}_candles.json")

    private fun checkpointFile(jobId: String): File =
        File(storageDir, "${jobId}_checkpoint.json")

    private fun readJsonFile(target: File): JSONObject? {
        if (!target.exists()) return null

        return runCatching {
            JSONObject(
                target.inputStream().bufferedReader().use { it.readText() }
            )
        }.getOrNull()
    }

    private fun readJsonArrayFile(target: File): JSONArray? {
        if (!target.exists()) return null

        return runCatching {
            JSONArray(
                target.inputStream().bufferedReader().use { it.readText() }
            )
        }.getOrNull()
    }

    private fun writeJsonFile(target: File, json: Any) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        val text = json.toString()

        tempFile.outputStream().bufferedWriter().use {
            it.write(text)
        }

        if (!tempFile.renameTo(target)) {
            tempFile.delete()
            target.outputStream().bufferedWriter().use {
                it.write(text)
            }
        }
    }

    private fun deleteStorageFile(target: File) {
        if (target.exists()) {
            target.delete()
        }

        val temp = File(target.parentFile, "${target.name}.tmp")
        if (temp.exists()) {
            temp.delete()
        }
    }

    private fun migrateLegacyStorageIfNeeded() {
        val marker = File(storageDir, LEGACY_MIGRATION_MARKER)
        if (marker.exists()) return
        if (!file.exists()) {
            marker.createNewFile()
            return
        }

        val legacy = runCatching {
            JSONObject(
                file.inputStream().bufferedReader().use { it.readText() }
            )
        }.getOrNull() ?: return

        /*
         * Existing job metadata stays in backtest_jobs.json.
         * Large legacy payloads are extracted into their own files once.
         */
        legacy.optJSONObject(RESULTS_KEY)?.let { resultsRoot ->
            resultsRoot.keys().forEach { jobId ->
                resultsRoot.optJSONArray(jobId)?.let {
                    writeJsonFile(resultsFile(jobId), it)
                }
            }
            legacy.remove(RESULTS_KEY)
        }

        legacy.optJSONObject(CANDLES_KEY)?.let { candlesRoot ->
            candlesRoot.keys().forEach { jobId ->
                candlesRoot.optJSONArray(jobId)?.let {
                    writeJsonFile(candlesFile(jobId), it)
                }
            }
            legacy.remove(CANDLES_KEY)
        }

        legacy.optJSONObject(CHECKPOINTS_KEY)?.let { checkpointsRoot ->
            checkpointsRoot.keys().forEach { jobId ->
                checkpointsRoot.optJSONObject(jobId)?.let {
                    writeJsonFile(checkpointFile(jobId), it)
                }
            }
            legacy.remove(CHECKPOINTS_KEY)
        }

        writeRoot(legacy)
        marker.createNewFile()
    }

    private fun readRoot(): JSONObject {
        if (!file.exists()) return JSONObject()

        return runCatching {
            JSONObject(
                file.inputStream().bufferedReader().use { it.readText() }
            )
        }.getOrElse {
            JSONObject()
        }
    }

    private fun writeRoot(root: JSONObject) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        val json = root.toString()

        tempFile.outputStream().bufferedWriter().use {
            it.write(json)
        }

        if (!tempFile.renameTo(file)) {
            tempFile.delete()
            file.outputStream().bufferedWriter().use {
                it.write(json)
            }
        }
    }

    private fun persistedEquityCurve(
        curve: List<com.algotrader.backtest.EquityPoint>
    ): List<com.algotrader.backtest.EquityPoint> {
        if (curve.size <= MAX_PERSISTED_EQUITY_POINTS) {
            return curve
        }

        val lastIndex = curve.lastIndex
        val result = ArrayList<com.algotrader.backtest.EquityPoint>(
            MAX_PERSISTED_EQUITY_POINTS
        )

        for (i in 0 until MAX_PERSISTED_EQUITY_POINTS) {
            val sourceIndex =
                ((i.toLong() * lastIndex) /
                    (MAX_PERSISTED_EQUITY_POINTS - 1))
                    .toInt()

            result += curve[sourceIndex]
        }

        return result
    }

    companion object {
        private const val FUTURES_ACCOUNTING_KEY = "futuresAccounting"

        private val TERMINAL_STATUSES = setOf(
            Status.COMPLETED,
            Status.FAILED,
            Status.CANCELLED
        )

        private const val FILE_NAME = "backtest_jobs.json"
        private const val LEGACY_MIGRATION_MARKER =
            "backtest_storage_migration_v1.done"

        /*
         * Full-resolution equity is still used by BacktestEngine and
         * PerformanceMetrics. Persistence is capped so large intraday
         * backtests do not create an unnecessarily huge result file.
         */
        private const val MAX_PERSISTED_EQUITY_POINTS = 2_000
    }
}
