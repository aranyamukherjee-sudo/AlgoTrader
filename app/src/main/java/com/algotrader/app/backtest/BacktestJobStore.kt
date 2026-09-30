package com.algotrader.app.backtest
import com.algotrader.backtest.BacktestConfig

import android.content.Context
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.EquityPoint
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
class BacktestJobStore(context: Context) {

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

    data class Job(
        val id: String,
        val status: Status,
        val instrumentSymbol: String,
        val instrumentExchange: String,
        val instrumentCurrency: String,
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

    private val file = context.applicationContext.getFileStreamPath(FILE_NAME)

    @Synchronized
    fun create(
        instrument: Instrument,
        timeframe: Timeframe,
        strategies: List<StrategyConfiguration>,
        initialCapital: Double,
        positionSizing: PositionSizing,
        candleCount: Int
    ): Job {
        require(strategies.isNotEmpty()) {
            "At least one strategy is required for a backtest job"
        }

        val now = Instant.now()

        val job = Job(
            id = UUID.randomUUID().toString(),
            status = Status.QUEUED,
            instrumentSymbol = instrument.symbol,
            instrumentExchange = instrument.exchange,
            instrumentCurrency = instrument.currency,
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
    ): Job? {
        val existing = get(jobId) ?: return null

        val updated = existing.copy(
            status = status ?: existing.status,
            progress = progress?.coerceIn(0, 100) ?: existing.progress,
            currentStep = currentStep ?: existing.currentStep,
            updatedAt = Instant.now(),
            completedAt = completedAt ?: existing.completedAt,
            errorMessage = errorMessage
        )

        writeJob(updated)
        return updated
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
    fun fail(jobId: String, message: String): Job? {
        return update(
            jobId = jobId,
            status = Status.FAILED,
            currentStep = "Failed",
            errorMessage = message
        )
    }

    @Synchronized
    fun cancel(jobId: String): Job? {
        return update(
            jobId = jobId,
            status = Status.CANCELLED,
            currentStep = "Cancelled"
        )
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
            if (key == RESULTS_KEY || key == CANDLES_KEY) return@forEach

            root.optJSONObject(key)?.let { json ->
                runCatching { jobFromJson(json) }
                    .getOrNull()
                    ?.let(jobs::add)
            }
        }

        return jobs.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun saveResults(jobId: String, results: List<BacktestResult>) {
        val root = readRoot()

        val resultsRoot = root.optJSONObject(RESULTS_KEY)
            ?: JSONObject().also { root.put(RESULTS_KEY, it) }

        val array = JSONArray()
        results.forEach { array.put(resultToJson(it)) }

        resultsRoot.put(jobId, array)
        writeRoot(root)
    }

    @Synchronized
    fun getResults(jobId: String): List<BacktestResult> {
        if (!file.exists()) return emptyList()

        val array = readRoot()
            .optJSONObject(RESULTS_KEY)
            ?.optJSONArray(jobId)
            ?: return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                runCatching {
                    add(resultFromJson(array.getJSONObject(index)))
                }
            }
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
                        quantity = trade.getDouble("quantity")
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
                )
            ),
            finalEquity = json.getDouble("finalEquity"),
            trades = trades,
            equityCurve = equityCurve,
            metrics = metrics
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeUnless { it.isNaN() }
    }

    @Synchronized
    fun saveCandles(jobId: String, candles: List<Candle>) {
        val root = readRoot()

        val candlesRoot = root.optJSONObject(CANDLES_KEY)
            ?: JSONObject().also { root.put(CANDLES_KEY, it) }

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

        candlesRoot.put(jobId, array)
        writeRoot(root)
    }

    @Synchronized
    fun getCandles(jobId: String): List<Candle> {
        if (!file.exists()) return emptyList()

        val array = readRoot()
            .optJSONObject(CANDLES_KEY)
            ?.optJSONArray(jobId)
            ?: return emptyList()

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
        val root = readRoot()
        root.put(job.id, jobToJson(job))
        writeRoot(root)
    }

    private fun jobToJson(job: Job): JSONObject {
        return JSONObject().apply {
            put("id", job.id)
            put("status", job.status.name)
            put("instrumentSymbol", job.instrumentSymbol)
            put("instrumentExchange", job.instrumentExchange)
            put("instrumentCurrency", job.instrumentCurrency)
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
            status = Status.valueOf(json.getString("status")),
            instrumentSymbol = json.getString("instrumentSymbol"),
            instrumentExchange = json.getString("instrumentExchange"),
            instrumentCurrency = json.optString("instrumentCurrency", "INR"),
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

            else -> error("Unknown position sizing type: ${json.getString("type")}")
        }
    }

    private fun resultToJson(result: BacktestResult): JSONObject {
        return JSONObject().apply {
            put("strategyName", result.strategyName)
            put("finalEquity", result.finalEquity)

            put(
                "config",
                JSONObject().apply {
                    put("initialCapital", result.config.initialCapital)
                    put(
                        "positionSizing",
                        positionSizingToJson(result.config.positionSizing)
                    )
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
                                put("entryTimestamp", trade.entryTimestamp.toString())
                                put("entryPrice", trade.entryPrice)
                                put("exitIndex", trade.exitIndex)
                                put("exitTimestamp", trade.exitTimestamp.toString())
                                put("exitPrice", trade.exitPrice)
                                put("quantity", trade.quantity)
                                put("grossPnl", trade.grossPnl)
                                put("returnPercent", trade.returnPercent)
                                put("holdingPeriodBars", trade.holdingPeriodBars)
                                put("isWin", trade.isWin)
                            }
                        )
                    }
                }
            )

            put(
                "equityCurve",
                JSONArray().apply {
                    result.equityCurve.forEach { point ->
                        put(
                            JSONObject().apply {
                                put("index", point.index)
                                put("timestamp", point.timestamp.toString())
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
                    put("totalReturnPercent", result.metrics.totalReturnPercent)
                    put("maxDrawdown", result.metrics.maxDrawdown)
                    put("maxDrawdownPercent", result.metrics.maxDrawdownPercent)
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
        file.outputStream().bufferedWriter().use {
            it.write(root.toString())
        }
    }

    companion object {
        private const val FILE_NAME = "backtest_jobs.json"
        private const val RESULTS_KEY = "__results__"
        private const val CANDLES_KEY = "__candles__"
    }
}
