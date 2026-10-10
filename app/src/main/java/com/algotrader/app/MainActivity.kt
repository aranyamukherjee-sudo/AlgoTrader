package com.algotrader.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.Manifest
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.pow
import kotlin.math.roundToInt
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestSample
import com.algotrader.backtest.BacktestLaunchPlan
import com.algotrader.backtest.StrategyBacktestRunner
import com.algotrader.backtest.PositionSizing
import com.algotrader.backtest.ResearchCostModel
import com.algotrader.app.backtest.BacktestJobStore
import com.algotrader.app.backtest.BacktestFormat
import com.algotrader.app.backtest.BacktestWorker
import com.algotrader.strategy.CprEmaTrendStrategy
import com.algotrader.strategy.Signal
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.DonchianEmaTrendStrategy
import com.algotrader.strategyengine.StrategyFactory
import com.algotrader.strategyengine.StrategyRunner
import com.algotrader.strategyengine.StrategyRegistry
import com.algotrader.strategyengine.StrategyConfiguration
import com.algotrader.app.ui.screens.MarketDataScreen
import com.algotrader.app.ui.screens.ExecutionScreen
import com.algotrader.app.ui.screens.BacktestScreen
import com.algotrader.app.ui.screens.StrategiesScreen
import com.algotrader.app.ui.nav.AltrixaBottomNav
import com.algotrader.app.ui.dialogs.FyersAuthDialog
import com.algotrader.app.notification.AltrixaNotifications
import com.algotrader.app.ui.nav.AltrixaDestination
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaChip
import com.algotrader.app.ui.components.altrixaPrimaryButton
import com.algotrader.app.ui.components.altrixaStatusBadge
/**
 * Real-time candle for the selected instrument/timeframe.
 * All values come from the AlgoTrader backend (FYERS-backed) — never fabricated.
 */
data class Candle(
    val timestamp: Long, // epoch seconds
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float
)

data class InstrumentInfo(
    val displayName: String,
    val backendSymbol: String,
    /**
     * Contract size in units for this instrument's index futures/options
     * (1 lot = lotSize units). Backtest quantities for these instruments are
     * always whole lots. Exchanges revise lot sizes periodically, so keep
     * these in sync with the latest NSE/BSE circulars.
     */
    val lotSize: Int = 1,
    val isFutures: Boolean = false,
    val underlyingSymbol: String? = null,
    val expiryEpochSeconds: Long? = null,
    /**
     * Authoritative contract size (units per lot) of THIS futures contract,
     * or null when unknown. Null is the real state of every discovered
     * futures contract until an authoritative source supplies a value.
     *
     * For futures, [lotSize] is NOT a contract size: it is a placeholder (1)
     * and must never be used for backtest accounting. Futures backtests use
     * only this field, and refuse to run while it is null. It is never copied
     * from an index's [lotSize].
     */
    val contractLotSize: Int? = null,
    /** Where [contractLotSize] came from; null exactly when it is unknown. */
    val contractLotSizeSource: String? = null
)

object Instruments {
    // NSE index derivatives lot sizes, effective Jan 2026 series.
    val NIFTY = InstrumentInfo("NIFTY 50", "NSE:NIFTY50-INDEX", lotSize = 65)
    val BANK_NIFTY = InstrumentInfo("BANK NIFTY", "NSE:NIFTYBANK-INDEX", lotSize = 30)
    // BSE SENSEX derivatives lot size (verify against the latest BSE circular).
    val SENSEX = InstrumentInfo("SENSEX", "BSE:SENSEX-INDEX", lotSize = 20)
    val all = listOf(NIFTY, BANK_NIFTY, SENSEX)
}

class MainActivity : Activity() {

    companion object {
        private const val BACKEND_HTTP_BASE = "https://algotrader-backend-kras.onrender.com"
        private const val BACKEND_WS_URL = "wss://algotrader-backend-kras.onrender.com/ws/quotes"
        private const val FYERS_AUTH_NOTIFICATION_CHANNEL = "fyers_auth"
        private const val FYERS_AUTH_NOTIFICATION_ID = 4201
        private const val FYERS_AUTH_REQUIRED = "auth_required"
        private const val FYERS_AUTH_RENEW_ACTION = "com.algotrader.app.FYERS_AUTH_RENEW"
        private val TIMEFRAMES = listOf("5m", "15m", "30m", "1h", "1D")

        // Persistent historical-candle disk cache.
        private const val HISTORY_CACHE_SCHEMA_VERSION = 2
        private const val HISTORY_CACHE_DIR_NAME = "history_cache"
        // Mirrors the backend's own /history cache TTL, so we don't refresh
        // more often than the backend data could actually change.
        private const val DISK_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    private lateinit var content: LinearLayout
    private lateinit var bottomNav: AltrixaBottomNav

    private lateinit var backtestJobStore: BacktestJobStore
    // The selected/viewed job is separate from the set of jobs
    // actually running in WorkManager. Multiple jobs can run in parallel.
    private var selectedBacktestJobId: String? = null
    private var activeBacktestJobId: String? = null
    private var renderedBacktestJobId: String? = null

    // Explicit UI state: Android Back should move from Results
    // to Backtest configuration before leaving the Backtest tab.
    private var isBacktestResultsScreen = false

    private val backtestProgressHandler = Handler(Looper.getMainLooper())
    private var isBacktestScreenVisible = false

    private val backtestProgressRunnable = object : Runnable {
        override fun run() {
            if (!isBacktestScreenVisible) return

            val jobId = activeBacktestJobId
            if (jobId == null) {
                backtestProgressHandler.postDelayed(this, 750L)
                return
            }

            refreshBacktestJob(jobId)

            if (isBacktestScreenVisible) {
                backtestProgressHandler.postDelayed(this, 750L)
            }
        }
    }

    private val liveHandler = Handler(Looper.getMainLooper())
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var quotesWebSocket: WebSocket? = null

    // FYERS authentication state. This is deliberately separate from the
    // WebSocket connection state: a socket can be disconnected for network
    // reasons, while auth_required is a specific backend authentication state.
    private var fyersAuthRequired = false
    private var fyersAuthNotificationShown = false

    // State returned by /auth/fyers/start and retained while the
    // browser completes the FYERS -100 login + MFA flow.
    private var pendingFyersOAuthState: String? = null
    private var fyersOAuthBrowserOpened = false
    private var fyersAuthBanner: TextView? = null

    // Single background thread for all disk cache reads/writes: keeps file
    // access off the main thread and serializes writes to the same key.
    private val diskCacheExecutor = Executors.newSingleThreadExecutor()

    // ---- Home / trading workspace state ----
    private var selectedInstrument: InstrumentInfo = Instruments.NIFTY
    private var selectedFuturesContract: InstrumentInfo? = null

    // Patch 7: reason the futures lot size is unavailable (null while known or not yet requested).
    private var futuresMetadataStatus: String? = null
    private var futuresMetadataRequestInFlight = false
    private var selectedTimeframe = "5m"

    private var selectedSignalStrategyId =
        StrategyRegistry.MOVING_AVERAGE_CROSSOVER

    private val strategyRunner = StrategyRunner()

    private val strategySelectorButtons =
        mutableMapOf<String, Button>()
private var isHomeScreenActive = false

    // Historical candles cached by backend symbol + timeframe.
    // Example key: "NSE:NIFTY50-INDEX|1h"
    private val candlesByInstrument = mutableMapOf<String, List<Candle>>()

    /*
     * P3P10:
     * Authoritative futures intraday source.
     *
     * Futures 15m/30m/1h candles are derived from real 5m candles
     * returned by /futures/history. This prevents re-aggregating an
     * already-aggregated chart series during refresh.
     */
    private val futures5mCandlesBySymbol =
        mutableMapOf<String, List<Candle>>()

    // Prevent duplicate intraday history refreshes while a request is active.
    private val intradayRefreshInFlight = mutableSetOf<String>()

    /*
     * Prevent repeated history redraws for the same live candle bucket.
     * WebSocket ticks can arrive many times per second while the current
     * backend history bucket is still unchanged.
     */
    private val lastIntradayRefreshBucket = mutableMapOf<String, Long>()
    // Latest live LTP received over the websocket, per backend symbol.
    private val liveLtpByInstrument = mutableMapOf<String, Double>()

    // Authoritative FYERS exchange/trade timestamp for the latest live quote.
    // Used by the on-device candle aggregator instead of device wall-clock time.
    private val liveTimestampByInstrument = mutableMapOf<String, Long>()
    // Previous trading day's close used as the header's daily Change/% Change
    // reference. This is intentionally independent of the selected intraday
    // chart timeframe and the live LTP.
    private var homePreviousDayClose: Float = 0f
    private val previousCloseLoadInFlight = mutableSetOf<String>()

    // Views on the Home screen that get updated in place (no full rebuild) when
    // live ticks or history responses arrive, so we don't re-layout on every tick.
    private var headerTitleLabel: TextView? = null
    private var headerPriceLabel: TextView? = null
    private var headerChangeLabel: TextView? = null
    private var headerStatusLabel: TextView? = null
    private var timeRangeLabel: TextView? = null
    private var mainChartView: TradingChartView? = null
    private var volumeChartView: VolumeChartView? = null
    private var rsiChartView: RsiChartView? = null
    private val timeframeButtons = mutableMapOf<String, Button>()
    private val instrumentButtons = mutableMapOf<String, Button>()

    // ---- Phase 2 dashboard: connection badge + OHLC strip (presentation-only) ----
    private var headerConnectionRow: LinearLayout? = null
    private var headerConnectionBadge: TextView? = null
    private var ohlcOpenValue: TextView? = null
    private var ohlcHighValue: TextView? = null
    private var ohlcLowValue: TextView? = null
    private var ohlcCloseValue: TextView? = null

    // ---------------------------------------------------------------------
    // Networking
    // ---------------------------------------------------------------------

    private fun timeframeResolution(
        timeframe: String = selectedTimeframe
    ): String {
        return when (timeframe) {
            "5m" -> "5"
            "15m" -> "15"
            "30m" -> "30"
            "1h" -> "60"
            "1D" -> "D"
            else -> error("Unsupported timeframe: $timeframe")
        }
    }

    /**
     * Loads history for the currently selected instrument + timeframe.
     *
     * Lookup order:
     *   1. In-memory cache (instant, no I/O)
     *   2. On-disk cache (fast, off-thread) — rendered immediately if present;
     *      if it's younger than [DISK_CACHE_TTL_MS] we stop there, otherwise
     *      we also kick a silent background refresh from the backend.
     *   3. Backend "/history" (cold miss — shows the normal loading status).
     */
    /**
     * Ensures the existing daily-history cache is available for the header.
     * Intraday charts still render independently and are never blocked by this.
     */
    private fun ensurePreviousDayCloseLoaded(instrument: InstrumentInfo) {
        val symbol = instrument.backendSymbol
        val dailyKey = "$symbol|1D"

        candlesByInstrument[dailyKey]?.let { daily ->
            if (daily.size >= 1) {
                updatePreviousDayCloseFromDaily(symbol, daily)
                return
            }
        }

        if (!previousCloseLoadInFlight.add(symbol)) {
            return
        }

        diskCacheExecutor.execute {
            val diskEntry = readDiskCache(symbol, "1D")

            runOnUiThread {
                if (diskEntry != null && diskEntry.candles.isNotEmpty()) {
                    candlesByInstrument[dailyKey] = diskEntry.candles
                    previousCloseLoadInFlight.remove(symbol)
                    updatePreviousDayCloseFromDaily(symbol, diskEntry.candles)

                    val age = System.currentTimeMillis() - diskEntry.savedAt
                    if (age >= DISK_CACHE_TTL_MS) {
                        fetchHistoryFromBackend(
                            instrument = instrument,
                            requestedSymbol = symbol,
                            requestedTimeframe = "1D",
                            cacheKey = dailyKey,
                            showLoadingStatus = false
                        )
                    }
                } else {
                    fetchHistoryFromBackend(
                        instrument = instrument,
                        requestedSymbol = symbol,
                        requestedTimeframe = "1D",
                        cacheKey = dailyKey,
                        showLoadingStatus = false
                    )
                }
            }
        }
    }

    /**
     * Updates the header baseline from daily candles.
     * The latest daily candle is the current trading day when present, so
     * the preceding daily candle is the previous trading day's close.
     */
    /**
     * Sets the header baseline to the latest completed trading day's close.
     *
     * We explicitly compare the candle's IST date with today's IST date.
     * This avoids assuming that candles[size - 2] is always yesterday.
     *
     * Header calculation:
     * Change = Live LTP - Previous Trading Day Close
     */
    private fun updatePreviousDayCloseFromDaily(
        symbol: String,
        candles: List<Candle>
    ) {
        if (candles.isEmpty()) {
            return
        }

        val ist = java.time.ZoneId.of("Asia/Kolkata")
        val today = java.time.LocalDate.now(ist)

        val previousTradingDay = candles
            .asSequence()
            .sortedByDescending { it.timestamp }
            .mapNotNull { candle ->
                val candleDate = try {
                    java.time.Instant.ofEpochSecond(candle.timestamp)
                        .atZone(ist)
                        .toLocalDate()
                } catch (_: Exception) {
                    null
                }

                if (candleDate != null && candleDate.isBefore(today)) {
                    candle
                } else {
                    null
                }
            }
            .firstOrNull()

        if (previousTradingDay == null) {
            return
        }

        homePreviousDayClose = previousTradingDay.close

        if (isHomeScreenActive &&
            selectedInstrument.backendSymbol == symbol
        ) {
            updateHeaderPrice()
        }
    }


    /**
     * Phase 3 Patch 5:
     * Discover the actual nearest NIFTY futures contract from FYERS.
     *
     * The backend returns exact contract symbols and expiry timestamps.
     * Android never constructs a futures symbol itself.
     */
    private fun discoverNiftyFuturesContract() {
        val request = Request.Builder()
            .url(
                "$BACKEND_HTTP_BASE/futures/chain" +
                    "?symbol=${android.net.Uri.encode("NSE:NIFTY50-INDEX")}"
            )
            .build()

        wsClient.newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(
                call: okhttp3.Call,
                e: java.io.IOException
            ) {
                // Existing index functionality remains unaffected.
            }

            override fun onResponse(
                call: okhttp3.Call,
                response: Response
            ) {
                response.use {
                    if (!response.isSuccessful) return

                    try {
                        val root = JSONObject(
                            response.body?.string() ?: return
                        )

                        if (backendRequiresFyersAuth(root)) {
                            handleFyersAuthRequired()
                            return
                        }

                        val responseObject =
                            root.optJSONObject("response") ?: return

                        val data =
                            responseObject.optJSONArray("data") ?: return

                        val contractPattern =
                            Regex(
                                "^NSE:NIFTY\\d{2}" +
                                    "(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)" +
                                    "FUT$"
                            )

                        val contracts = mutableListOf<InstrumentInfo>()

                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val symbol = item.optString("symbol").trim().uppercase()
                            if (!contractPattern.matches(symbol)) continue

                            // FYERS chain may encode expiry as either a JSON
                            // number or a numeric string. Normalize both forms
                            // explicitly; malformed/missing expiry remains rejected.
                            val expiry = when (val rawExpiry = item.opt("expiry")) {
                                is Number -> rawExpiry.toLong()
                                is String -> rawExpiry.trim().toLongOrNull() ?: 0L
                                else -> 0L
                            }
                            if (expiry <= 0L) continue

                            contracts.add(
                                InstrumentInfo(
                                    displayName = "NIFTY FUT",
                                    backendSymbol = symbol,
                                    // Exact futures lot size is deliberately
                                    // not inferred from the chain. `lotSize`
                                    // stays a non-accounting placeholder;
                                    // `contractLotSize` stays null (unknown)
                                    // until an authoritative source exists.
                                    lotSize = 1,
                                    isFutures = true,
                                    underlyingSymbol = "NSE:NIFTY50-INDEX",
                                    expiryEpochSeconds = expiry
                                )
                            )
                        }

                        val nearest = contracts
                            .filter {
                                it.expiryEpochSeconds != null &&
                                    it.expiryEpochSeconds > 0L
                            }
                            .minByOrNull {
                                it.expiryEpochSeconds!!
                            }
                            ?: return

                        runOnUiThread {
                            selectedFuturesContract = nearest

                            // If Home is already visible, rebuild it so the
                            // discovered exact futures contract appears in
                            // the selector immediately.
                            if (isHomeScreenActive) {
                                if (selectedInstrument.isFutures) {
                                    selectedInstrument = nearest
                                }
                                showHome()
                            } else if (selectedInstrument.isFutures) {
                                // Preserve the discovered contract even when
                                // Home is not currently visible.
                                selectedInstrument = nearest
                            }
                        }

                        subscribeToFuturesContract(nearest)
                        fetchFuturesContractMetadata(nearest)
                    } catch (_: Exception) {
                        // Preserve existing index market-data behavior.
                    }
                }
            }
        })
    }

    /**
     * Phase 3 Patch 7: request the authoritative lot size / expiry of the
     * exact discovered contract from the backend. The result is validated by
     * [FuturesContractMetadata]; on any failure the lot size stays unknown
     * and futures backtesting stays unavailable. Nothing is defaulted.
     */
    private fun fetchFuturesContractMetadata(contract: InstrumentInfo) {
        if (futuresMetadataRequestInFlight) return
        futuresMetadataRequestInFlight = true

        val request = Request.Builder()
            .url(
                "$BACKEND_HTTP_BASE/futures/contract-metadata" +
                    "?symbol=${android.net.Uri.encode(contract.backendSymbol)}"
            )
            .get()
            .build()

        fun finish(outcome: com.algotrader.app.backtest.FuturesContractMetadata.Outcome) {
            runOnUiThread {
                futuresMetadataRequestInFlight = false
                val current = selectedFuturesContract
                // Ignore a result for a contract that is no longer the selected one.
                if (current == null || current.backendSymbol != contract.backendSymbol) {
                    return@runOnUiThread
                }
                val updated = com.algotrader.app.backtest.FuturesContractMetadata
                    .applyTo(current, outcome)
                selectedFuturesContract = updated
                if (selectedInstrument.isFutures &&
                    selectedInstrument.backendSymbol == updated.backendSymbol
                ) {
                    selectedInstrument = updated
                }
                futuresMetadataStatus =
                    (outcome as? com.algotrader.app.backtest.FuturesContractMetadata.Outcome.Unavailable)
                        ?.reason

                // Phase 3 Patch 8: metadata arrives asynchronously after the
                // Backtest configuration may already have been rendered.
                // Redraw only when Backtest is still the active screen so the
                // authoritative contract lot size becomes visible immediately.
                if (isBacktestScreenVisible) {
                    renderBacktestConfiguration()
                }
            }
        }

        wsClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                finish(
                    com.algotrader.app.backtest.FuturesContractMetadata.Outcome.Unavailable(
                        "Could not reach the metadata service."
                    )
                )
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val body = if (response.isSuccessful) response.body?.string() else null
                    finish(
                        com.algotrader.app.backtest.FuturesContractMetadata.parse(
                            body,
                            contract.backendSymbol,
                            contract.expiryEpochSeconds
                        )
                    )
                }
            }
        })
    }

    /**
     * Add the exact discovered futures contract to the backend's running
     * WebSocket subscription.
     */
    private fun subscribeToFuturesContract(contract: InstrumentInfo) {
        val requestUrl =
            "$BACKEND_HTTP_BASE/futures/subscribe" +
                "?symbol=${android.net.Uri.encode(contract.backendSymbol)}"

        android.util.Log.d(
            "ALTRIXA_P3P10",
            "FUTURES SUBSCRIBE REQUEST symbol=${contract.backendSymbol} url=$requestUrl"
        )

        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .build()

        wsClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(
                call: okhttp3.Call,
                e: java.io.IOException
            ) {
                android.util.Log.e(
                    "ALTRIXA_P3P10",
                    "FUTURES SUBSCRIBE FAILURE symbol=${contract.backendSymbol}: ${e.message}",
                    e
                )
            }

            override fun onResponse(
                call: okhttp3.Call,
                response: Response
            ) {
                response.use {
                    val body = response.body?.string().orEmpty()

                    android.util.Log.d(
                        "ALTRIXA_P3P10",
                        "FUTURES SUBSCRIBE RESPONSE " +
                            "symbol=${contract.backendSymbol} " +
                            "http=${response.code} " +
                            "successful=${response.isSuccessful} " +
                            "body=$body"
                    )
                }
            }
        })
    }

    /**
     * FYERS F&O intraday history is requested in bounded windows.
     * Verified futures history works through the required 100-day window.
     */
    private fun futuresHistoryRangeDays(): Long = 100L

    /*
     * P3P10: Aggregate real futures 5m candles into the selected
     * intraday timeframe using the NSE 09:15 IST session anchor.
     *
     * Historical completed sessions remain bounded to 09:15-15:30 IST.
     * Today's live futures candles are allowed beyond 15:30 because the
     * backend merges the current live 5m feed into the history response.
     */
    private fun aggregateFuturesIntradayCandles(
        candles: List<Candle>,
        timeframe: String
    ): List<Candle> {
        if (candles.isEmpty() || timeframe == "5m") {
            return candles.sortedBy { it.timestamp }
        }

        val interval = timeframeToSeconds(timeframe)

        if (interval <= 5L * 60L) {
            return candles.sortedBy { it.timestamp }
        }

        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val today = java.time.LocalDate.now(zone)
        val grouped = sortedMapOf<Long, MutableList<Candle>>()

        for (candle in candles.sortedBy { it.timestamp }) {
            val local =
                java.time.Instant
                    .ofEpochSecond(candle.timestamp)
                    .atZone(zone)

            val date = local.toLocalDate()

            val sessionStart =
                date
                    .atTime(9, 15)
                    .atZone(zone)
                    .toEpochSecond()

            val sessionEnd =
                date
                    .atTime(15, 30)
                    .atZone(zone)
                    .toEpochSecond()

            /*
             * Completed historical days use the normal NSE session.
             *
             * Today is special: the backend may contain the live 5m
             * futures candle after 15:30, so retain today's candles
             * through the current backend response.
             */
            if (candle.timestamp < sessionStart) {
                continue
            }

            if (date != today && candle.timestamp >= sessionEnd) {
                continue
            }

            val offset =
                candle.timestamp - sessionStart

            val bucket =
                sessionStart +
                    (offset / interval) * interval

            grouped
                .getOrPut(bucket) { mutableListOf() }
                .add(candle)
        }

        return grouped.map { (bucket, group) ->
            val first = group.first()
            val last = group.last()

            Candle(
                timestamp = bucket,
                open = first.open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = last.close,
                volume = group
                    .sumOf { it.volume.toDouble() }
                    .toFloat()
            )
        }
    }

    /*
     * P3P10: Build one real daily futures candle from the underlying
     * 5m candles. The candle timestamp is anchored to 09:15 IST so the
     * daily bar remains aligned with the NSE trading session.
     */
    private fun aggregateFuturesDailyCandles(
        candles: List<Candle>
    ): List<Candle> {
        if (candles.isEmpty()) {
            return emptyList()
        }

        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val grouped = sortedMapOf<java.time.LocalDate, MutableList<Candle>>()

        for (candle in candles.sortedBy { it.timestamp }) {
            val local =
                java.time.Instant
                    .ofEpochSecond(candle.timestamp)
                    .atZone(zone)

            val date = local.toLocalDate()

            val sessionStart =
                date
                    .atTime(9, 15)
                    .atZone(zone)
                    .toEpochSecond()

            val sessionEnd =
                date
                    .atTime(15, 30)
                    .atZone(zone)
                    .toEpochSecond()

            /*
             * Keep the same session policy as intraday aggregation:
             * historical days are limited to the completed NSE session;
             * today's live candle may extend beyond 15:30.
             */
            val today = java.time.LocalDate.now(zone)

            if (candle.timestamp < sessionStart) {
                continue
            }

            if (date != today && candle.timestamp >= sessionEnd) {
                continue
            }

            grouped
                .getOrPut(date) { mutableListOf() }
                .add(candle)
        }

        return grouped.map { (date, group) ->
            val first = group.first()
            val last = group.last()

            Candle(
                timestamp = date
                    .atTime(9, 15)
                    .atZone(zone)
                    .toEpochSecond(),
                open = first.open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = last.close,
                volume = group
                    .sumOf { it.volume.toDouble() }
                    .toFloat()
            )
        }
    }

    private fun normalizeFuturesCandles(
        candles: List<Candle>,
        timeframe: String
    ): List<Candle> {
        return when (timeframe) {
            "5m" ->
                candles.sortedBy { it.timestamp }

            "15m", "30m", "1h" ->
                aggregateFuturesIntradayCandles(
                    candles,
                    timeframe
                )

            "1D" ->
                aggregateFuturesDailyCandles(candles)

            else ->
                candles.sortedBy { it.timestamp }
        }
    }

    private fun futuresHistoryUrl(
        symbol: String,
        resolution: String
    ): String {
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        val today =
            java.time.LocalDate.now(ist)

        val rangeTo =
            java.time.Instant.now().epochSecond

        val rangeFrom =
            today
                .minusDays(futuresHistoryRangeDays() - 1L)
                .atStartOfDay(ist)
                .toEpochSecond()

        /*
         * Futures 1D is derived locally from the real 5m session candles.
         * Request 5m source data instead of FYERS resolution=D.
         */
        val requestResolution =
            if (resolution == "D") "5" else resolution

        return "$BACKEND_HTTP_BASE/futures/history" +
            "?symbol=${android.net.Uri.encode(symbol)}" +
            "&resolution=${android.net.Uri.encode(requestResolution)}" +
            "&range_from=$rangeFrom" +
            "&range_to=$rangeTo" +
            "&date_format=0" +
            "&include_oi=0" +
            "&include_greeks=0"
    }

    private fun loadHistoryForSelected() {
        val instrument = selectedInstrument
        val requestedSymbol = instrument.backendSymbol
        val requestedTimeframe = selectedTimeframe
        val cacheKey = "$requestedSymbol|$requestedTimeframe"

        // 1) Memory cache — render immediately.
        // Futures must still refresh from the backend because today's
        // authoritative 5-minute candles are merged there from the live feed.
        candlesByInstrument[cacheKey]?.let { cached ->
            if (cached.isNotEmpty()) {
                setHeaderStatus(null)
                renderHomeData(cached)
                if (requestedTimeframe != "1D") {
                    ensurePreviousDayCloseLoaded(instrument)
                }

                if (instrument.isFutures) {
                    fetchHistoryFromBackend(
                        instrument = instrument,
                        requestedSymbol = requestedSymbol,
                        requestedTimeframe = requestedTimeframe,
                        cacheKey = cacheKey,
                        showLoadingStatus = false
                    )
                }

                return
            }
        }

        // 2) Disk cache — read off the main thread.
        diskCacheExecutor.execute {
            val diskEntry = readDiskCache(requestedSymbol, requestedTimeframe)

            runOnUiThread {
                if (!isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                    return@runOnUiThread
                }

                if (diskEntry != null) {
                    // Populate the memory cache too, so switching away and
                    // back is instant without touching disk again.
                    candlesByInstrument[cacheKey] = diskEntry.candles
                    setHeaderStatus(null)
                    renderHomeData(diskEntry.candles)
                    if (requestedTimeframe != "1D") {
                        ensurePreviousDayCloseLoaded(instrument)
                    }

                    val age = System.currentTimeMillis() - diskEntry.savedAt

                    if (instrument.isFutures || age >= DISK_CACHE_TTL_MS) {
                        // Futures must always refresh quietly because the disk
                        // cache can contain only completed historical candles
                        // while today's live 5-minute candle is maintained by
                        // the backend live-feed accumulator.
                        fetchHistoryFromBackend(
                            instrument = instrument,
                            requestedSymbol = requestedSymbol,
                            requestedTimeframe = requestedTimeframe,
                            cacheKey = cacheKey,
                            showLoadingStatus = false
                        )
                    }
                } else {
                    // 3) True cold miss (no memory, no valid disk cache).
                    fetchHistoryFromBackend(
                        instrument = instrument,
                        requestedSymbol = requestedSymbol,
                        requestedTimeframe = requestedTimeframe,
                        cacheKey = cacheKey,
                        showLoadingStatus = true
                    )
                }
            }
        }
    }

    /**
     * Fetches history from the backend "/history" endpoint. On success,
     * updates the in-memory cache, overwrites the on-disk cache, and
     * re-renders the chart. On failure, leaves whatever is already on
     * screen untouched and only surfaces the existing non-blocking status
     * message.
     */
    /**
     * Marks the backend as requiring a fresh FYERS access token.
     *
     * This does not attempt token refresh. FYERS refresh-token API is not
     * available for this application, so the eventual flow will be:
     * auth_required -> user generates fresh token -> user submits token.
     */
    private fun handleFyersAuthRequired() {
        runOnUiThread {
            val wasAlreadyRequired = fyersAuthRequired
            fyersAuthRequired = true

            headerStatusLabel?.let {
                it.text = "FYERS Authentication Required"
                it.setTextColor(AltrixaColors.warning)
            }

            fyersAuthBanner?.let {
                it.visibility = View.VISIBLE
            }

            if (!wasAlreadyRequired || !fyersAuthNotificationShown) {
                showFyersAuthNotification()
            }
        }
    }

    private fun clearFyersAuthRequired() {
        runOnUiThread {
            fyersAuthRequired = false
            fyersAuthNotificationShown = false

            fyersAuthBanner?.let {
                it.visibility = View.GONE
            }

            if (headerStatusLabel?.text?.toString() == "FYERS Authentication Required") {
                headerStatusLabel?.text = ""
            }
        }
    }

    /**
     * Handles the browser -> ALTRIXA FYERS OAuth callback.
     *
     * Only the callback status is consumed by Android.
     * The FYERS authorization code and access token remain server-side.
     */
    private fun handleFyersOAuthCallback(uri: android.net.Uri?) {
        if (uri == null ||
            uri.scheme != "altrixa" ||
            uri.host != "fyers-auth"
        ) {
            return
        }

        val status = uri.getQueryParameter("status").orEmpty()

        if (status == "success") {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    "FYERS authentication renewed.",
                    android.widget.Toast.LENGTH_LONG
                ).show()

                clearFyersAuthRequired()

                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager

                notificationManager.cancel(FYERS_AUTH_NOTIFICATION_ID)

                connectQuotesWebSocket()
            }

            return
        }

        if (status == "error") {
            val reason =
                uri.getQueryParameter("reason")
                    ?.replace('_', ' ')
                    ?.take(120)
                    .orEmpty()

            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (reason.isNotBlank()) {
                        "FYERS authentication failed: $reason"
                    } else {
                        "FYERS authentication failed."
                    },
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Starts the server-side FYERS OAuth browser flow.
     *
     * Android never receives the FYERS App Secret or access token.
     * The backend generates the short-lived OAuth URL and performs
     * the authorization-code exchange server-side.
     */
    private fun startFyersOAuthRenewal() {
        runOnUiThread {
            android.widget.Toast.makeText(
                this,
                "Opening FYERS authentication…",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        val request = Request.Builder()
            .url("$BACKEND_HTTP_BASE/auth/fyers/start")
            .get()
            .build()

        OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(
                call: okhttp3.Call,
                e: java.io.IOException
            ) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Could not start FYERS authentication. Check your connection.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(
                call: okhttp3.Call,
                response: okhttp3.Response
            ) {
                response.use { res ->

                    val body = res.body?.string().orEmpty()

                    if (!res.isSuccessful) {
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "FYERS authentication could not be started.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        return
                    }

                    try {
                        val root = JSONObject(body)

                        val authUrl =
                            root.optString("auth_url", "").trim()

                        val state =
                            root.optString("state", "").trim()

                        if (
                            root.optString("status") != "ok" ||
                            authUrl.isEmpty() ||
                            state.isEmpty()
                        ) {
                            throw IllegalStateException(
                                "FYERS authentication session unavailable"
                            )
                        }

                        pendingFyersOAuthState = state
                        fyersOAuthBrowserOpened = true

                        runOnUiThread {
                            try {
                                val browserIntent =
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(authUrl)
                                    )

                                startActivity(browserIntent)

                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "Complete FYERS login and MFA, then return to ALTRIXA.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()

                            } catch (_: Exception) {
                                pendingFyersOAuthState = null
                                fyersOAuthBrowserOpened = false

                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "No browser is available to open FYERS authentication.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }

                        }

                    } catch (_: Exception) {
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "FYERS authentication could not be started.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        })
    }

    /**
     * Shows the temporary FYERS authorization-code entry dialog.
     *
     * The auth code is sent directly to the backend over HTTPS and
     * is never persisted locally or displayed after submission.
     */
    private fun showFyersAuthCodeDialog() {
        val state = pendingFyersOAuthState

        if (state.isNullOrBlank()) {
            android.widget.Toast.makeText(
                this,
                "No active FYERS authentication session. Start renewal again.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        FyersAuthDialog(this) { authCode, authDialog ->
            exchangeFyersAuthCode(
                authCode = authCode,
                state = state,
                dialog = authDialog
            )
        }.show()
    }

    private fun exchangeFyersAuthCode(
        authCode: String,
        state: String,
        dialog: FyersAuthDialog
    ) {
        val json = JSONObject().apply {
            put("auth_code", authCode)
            put("state", state)
        }

        val requestBody =
            json.toString().toRequestBody(
                "application/json; charset=utf-8".toMediaType()
            )

        val request = Request.Builder()
            .url("$BACKEND_HTTP_BASE/auth/fyers/exchange-code")
            .post(requestBody)
            .build()

        OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(
                call: okhttp3.Call,
                e: java.io.IOException
            ) {
                runOnUiThread {
                    dialog.showError(
                        "Could not contact ALTRIXA server. Check your connection."
                    )
                }
            }

            override fun onResponse(
                call: okhttp3.Call,
                response: okhttp3.Response
            ) {
                response.use { res ->

                    val body = res.body?.string().orEmpty()

                    try {
                        val root = JSONObject(body)
                        val status = root.optString("status", "")
                        val message =
                            root.optString("message", "").trim()

                        if (res.isSuccessful && status == "ok") {
                            pendingFyersOAuthState = null
                            fyersOAuthBrowserOpened = false

                            runOnUiThread {
                                dialog.showSuccessAndDismiss(
                                    "FYERS authentication renewed."
                                )

                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "FYERS authentication renewed.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()

                                clearFyersAuthRequired()

                                val notificationManager =
                                    getSystemService(Context.NOTIFICATION_SERVICE)
                                        as NotificationManager

                                notificationManager.cancel(
                                    FYERS_AUTH_NOTIFICATION_ID
                                )

                                connectQuotesWebSocket()
                            }

                            return
                        }

                        runOnUiThread {
                            dialog.showError(
                                if (message.isNotBlank()) {
                                    "FYERS authentication failed: $message"
                                } else {
                                    "FYERS authentication failed."
                                }
                            )
                        }

                    } catch (_: Exception) {
                        runOnUiThread {
                            dialog.showError("FYERS authentication failed.")
                        }
                    }
                }
            }
        })
    }


    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            4202
        )
    }

    private fun showFyersAuthNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                4202
            )
            // The permission callback will retry the notification.
            return
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FYERS_AUTH_NOTIFICATION_CHANNEL,
                "FYERS Authentication",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Authentication alerts for ALTRIXA market data"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = FYERS_AUTH_RENEW_ACTION
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            4203,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
        )

        val notification = android.app.Notification.Builder(
            this,
            FYERS_AUTH_NOTIFICATION_CHANNEL
        )
            .setSmallIcon(R.drawable.ic_stat_altrixa)
            .setColor(AltrixaColors.warning)
            .setLargeIcon(AltrixaNotifications.brandLargeIcon(this))
            .setContentTitle("ALTRIXA — FYERS token expired")
            .setContentText("Open ALTRIXA to refresh FYERS authentication.")
            .setStyle(
                android.app.Notification.BigTextStyle().bigText(
                    "Your FYERS access token requires renewal. Open ALTRIXA to generate and update a fresh token."
                )
            )
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(FYERS_AUTH_NOTIFICATION_ID, notification)
        fyersAuthNotificationShown = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 4202 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
            fyersAuthRequired
        ) {
            showFyersAuthNotification()
        }
    }

    private fun backendRequiresFyersAuth(root: JSONObject): Boolean {
        return root.optBoolean("auth_required", false) ||
            root.optString("fyers", "") == FYERS_AUTH_REQUIRED
    }

    private fun fetchHistoryFromBackend(
        instrument: InstrumentInfo,
        requestedSymbol: String,
        requestedTimeframe: String,
        cacheKey: String,
        showLoadingStatus: Boolean
    ) {
        /*
         * Bind this request to the timeframe that created it.
         *
         * selectedTimeframe is mutable and can change while an HTTP
         * request is in flight. requestedTimeframe is the selection
         * associated with this request.
         */
        /*
         * P3P10:
         * Futures intraday history always comes from the authoritative
         * 5m backend series. Larger timeframes are derived locally.
         */
        val resolution =
            if (
                instrument.isFutures &&
                requestedTimeframe != "1D"
            ) {
                "5"
            } else {
                timeframeResolution(requestedTimeframe)
            }

        if (showLoadingStatus) {
            setHeaderStatus("Loading ${instrument.displayName}…")
        }

        val requestUrl =
            if (instrument.isFutures) {
                futuresHistoryUrl(
                    symbol = requestedSymbol,
                    resolution = resolution
                )
            } else {
                val historyDays = 365
                "$BACKEND_HTTP_BASE/history" +
                    "?symbol=$requestedSymbol" +
                    "&resolution=$resolution&days=$historyDays"
            }

        if (
            instrument.isFutures &&
            showLoadingStatus &&
            isStillCurrentSelection(requestedSymbol, requestedTimeframe)
        ) {
            setHeaderStatus(
                "DIAG URL ${requestUrl.substringAfter("$BACKEND_HTTP_BASE")}"
            )
        }

        val request = Request.Builder()
            .url(requestUrl)
            .build()

        wsClient.newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e(
                    "ALTRIXA_P3P10",
                    "HISTORY FAILURE " +
                        "symbol=$requestedSymbol " +
                        "requestedTf=$requestedTimeframe " +
                        "resolution=$resolution " +
                        "error=${e.javaClass.simpleName}: ${e.message}",
                    e
                )

                runOnUiThread {
                    if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                        setHeaderStatus(
                            "P3P10 FAILURE ${requestedTimeframe} " +
                                "${e.javaClass.simpleName}"
                        )
                    }
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    android.util.Log.d(
                        "ALTRIXA_P3P10",
                        "HISTORY RESPONSE " +
                            "symbol=$requestedSymbol " +
                            "requestedTf=$requestedTimeframe " +
                            "resolution=$resolution " +
                            "http=${response.code} " +
                            "successful=${response.isSuccessful}"
                    )

                    runOnUiThread {
                        if (isStillCurrentSelection(
                                requestedSymbol,
                                requestedTimeframe
                            )
                        ) {
                            setHeaderStatus(
                                "P3P10 RESPONSE ${requestedTimeframe} " +
                                    "HTTP ${response.code}"
                            )
                        }
                    }

                    val body = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        runOnUiThread {
                            if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                setHeaderStatus("Backend error (HTTP ${response.code})")
                            }
                        }
                        return
                    }

                    try {
                        val root = JSONObject(body)

                        if (backendRequiresFyersAuth(root)) {
                            handleFyersAuthRequired()
                            return
                        }

                        val array =
                            root.optJSONArray("candles")
                                ?: root
                                    .optJSONObject("response")
                                    ?.optJSONArray("candles")
                                ?: root
                                    .optJSONObject("response")
                                    ?.optJSONArray("data")

                        if (instrument.isFutures) {
                            val rawLastCandle =
                                array?.optJSONArray(
                                    (array.length() - 1).coerceAtLeast(0)
                                )
                            val rawLastTimestamp =
                                rawLastCandle?.optLong(0, 0L) ?: 0L

                            val rawDiag =
                                "DIAG REQUEST_RESPONSE " +
                                "TF=$requestedTimeframe " +
                                "RES=$resolution " +
                                "RAW=${array?.length() ?: 0} " +
                                "RAW_LAST=$rawLastTimestamp"

                            android.util.Log.d(
                                "ALTRIXA_P3P10",
                                rawDiag
                            )

                            if (
                                isStillCurrentSelection(
                                    requestedSymbol,
                                    requestedTimeframe
                                )
                            ) {
                                runOnUiThread {
                                    if (
                                        isStillCurrentSelection(
                                            requestedSymbol,
                                            requestedTimeframe
                                        )
                                    ) {
                                        setHeaderStatus(rawDiag)
                                    }
                                }
                            }
                        }

                        if (array == null || array.length() == 0) {
                            val backendStatus = root.optString("status", "unknown")
                            val backendMessage =
                                root.optString(
                                    "message",
                                    root.optJSONObject("response")
                                        ?.optString("message", "")
                                        ?: ""
                                )

                            runOnUiThread {
                                if (isStillCurrentSelection(
                                        requestedSymbol,
                                        requestedTimeframe
                                    )
                                ) {
                                    setHeaderStatus(
                                        "DIAG BACKEND ${requestedTimeframe} " +
                                            "status=$backendStatus " +
                                            "message=${backendMessage.ifBlank { "no candles" }}"
                                    )
                                }
                            }
                            return
                        }

                        val candles = mutableListOf<Candle>()

                        for (i in 0 until array.length()) {
                            val candle = array.optJSONArray(i) ?: continue

                            if (candle.length() >= 5) {
                                candles.add(
                                    Candle(
                                        timestamp = candle.optLong(0),
                                        open = candle.getDouble(1).toFloat(),
                                        high = candle.getDouble(2).toFloat(),
                                        low = candle.getDouble(3).toFloat(),
                                        close = candle.getDouble(4).toFloat(),
                                        volume = if (candle.length() >= 6) {
                                            candle.optDouble(5, 0.0).toFloat()
                                        } else 0f
                                    )
                                )
                            }
                        }

                        if (candles.isEmpty()) {
                            runOnUiThread {
                                if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                    setHeaderStatus("No candle data available")
                                }
                            }
                            return
                        }

                        // Treat backend history as untrusted input too.
                        // Never allow malformed candles into memory or disk
                        // cache, even if they arrived from a successful HTTP
                        // response.
                        val validCandles = candles.filter(::isValidCachedCandle).toMutableList()

                        if (validCandles.isEmpty()) {
                            runOnUiThread {
                                if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                    setHeaderStatus("Invalid candle data received")
                                }
                            }
                            return
                        }

                        // FYERS history should be chronological, but normalize
                        // the series before caching so a reversed response can
                        // never produce a reversed chart/date range.
                        validCandles.sortBy { it.timestamp }

                        // Reject duplicate timestamps before the response can
                        // become a persistent cache entry.
                        if (validCandles.map { it.timestamp }.toSet().size != validCandles.size) {
                            runOnUiThread {
                                if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                    setHeaderStatus("Invalid candle data received")
                                }
                            }
                            return
                        }

                        /*
                         * P3P10:
                         * Keep the backend's real 5m futures series as the
                         * source of truth for subsequent refreshes.
                         */
                        if (
                            instrument.isFutures &&
                            requestedTimeframe != "1D"
                        ) {
                            futures5mCandlesBySymbol[
                                requestedSymbol
                            ] = validCandles
                        }

                        val todayRawCount =
                            if (instrument.isFutures) {
                                val zone =
                                    java.time.ZoneId.of("Asia/Kolkata")
                                val today =
                                    java.time.LocalDate.now(zone)

                                validCandles.count {
                                    java.time.Instant
                                        .ofEpochSecond(it.timestamp)
                                        .atZone(zone)
                                        .toLocalDate() == today
                                }
                            } else {
                                0
                            }

                        val todayRawFirst =
                            if (instrument.isFutures) {
                                val zone =
                                    java.time.ZoneId.of("Asia/Kolkata")
                                val today =
                                    java.time.LocalDate.now(zone)

                                validCandles
                                    .filter {
                                        java.time.Instant
                                            .ofEpochSecond(it.timestamp)
                                            .atZone(zone)
                                            .toLocalDate() == today
                                    }
                                    .minOfOrNull { it.timestamp } ?: 0L
                            } else {
                                0L
                            }

                        val todayRawLast =
                            if (instrument.isFutures) {
                                val zone =
                                    java.time.ZoneId.of("Asia/Kolkata")
                                val today =
                                    java.time.LocalDate.now(zone)

                                validCandles
                                    .filter {
                                        java.time.Instant
                                            .ofEpochSecond(it.timestamp)
                                            .atZone(zone)
                                            .toLocalDate() == today
                                    }
                                    .maxOfOrNull { it.timestamp } ?: 0L
                            } else {
                                0L
                            }

                        if (instrument.isFutures) {
                            android.util.Log.d(
                                "ALTRIXA_P3P10",
                                "DIAG BEFORE_NORMALIZE " +
                                    "symbol=$requestedSymbol " +
                                    "requestedTf=$requestedTimeframe " +
                                    "resolution=$resolution " +
                                    "validCount=${validCandles.size} " +
                                    "first=${validCandles.firstOrNull()?.timestamp ?: 0L} " +
                                    "last=${validCandles.lastOrNull()?.timestamp ?: 0L}"
                            )
                        }

                        val renderedCandles =
                            if (instrument.isFutures) {
                                normalizeFuturesCandles(
                                    validCandles,
                                    requestedTimeframe
                                )
                            } else {
                                validCandles
                            }

                        val todayRenderedCount =
                            if (instrument.isFutures) {
                                val zone =
                                    java.time.ZoneId.of("Asia/Kolkata")
                                val today =
                                    java.time.LocalDate.now(zone)

                                renderedCandles.count {
                                    java.time.Instant
                                        .ofEpochSecond(it.timestamp)
                                        .atZone(zone)
                                        .toLocalDate() == today
                                }
                            } else {
                                0
                            }

                        if (
                            instrument.isFutures &&
                            isStillCurrentSelection(
                                requestedSymbol,
                                requestedTimeframe
                            )
                        ) {
                            val diagnostic =
                                "P3P10 " +
                                "REQ=$requestedTimeframe " +
                                "RES=$resolution " +
                                "RAW=${validCandles.size} " +
                                "TODAY_RAW=${todayRawCount} " +
                                "RAW_FIRST=${todayRawFirst} " +
                                "RAW_LAST=${todayRawLast} " +
                                "RENDERED=${renderedCandles.size} " +
                                "TODAY_RENDERED=${todayRenderedCount}"

                            runOnUiThread {
                                if (
                                    isStillCurrentSelection(
                                        requestedSymbol,
                                        requestedTimeframe
                                    )
                                ) {
                                    setHeaderStatus(diagnostic)
                                }
                            }
                        }

                        if (renderedCandles.isEmpty()) {
                            runOnUiThread {
                                if (
                                    isStillCurrentSelection(
                                        requestedSymbol,
                                        requestedTimeframe
                                    )
                                ) {
                                    setHeaderStatus(
                                        "No valid candle data available"
                                    )
                                }
                            }
                            return
                        }

                        // Cache the exact series rendered by this timeframe.
                        candlesByInstrument[cacheKey] =
                            renderedCandles

                        // Persist to disk, atomically, off the main thread.
                        val savedAt = System.currentTimeMillis()
                        diskCacheExecutor.execute {
                            writeDiskCache(
                                requestedSymbol,
                                requestedTimeframe,
                                validCandles,
                                savedAt
                            )
                        }

                        runOnUiThread {
                            previousCloseLoadInFlight.remove(requestedSymbol)

                            if (requestedTimeframe == "1D") {
                                updatePreviousDayCloseFromDaily(
                                    requestedSymbol,
                                    renderedCandles
                                )
                            }

                            if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                setHeaderStatus(null)

                                val firstTs =
                                    renderedCandles.firstOrNull()?.timestamp ?: 0L
                                val lastTs =
                                    renderedCandles.lastOrNull()?.timestamp ?: 0L

                                renderHomeData(renderedCandles)

                                setHeaderStatus(
                                    "DIAG ${requestedSymbol} ${requestedTimeframe} " +
                                        "raw=${array.length()} " +
                                        "source=${validCandles.size} " +
                                        "rendered=${renderedCandles.size} " +
                                        "first=$firstTs last=$lastTs"
                                )

                                if (requestedTimeframe != "1D") {
                                    ensurePreviousDayCloseLoaded(instrument)
                                }
                            } else {
                                setHeaderStatus(
                                    "DIAG DISCARDED ${requestedSymbol} " +
                                        "${requestedTimeframe} " +
                                        "selected=${selectedInstrument.backendSymbol} " +
                                        "${selectedTimeframe}"
                                )
                            }
                        }

                    } catch (e: Exception) {
                        runOnUiThread {
                            if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                setHeaderStatus("History parse error: ${e.javaClass.simpleName}")
                            }
                        }
                    }
                }
            }
        })
    }

    // ---------------------------------------------------------------------
    // Persistent (disk) historical candle cache
    // ---------------------------------------------------------------------

    private data class DiskCacheEntry(val savedAt: Long, val candles: List<Candle>)

    private fun historyCacheDir(): File {
        val dir = File(filesDir, HISTORY_CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun historyCacheFile(symbol: String, timeframe: String): File {
        // Sanitize so ":" and other symbol characters can never break the path.
        val safeName = "${symbol}_$timeframe".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(historyCacheDir(), "$safeName.json")
    }

    /**
     * Returns the expected candle interval in seconds for a cached
     * timeframe. This is used to reject stale/incorrect candle data
     * that may have been written by an older implementation.
     */
    private fun expectedCandleIntervalSeconds(timeframe: String): Long {
        return when (timeframe) {
            "5m" -> 300L
            "15m" -> 900L
            "30m" -> 1800L
            "1h" -> 3600L
            "1D" -> 86400L
            else -> 0L
        }
    }

    /**
     * Validates one cached candle before it is allowed into the
     * application cache. Cached data is untrusted input because it
     * survives app restarts and may have been written by an older
     * implementation or interrupted external process.
     */
    private fun isValidCachedCandle(candle: Candle): Boolean {
        if (candle.timestamp <= 0L) return false

        val values = listOf(
            candle.open,
            candle.high,
            candle.low,
            candle.close,
            candle.volume
        )

        if (values.any { !it.isFinite() }) return false

        if (candle.open <= 0f ||
            candle.high <= 0f ||
            candle.low <= 0f ||
            candle.close <= 0f
        ) {
            return false
        }

        if (candle.volume < 0f) return false

        if (candle.high < candle.low) return false

        if (candle.open < candle.low ||
            candle.open > candle.high ||
            candle.close < candle.low ||
            candle.close > candle.high
        ) {
            return false
        }

        return true
    }

    /**
     * Validates that cached candles actually match the requested
     * timeframe. Overnight, weekend and holiday gaps are valid;
     * compressed intervals are not.
     */
    private fun candlesMatchTimeframe(
        candles: List<Candle>,
        timeframe: String
    ): Boolean {
        if (candles.isEmpty()) return false

        if (candles.any { !isValidCachedCandle(it) }) {
            return false
        }

        /*
         * Timestamps are normalized before this check. Duplicate
         * timestamps are therefore always a cache corruption/error
         * condition rather than a legitimate market-data gap.
         */
        val timestamps = candles.map { it.timestamp }
        if (timestamps.toSet().size != timestamps.size) {
            return false
        }

        if (timeframe == "1D") {
            return true
        }

        if (candles.size < 2) {
            /*
             * A single valid candle cannot prove its timeframe, so
             * accept it rather than forcing an unnecessary network
             * request for a minimally populated but valid cache.
             */
            return true
        }

        val expected = expectedCandleIntervalSeconds(timeframe)
        if (expected <= 0L) return false

        var validIntervals = 0

        for (i in 1 until candles.size) {
            val delta = candles[i].timestamp - candles[i - 1].timestamp

            /*
             * The series is sorted before this method is called.
             * Zero/negative deltas indicate duplicate or corrupt
             * timestamps and must never be accepted.
             */
            if (delta <= 0L) {
                return false
            }

            /*
             * Smaller-than-requested spacing means the cache contains
             * finer-grained data than requested. That is unsafe because
             * it changes the strategy/backtest/chart semantics.
             */
            if (delta < expected) {
                return false
            }

            if (delta == expected) {
                validIntervals++
            }
        }

        /*
         * For a multi-candle intraday cache, require at least one
         * normal interval. This rejects datasets that accidentally
         * contain only daily/very sparse candles.
         */
        return validIntervals > 0
    }

    /**
     * Reads and validates one disk cache entry. Any missing file, unreadable
     * file, JSON parse failure, or schema/symbol/timeframe mismatch is
     * treated as a plain cache miss (returns null) and the bad file is
     * deleted so it can't keep failing on every future load.
     *
     * Must be called on [diskCacheExecutor], never the main thread.
     */
    private fun readDiskCache(symbol: String, timeframe: String): DiskCacheEntry? {
        val file = historyCacheFile(symbol, timeframe)
        if (!file.exists()) return null

        return try {
            val root = JSONObject(file.readText())

            val schemaVersion = root.optInt("schemaVersion", -1)
            val cachedSymbol = root.optString("symbol", "")
            val cachedTimeframe = root.optString("timeframe", "")

            if (schemaVersion != HISTORY_CACHE_SCHEMA_VERSION ||
                cachedSymbol != symbol ||
                cachedTimeframe != timeframe
            ) {
                file.delete()
                return null
            }

            val savedAt = root.optLong("savedAt", -1L)
            val array = root.optJSONArray("candles")

            if (savedAt <= 0L || array == null || array.length() == 0) {
                file.delete()
                return null
            }

            val candles = mutableListOf<Candle>()
            for (i in 0 until array.length()) {
                val candle = array.optJSONArray(i) ?: continue
                if (candle.length() >= 5) {
                    candles.add(
                        Candle(
                            timestamp = candle.optLong(0),
                            open = candle.optDouble(1).toFloat(),
                            high = candle.optDouble(2).toFloat(),
                            low = candle.optDouble(3).toFloat(),
                            close = candle.optDouble(4).toFloat(),
                            volume = if (candle.length() >= 6) {
                                candle.optDouble(5, 0.0).toFloat()
                            } else 0f
                        )
                    )
                }
            }

            if (candles.isEmpty()) {
                file.delete()
                return null
            }

            // Always keep the historical series chronological.
            candles.sortBy { it.timestamp }

            if (!candlesMatchTimeframe(candles, timeframe)) {
                // Cache contains candles at the wrong timeframe.
                // Delete it so the caller performs a fresh backend
                // request using the selected resolution.
                file.delete()
                return null
            }

            DiskCacheEntry(savedAt, candles)
        } catch (e: Exception) {
            // Corrupt or unreadable — clear it and behave like a cache miss.
            file.delete()
            null
        }
    }

    /**
     * Writes one disk cache entry atomically: the full content is written to
     * a ".tmp" file first, then renamed over the real file. A crash or kill
     * mid-write can therefore never leave a partially-written, corrupt cache
     * file behind — readers only ever see a complete previous version or a
     * complete new one.
     *
     * Must be called on [diskCacheExecutor], never the main thread.
     */
    private fun writeDiskCache(
        symbol: String,
        timeframe: String,
        candles: List<Candle>,
        savedAt: Long
    ) {
        try {
            val finalFile = historyCacheFile(symbol, timeframe)
            val tmpFile = File(finalFile.parentFile, "${finalFile.name}.tmp")

            val candleArray = JSONArray()
            candles.forEach { candle ->
                val row = JSONArray()
                row.put(candle.timestamp)
                row.put(candle.open)
                row.put(candle.high)
                row.put(candle.low)
                row.put(candle.close)
                row.put(candle.volume)
                candleArray.put(row)
            }

            val root = JSONObject()
            root.put("schemaVersion", HISTORY_CACHE_SCHEMA_VERSION)
            root.put("symbol", symbol)
            root.put("timeframe", timeframe)
            root.put("savedAt", savedAt)
            root.put("candles", candleArray)

            tmpFile.writeText(root.toString())

            if (!tmpFile.renameTo(finalFile)) {
                // Some filesystems refuse to rename over an existing target;
                // fall back to delete-then-rename (still single-writer safe).
                finalFile.delete()
                tmpFile.renameTo(finalFile)
            }
        } catch (e: Exception) {
            // Best-effort cache: a failed write just means the next load
            // falls back to the backend, nothing else is affected.
        }
    }

    private fun isStillCurrentSelection(symbol: String, timeframe: String): Boolean {
        return isHomeScreenActive &&
            symbol == selectedInstrument.backendSymbol &&
            timeframe == selectedTimeframe
    }

    /**
     * Refreshes recent intraday candles when the live market has advanced
     * beyond the latest candle currently held in memory.
     *
     * The normal history loader intentionally keeps its 365-day request.
     * This method only requests the most recent 5 days, then merges those
     * authoritative OHLCV candles into the existing long history.
     *
     * WebSocket LTP remains responsible for the live price marker/current
     * candle between backend history refreshes.
     */
    /*
     * P3P10:
     * Activity-side bucket calculation used by intraday history refresh.
     *
     * NSE intraday sessions are anchored at 09:15 IST:
     * 30m -> 09:15, 09:45, 10:15, ...
     * 60m -> 09:15, 10:15, 11:15, ...
     */
    private fun intradayRefreshBucketTimestamp(
        timestampSeconds: Long,
        interval: Long
    ): Long {
        if (interval >= 86400L) {
            val zone =
                java.time.ZoneId.of("Asia/Kolkata")

            return java.time.Instant
                .ofEpochSecond(timestampSeconds)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toEpochSecond()
        }

        val zone =
            java.time.ZoneId.of("Asia/Kolkata")

        val localDate =
            java.time.Instant
                .ofEpochSecond(timestampSeconds)
                .atZone(zone)
                .toLocalDate()

        val sessionStart =
            localDate
                .atTime(9, 15)
                .atZone(zone)
                .toEpochSecond()

        if (timestampSeconds >= sessionStart) {
            val offset = timestampSeconds - sessionStart
            return sessionStart + (offset / interval) * interval
        }

        return (timestampSeconds / interval) * interval
    }

    private fun refreshIntradayHistoryIfNeeded(instrument: InstrumentInfo) {
        val timeframe = selectedTimeframe

        if (!isHomeScreenActive || timeframe == "1D") return

        val cacheKey = "${instrument.backendSymbol}|$timeframe"
        val cached = candlesByInstrument[cacheKey] ?: return
        if (cached.isEmpty()) return

        val interval = timeframeToSeconds(timeframe)
        if (interval <= 0L) return

        val nowSeconds = System.currentTimeMillis() / 1000L
        val currentBucket =
            intradayRefreshBucketTimestamp(
                nowSeconds,
                interval
            )

        val latestCachedBucket =
            intradayRefreshBucketTimestamp(
                cached.last().timestamp,
                interval
            )

        // Nothing is missing yet.
        if (currentBucket <= latestCachedBucket) return

        /*
         * The WebSocket can deliver many ticks during the same candle.
         * Once a successful history refresh has already been performed for
         * this bucket, do not redraw the complete chart again until the
         * market advances into the next bucket.
         */
        if (lastIntradayRefreshBucket[cacheKey] == currentBucket) return

        // Avoid multiple refresh requests during the same gap.
        if (!intradayRefreshInFlight.add(cacheKey)) return

        /*
         * Use the timeframe captured at the beginning of this refresh.
         * Do not re-read mutable selectedTimeframe while constructing
         * the request.
         */
        /*
         * P3P10:
         * Refresh futures intraday from the authoritative 5m source.
         */
        val resolution =
            if (
                instrument.isFutures &&
                timeframe != "1D"
            ) {
                "5"
            } else {
                timeframeResolution(timeframe)
            }

        val requestUrl =
            if (instrument.isFutures) {
                // The primary futures history request already supplies the
                // authoritative bounded range. Refresh the same bounded
                // window rather than sending current/future range_to.
                futuresHistoryUrl(
                    symbol = instrument.backendSymbol,
                    resolution = resolution
                )
            } else {
                "$BACKEND_HTTP_BASE/history" +
                    "?symbol=${instrument.backendSymbol}" +
                    "&resolution=$resolution&days=5"
            }

        val request = Request.Builder()
            .url(requestUrl)
            .build()

        wsClient.newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                intradayRefreshInFlight.remove(cacheKey)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) return

                        val body = response.body?.string() ?: return
                        val root = JSONObject(body)

                        if (backendRequiresFyersAuth(root)) {
                            handleFyersAuthRequired()
                            return
                        }

                        val array =
                            root.optJSONArray("candles")
                                ?: root
                                    .optJSONObject("response")
                                    ?.optJSONArray("candles")
                                ?: root
                                    .optJSONObject("response")
                                    ?.optJSONArray("data")
                                ?: return

                        val fresh = mutableListOf<Candle>()

                        for (i in 0 until array.length()) {
                            val candle = array.optJSONArray(i) ?: continue

                            if (candle.length() >= 5) {
                                fresh.add(
                                    Candle(
                                        timestamp = candle.optLong(0),
                                        open = candle.getDouble(1).toFloat(),
                                        high = candle.getDouble(2).toFloat(),
                                        low = candle.getDouble(3).toFloat(),
                                        close = candle.getDouble(4).toFloat(),
                                        volume = if (candle.length() >= 6) {
                                            candle.optDouble(5, 0.0).toFloat()
                                        } else {
                                            0f
                                        }
                                    )
                                )
                            }
                        }

                        if (fresh.isEmpty()) {
                            intradayRefreshInFlight.remove(cacheKey)
                            return
                        }

                        fresh.sortBy { it.timestamp }

                        val diagZone =
                            java.time.ZoneId.of("Asia/Kolkata")
                        val diagToday =
                            java.time.LocalDate.now(diagZone)

                        val freshToday =
                            fresh.filter {
                                java.time.Instant
                                    .ofEpochSecond(it.timestamp)
                                    .atZone(diagZone)
                                    .toLocalDate() == diagToday
                            }

                        android.util.Log.d(
                            "ALTRIXA_P3P10",
                            "FRESH_RESPONSE " +
                                "symbol=${instrument.backendSymbol} " +
                                "timeframe=$timeframe " +
                                "resolution=$resolution " +
                                "count=${fresh.size} " +
                                "today=${freshToday.size} " +
                                "first=${fresh.firstOrNull()?.timestamp ?: 0L} " +
                                "last=${fresh.lastOrNull()?.timestamp ?: 0L} " +
                                "todayFirst=${freshToday.firstOrNull()?.timestamp ?: 0L} " +
                                "todayLast=${freshToday.lastOrNull()?.timestamp ?: 0L}"
                        )

                        runOnUiThread {
                            try {
                                if (!isStillCurrentSelection(
                                        instrument.backendSymbol,
                                        timeframe
                                    )
                                ) {
                                    return@runOnUiThread
                                }

                                val merged =
                                    run {
                                        val current =
                                            candlesByInstrument[
                                                cacheKey
                                            ].orEmpty()

                                        val mergedCandles =
                                            (current + fresh)
                                                .filter(
                                                    ::isValidCachedCandle
                                                )
                                                .associateBy {
                                                    it.timestamp
                                                }
                                                .values
                                                .sortedBy {
                                                    it.timestamp
                                                }

                                        if (
                                            instrument.isFutures &&
                                            timeframe != "1D"
                                        ) {
                                            normalizeFuturesCandles(
                                                mergedCandles,
                                                timeframe
                                            )
                                        } else {
                                            mergedCandles
                                        }
                                    }
                                if (merged.isEmpty()) {
                                    return@runOnUiThread
                                }

                                candlesByInstrument[cacheKey] =
                                    merged

                                /*
                                 * Mark this bucket as successfully refreshed.
                                 * Network/HTTP/parse failures do not reach this
                                 * point, so they remain retryable.
                                 */
                                lastIntradayRefreshBucket[cacheKey] = currentBucket

                                val savedAt = System.currentTimeMillis()
                                diskCacheExecutor.execute {
                                    writeDiskCache(
                                        instrument.backendSymbol,
                                        timeframe,
                                        merged,
                                        savedAt
                                    )
                                }

                                renderHomeData(
                                    merged,
                                    preserveChartViewport = true
                                )

                                // Re-apply the latest WebSocket LTP after
                                // rendering so the live marker/current candle
                                // remains visually live.
                                liveLtpByInstrument[instrument.backendSymbol]
                                    ?.let { updateHeaderPrice() }

                            } finally {
                                intradayRefreshInFlight.remove(cacheKey)
                            }
                        }

                    } catch (_: Exception) {
                        intradayRefreshInFlight.remove(cacheKey)
                    }
                }
            }
        })
    }

    private fun connectQuotesWebSocket() {
        val request = Request.Builder()
            .url(BACKEND_WS_URL)
            .removeHeader("Origin")
            .build()

        quotesWebSocket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Connection established.
                    runOnUiThread {
                        if (isHomeScreenActive) refreshHeaderConnectionBadge()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val root = JSONObject(text)

                        if (backendRequiresFyersAuth(root)) {
                            handleFyersAuthRequired()
                            return
                        }

                        if (fyersAuthRequired) {
                            clearFyersAuthRequired()
                        }

                        val quotes = root.optJSONObject("quotes") ?: return

                        val subscribedInstruments =
                            buildList {
                                addAll(Instruments.all)
                                selectedFuturesContract?.let { add(it) }
                            }.distinctBy { it.backendSymbol }

                        for (instrument in subscribedInstruments) {
                            val quote =
                                quotes.optJSONObject(instrument.backendSymbol)
                                    ?: continue

                            val ltp =
                                quote.optDouble(
                                    "ltp",
                                    Double.NaN
                                )

                            if (!ltp.isNaN()) {
                                liveLtpByInstrument[
                                    instrument.backendSymbol
                                ] = ltp

                                /*
                                 * Prefer FYERS market-side timestamps for
                                 * candle bucketing.
                                 */
                                val marketTimestamp =
                                    listOf(
                                        quote.optLong(
                                            "last_traded_time",
                                            0L
                                        ),
                                        quote.optLong(
                                            "exch_feed_time",
                                            0L
                                        )
                                    ).firstOrNull { it > 0L }

                                if (marketTimestamp != null) {
                                    liveTimestampByInstrument[
                                        instrument.backendSymbol
                                    ] = marketTimestamp
                                }
                            }
                        }

                        runOnUiThread {
                            if (isHomeScreenActive) {
                                val selectedSymbol =
                                    selectedInstrument.backendSymbol

                                val selectedLtp =
                                    liveLtpByInstrument[selectedSymbol]

                                if (selectedLtp != null) {
                                    mainChartView?.setLatestPrice(
                                        selectedLtp.toFloat(),
                                        liveTimestampByInstrument[selectedSymbol]
                                    )
                                }

                                updateHeaderPrice()

                                /*
                                 * Indexes retain the existing recent-history
                                 * refresh path.
                                 *
                                 * Futures deliberately do not use this
                                 * generic HTTP refresh on every WebSocket
                                 * tick. Their 5-minute history endpoint
                                 * already merges the backend live futures
                                 * candle, while setLatestPrice() maintains
                                 * the currently forming candle locally.
                                 *
                                 * Re-fetching and re-rendering the complete
                                 * futures history on every quote can race
                                 * with that live-candle path and cause
                                 * unnecessary redraws/flicker.
                                 */
                                if (!selectedInstrument.isFutures) {
                                    refreshIntradayHistoryIfNeeded(
                                        selectedInstrument
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    quotesWebSocket = null

                    runOnUiThread {
                        if (isHomeScreenActive) refreshHeaderConnectionBadge()
                    }

                    liveHandler.postDelayed(
                        { connectQuotesWebSocket() },
                        2000
                    )
                }
            }
        )
    }

    // ---------------------------------------------------------------------
    // Activity lifecycle
    // ---------------------------------------------------------------------

    override fun onResume() {
        super.onResume()

        if (fyersOAuthBrowserOpened &&
            !isFinishing &&
            pendingFyersOAuthState != null
        ) {
            // Give the browser-to-ALTRIXA lifecycle transition time to settle.
            // Only consume the pending OAuth state if it is still active.
            window.decorView.postDelayed({
                if (!isFinishing &&
                    fyersOAuthBrowserOpened &&
                    pendingFyersOAuthState != null
                ) {
                    fyersOAuthBrowserOpened = false
                    showFyersAuthCodeDialog()
                }
            }, 750)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        backtestJobStore = BacktestJobStore(applicationContext)

        intent.getStringExtra(BacktestWorker.EXTRA_BACKTEST_JOB_ID)?.let {
            selectedBacktestJobId = it
            activeBacktestJobId = it
        }

        super.onCreate(savedInstanceState)

        buildApp()
        requestNotificationPermissionIfNeeded()
        connectQuotesWebSocket()
        discoverNiftyFuturesContract()

        if (intent?.action == FYERS_AUTH_RENEW_ACTION) {
            startFyersOAuthRenewal()
        }

        handleFyersOAuthCallback(intent?.data)

        if (activeBacktestJobId != null) {
            showBacktest()
        } else {
            showHome()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent?.action == FYERS_AUTH_RENEW_ACTION) {
            startFyersOAuthRenewal()
        }

        handleFyersOAuthCallback(intent?.data)

        intent?.getStringExtra(BacktestWorker.EXTRA_BACKTEST_JOB_ID)?.let {
            selectedBacktestJobId = it
            activeBacktestJobId = it
            showBacktest()
        }
    }

    override fun onBackPressed() {
        if (isHomeScreenActive) {
            super.onBackPressed()
            return
        }

        if (isBacktestResultsScreen) {
            // Results -> Backtest configuration.
            // Keep all background jobs alive. Only clear the currently
            // viewed result.
            isBacktestResultsScreen = false
            selectedBacktestJobId = null
            activeBacktestJobId = null
            renderedBacktestJobId = null
            isBacktestScreenVisible = true
            isHomeScreenActive = false

            clearContent()
            renderBacktestConfiguration()

            backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
            backtestProgressHandler.post(backtestProgressRunnable)
            return
        }

        if (isBacktestScreenVisible && activeBacktestJobId != null) {
            // Running job -> Backtest configuration.
            // The WorkManager job continues independently in the background.
            // Keep the selected job ID so returning to Backtest can restore
            // the same job and show its eventual persisted result.
            selectedBacktestJobId = activeBacktestJobId
            renderedBacktestJobId = null
            isBacktestResultsScreen = false
            isBacktestScreenVisible = true
            isHomeScreenActive = false

            clearContent()
            renderBacktestConfiguration()

            backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
            backtestProgressHandler.post(backtestProgressRunnable)
            return
        }

        // Backtest configuration -> Home.
        showHome()
    }

    override fun onDestroy() {
        backtestProgressHandler.removeCallbacksAndMessages(null)
        isBacktestScreenVisible = false
        super.onDestroy()
        quotesWebSocket?.close(1000, "Activity destroyed")
        liveHandler.removeCallbacksAndMessages(null)
        diskCacheExecutor.shutdown()
    }

    private fun buildApp() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AltrixaColors.background)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AltrixaColors.background)
            setPadding(
                dp(AltrixaDimens.spaceLg),
                dp(AltrixaDimens.spaceLg),
                dp(AltrixaDimens.spaceLg),
                dp(AltrixaDimens.spaceSm)
            )
        }

        val contentScroll = ScrollView(this).apply {
            addView(content)
        }

        bottomNav = AltrixaBottomNav(this) { destination ->
            when (destination) {
                AltrixaDestination.HOME -> showHome()
                AltrixaDestination.MARKET_DATA -> showMarketData()
                AltrixaDestination.STRATEGIES -> showStrategies()
                AltrixaDestination.EXECUTION -> showExecution()
                AltrixaDestination.BACKTEST -> showBacktest()
            }
        }

        root.addView(
            contentScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            bottomNav,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun clearContent() {
        isHomeScreenActive = false
        headerTitleLabel = null
        headerPriceLabel = null
        headerChangeLabel = null
        headerStatusLabel = null
        timeRangeLabel = null
        mainChartView = null
        volumeChartView = null
        rsiChartView = null
        timeframeButtons.clear()
        instrumentButtons.clear()
        strategySelectorButtons.clear()
        headerConnectionRow = null
        headerConnectionBadge = null
        ohlcOpenValue = null
        ohlcHighValue = null
        ohlcLowValue = null
        ohlcCloseValue = null
        content.removeAllViews()
    }

    private fun title(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = AltrixaDimens.textTitle
            setTextColor(AltrixaColors.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(AltrixaDimens.spaceMd))
        }

    private fun section(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = AltrixaDimens.textSection
            setTextColor(AltrixaColors.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(
                0,
                dp(AltrixaDimens.spaceMd),
                0,
                dp(AltrixaDimens.spaceSm)
            )
        }

    private fun label(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = AltrixaDimens.textBody
            setTextColor(AltrixaColors.textLabel)
            setPadding(
                0,
                dp(AltrixaDimens.spaceXs),
                0,
                dp(AltrixaDimens.spaceXs)
            )
        }

    private fun formatNumber(value: Float): String {
        return String.format(Locale.US, "%,.2f", value)
    }

    private fun formatChange(change: Float): String {
        val sign = if (change >= 0) "+" else ""
        return "$sign${String.format(Locale.US, "%,.2f", change)}"
    }

    private fun formatPercent(pct: Float): String {
        val sign = if (pct >= 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.2f", pct)}%"
    }

    // ---------------------------------------------------------------------
    // HOME — TradingView-style single-instrument workspace
    // ---------------------------------------------------------------------

    private fun showHome() {
        isBacktestScreenVisible = false
        backtestProgressHandler.removeCallbacks(backtestProgressRunnable)

        // Home is outside the Backtest sub-flow.
        isBacktestResultsScreen = false
        renderedBacktestJobId = null

        bottomNav.setSelected(AltrixaDestination.HOME)
        clearContent()
        isHomeScreenActive = true

        content.addView(buildHeader())
        content.addView(
            buildSelectorPanel(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        content.addView(
            buildChartPanel(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        content.addView(
            buildOhlcRow(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        content.addView(
            buildExecutionPanel(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        // Show cached data immediately (if any) while a fresh reload is in flight,
        // then always reload from the backend for the current selection.
        candlesByInstrument["${selectedInstrument.backendSymbol}|$selectedTimeframe"]?.let { cached ->
            renderHomeData(cached)
        }
        updateHeaderPrice()
        loadHistoryForSelected()
    }

    // ALTRIXA_PHASE2_HOME_DASHBOARD — premium terminal-style Home header/panels.
    // Presentation only: values still come from renderHomeData()/updateHeaderPrice();
    // no new data sources are introduced here.
    private fun buildHeader(): LinearLayout {
        val card = altrixaCard(this)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(
            TextView(this).apply {
                text = "ALTRIXA"
                textSize = AltrixaDimens.textCaption
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(AltrixaColors.accent)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        headerConnectionRow = brandRow
        card.addView(brandRow)

        val authBanner = TextView(this).apply {
            text = "⚠  FYERS Authentication Required\nGenerate a fresh access token to restore live market data."
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(AltrixaColors.warning)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = if (fyersAuthRequired) View.VISIBLE else View.GONE
        }
        fyersAuthBanner = authBanner
        card.addView(
            authBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        val titleRow = TextView(this).apply {
            text = "${selectedInstrument.displayName} · $selectedTimeframe"
            textSize = 15f
            setTextColor(AltrixaColors.textSecondary)
            setPadding(0, dp(6), 0, 0)
        }
        headerTitleLabel = titleRow

        val priceRow = TextView(this).apply {
            text = "—"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(AltrixaColors.textPrimary)
            setPadding(0, dp(2), 0, dp(2))
        }
        headerPriceLabel = priceRow

        val changeRow = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(AltrixaColors.positive)
        }
        headerChangeLabel = changeRow

        val statusRow = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(AltrixaColors.warning)
            setPadding(0, dp(2), 0, 0)
        }
        headerStatusLabel = statusRow

        card.addView(titleRow)
        card.addView(priceRow)
        card.addView(changeRow)
        card.addView(statusRow)

        refreshHeaderConnectionBadge()

        return card
    }

    /** Rebuilds the LIVE/OFFLINE badge in place, reflecting the real websocket state. */
    private fun refreshHeaderConnectionBadge() {
        val row = headerConnectionRow ?: return
        val connected = quotesWebSocket != null

        headerConnectionBadge?.let { row.removeView(it) }

        val badge = altrixaStatusBadge(
            this,
            if (connected) "LIVE" else "OFFLINE",
            if (connected) AltrixaTone.POSITIVE else AltrixaTone.NEGATIVE
        )
        headerConnectionBadge = badge
        row.addView(badge)
    }

    private fun setHeaderStatus(text: String?) {
        headerStatusLabel?.text = text ?: ""
        headerStatusLabel?.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildInstrumentSelector(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }

        instrumentButtons.clear()

        val instruments = buildList {
            addAll(Instruments.all)
            selectedFuturesContract?.let { add(it) }
        }.distinctBy { it.backendSymbol }

        instruments.forEach { instrument ->
            val selected =
                instrument.backendSymbol == selectedInstrument.backendSymbol

            val chip = altrixaChip(this, instrument.displayName, selected) {
                if (selectedInstrument.backendSymbol != instrument.backendSymbol) {
                    selectedInstrument = instrument

                    if (instrument.isFutures) {
                        subscribeToFuturesContract(instrument)
                    }

                    showHome()
                }
            }

            instrumentButtons[instrument.backendSymbol] = chip

            row.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    marginEnd = dp(4)
                }
            )
        }

        return row
    }

    private fun buildTimeframeToolbar(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        TIMEFRAMES.forEach { timeframe ->
            val selected = timeframe == selectedTimeframe
            val chip = altrixaChip(this, timeframe, selected) {
                if (selectedTimeframe != timeframe) {
                    selectedTimeframe = timeframe
                    showHome()
                }
            }

            timeframeButtons[timeframe] = chip

            row.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginEnd = dp(2)
                }
            )
        }

        return row
    }

    /** Instrument + timeframe chips, grouped into one ALTRIXA panel. */
    private fun buildSelectorPanel(): LinearLayout {
        val card = altrixaCard(this)
        card.addView(buildInstrumentSelector())
        card.addView(
            TextView(this).apply {
                text = "TIMEFRAME"
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.textFaint)
                setPadding(0, dp(8), 0, dp(4))
            }
        )
        card.addView(buildTimeframeToolbar())
        return card
    }


    /**
     * Phase 3.2: one active strategy whose real engine signals are
     * visualized on the main price chart.
     */
    private fun buildStrategySignalSelector(parent: LinearLayout) {
        parent.addView(
            TextView(this).apply {
                text = "SIGNAL STRATEGY"
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.textFaint)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(6))
            }
        )

        val scroll = ScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val choices = listOf(
            StrategyRegistry.MOVING_AVERAGE_CROSSOVER to "MA Crossover",
            StrategyRegistry.RSI to "RSI",
            StrategyRegistry.MACD to "MACD",
            StrategyRegistry.BOLLINGER_BANDS to "Bollinger",
            StrategyRegistry.DONCHIAN_CHANNEL to "Donchian",
            StrategyRegistry.DONCHIAN_EMA to "Donchian + EMA",
            StrategyRegistry.CPR_EMA to "CPR + EMA"
        )

        strategySelectorButtons.clear()

        choices.forEach { (strategyId, label) ->
            val button = altrixaChip(
                this,
                label,
                strategyId == selectedSignalStrategyId
            ) {
                selectedSignalStrategyId = strategyId

                strategySelectorButtons.forEach { (id, chip) ->
                    val selected = id == selectedSignalStrategyId
                    chip.setTextColor(
                        if (selected)
                            AltrixaColors.textPrimary
                        else
                            AltrixaColors.textSecondary
                    )
                    chip.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(
                            if (selected)
                                AltrixaColors.accent
                            else
                                AltrixaColors.surfaceVariant
                        )
                        cornerRadius = AltrixaDimens.radiusSm
                    }
                }

                val key =
                    "${selectedInstrument.backendSymbol}|$selectedTimeframe"

                val candles =
                    candlesByInstrument[key]

                if (candles.isNullOrEmpty()) {
                    mainChartView?.setSignals(emptyList())
                } else {
                    updateStrategySignals(candles)
                }
            }

            strategySelectorButtons[strategyId] = button

            row.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(38)
                ).apply {
                    marginEnd = dp(6)
                }
            )
        }

        scroll.addView(row)

        parent.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        )
    }

    private fun buildMainChart(parent: LinearLayout): TradingChartView {
        val chart = TradingChartView(this)
        mainChartView = chart
        parent.addView(
            chart,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280))
        )
        return chart
    }

    private fun buildVolumePanel(parent: LinearLayout): VolumeChartView {
        val volume = VolumeChartView(this)
        volumeChartView = volume
        parent.addView(
            volume,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply {
                topMargin = dp(2)
            }
        )
        return volume
    }

    private fun buildRsiPanel(parent: LinearLayout): RsiChartView {
        val rsi = RsiChartView(this)
        rsiChartView = rsi
        parent.addView(
            rsi,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)).apply {
                topMargin = dp(2)
            }
        )
        return rsi
    }

    private fun buildTimeRangeRow(parent: LinearLayout): TextView {
        val label = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(AltrixaColors.textFaint)
            setPadding(0, dp(6), 0, dp(6))
        }
        timeRangeLabel = label
        parent.addView(label)
        return label
    }

    /** Candlestick + EMA20/EMA50, volume, RSI(14) and the time-range caption, in one panel. */
    private fun buildChartPanel(): LinearLayout {
        val card = altrixaCard(this)
        buildStrategySignalSelector(card)
        buildMainChart(card)
        buildVolumePanel(card)
        buildRsiPanel(card)
        buildTimeRangeRow(card)
        return card
    }

    /** Compact OHLC strip for the most recently loaded real candle. */
    private fun buildOhlcRow(): LinearLayout {
        val card = altrixaCard(this)

        card.addView(
            TextView(this).apply {
                text = "MARKET DATA"
                textSize = AltrixaDimens.textCaption
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(AltrixaColors.textFaint)
                setPadding(0, 0, 0, dp(8))
            }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun ohlcCell(labelText: String): Pair<LinearLayout, TextView> {
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            cell.addView(
                TextView(this@MainActivity).apply {
                    text = labelText
                    textSize = AltrixaDimens.textCaption
                    setTextColor(AltrixaColors.textFaint)
                }
            )
            val value = TextView(this@MainActivity).apply {
                text = "—"
                textSize = AltrixaDimens.textBody
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(AltrixaColors.textPrimary)
                setPadding(0, dp(2), 0, 0)
            }
            cell.addView(value)
            return cell to value
        }

        val (openCell, openValue) = ohlcCell("OPEN")
        val (highCell, highValue) = ohlcCell("HIGH")
        val (lowCell, lowValue) = ohlcCell("LOW")
        val (closeCell, closeValue) = ohlcCell("CLOSE")

        ohlcOpenValue = openValue
        ohlcHighValue = highValue
        ohlcLowValue = lowValue
        ohlcCloseValue = closeValue

        listOf(openCell, highCell, lowCell, closeCell).forEach { cell ->
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        card.addView(row)
        return card
    }

    /** BUY/SELL — same placeOrder() call as before; execution is still not connected. */
    private fun buildExecutionPanel(): LinearLayout {
        val card = altrixaCard(this)

        card.addView(
            TextView(this).apply {
                text = "EXECUTION"
                textSize = AltrixaDimens.textCaption
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(AltrixaColors.textFaint)
                setPadding(0, 0, 0, dp(8))
            }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val buy = altrixaPrimaryButton(this, "BUY", AltrixaColors.positive) { placeOrder("BUY") }
        val sell = altrixaPrimaryButton(this, "SELL", AltrixaColors.negative) { placeOrder("SELL") }

        row.addView(buy, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(6) })
        row.addView(sell, LinearLayout.LayoutParams(0, dp(56), 1f))

        card.addView(row)
        return card
    }

    /**
     * Placeholder trade action. No live/paper order is submitted here yet — this is
     * structured so a real execution path (e.g. core:execution) can be wired in later,
     * with user confirmation before anything is actually sent.
     */
    private fun placeOrder(side: String) {
        Toast.makeText(
            this,
            "$side ${selectedInstrument.displayName} — order execution not yet connected",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Refreshes chart, volume, RSI panels and the header from freshly loaded candles. */
    private fun renderHomeData(
        candles: List<Candle>,
        preserveChartViewport: Boolean = false
    ) {
        if (!isHomeScreenActive) return

        // Rendering boundary: charts and indicators always receive
        // candles in chronological order.
        val orderedCandles = candles.sortedBy { it.timestamp }

        val closes = orderedCandles.map { it.close }
        val ema20 = computeEma(closes, 20)
        val ema50 = computeEma(closes, 50)
        val rsi = computeRsi(closes, 14)
        val isDaily = selectedTimeframe == "1D"

        val renderZone =
            java.time.ZoneId.of("Asia/Kolkata")
        val renderToday =
            java.time.LocalDate.now(renderZone)

        val todayCandles =
            orderedCandles.filter {
                java.time.Instant
                    .ofEpochSecond(it.timestamp)
                    .atZone(renderZone)
                    .toLocalDate() == renderToday
            }

        setHeaderStatus(
            "DIAG RENDER ${selectedInstrument.backendSymbol} " +
                "${selectedTimeframe} candles=${orderedCandles.size} " +
                "TODAY=${todayCandles.size} " +
                "first=${orderedCandles.firstOrNull()?.timestamp ?: 0L} " +
                "last=${orderedCandles.lastOrNull()?.timestamp ?: 0L} " +
                "todayFirst=${todayCandles.firstOrNull()?.timestamp ?: 0L} " +
                "todayLast=${todayCandles.lastOrNull()?.timestamp ?: 0L}"
        )

        mainChartView?.setData(
            candles = orderedCandles,
            ema20 = ema20,
            ema50 = ema50,
            isDaily = isDaily,
            candleIntervalSeconds = timeframeToSeconds(selectedTimeframe),
            preserveViewport = preserveChartViewport
        )
        updateStrategySignals(orderedCandles)
        volumeChartView?.setData(orderedCandles)
        rsiChartView?.setData(rsi)

        // Header Change/% Change baseline:
        // Use the previous trading day's CLOSE, not today's opening price and
        // not the last intraday candle close. For intraday timeframes, obtain
        // the daily candles from the existing in-memory cache when available.
        //
        // This keeps the live LTP and chart candle streams independent while
        // matching the standard market-data convention:
        // Change = LTP - previous trading day's close.
        val dailyKey = "${selectedInstrument.backendSymbol}|1D"
        val dailyCandles = candlesByInstrument[dailyKey]
            ?.sortedBy { it.timestamp }

        if (!dailyCandles.isNullOrEmpty()) {
            updatePreviousDayCloseFromDaily(
                selectedInstrument.backendSymbol,
                dailyCandles
            )
        } else if (isDaily) {
            updatePreviousDayCloseFromDaily(
                selectedInstrument.backendSymbol,
                orderedCandles
            )
        }

        headerTitleLabel?.text = "${selectedInstrument.displayName} · $selectedTimeframe"
        updateHeaderPrice()

        val latestCandle = orderedCandles.last()
        ohlcOpenValue?.text = formatNumber(latestCandle.open)
        ohlcHighValue?.text = formatNumber(latestCandle.high)
        ohlcLowValue?.text = formatNumber(latestCandle.low)
        ohlcCloseValue?.text = formatNumber(latestCandle.close)

        val dateFormat = if (isDaily) {
            SimpleDateFormat("dd MMM yyyy", Locale.US)
        } else {
            SimpleDateFormat("dd MMM, HH:mm", Locale.US)
        }
        dateFormat.timeZone = TimeZone.getTimeZone("Asia/Kolkata")

        val start = dateFormat.format(Date(orderedCandles.first().timestamp * 1000L))
        val end = dateFormat.format(Date(orderedCandles.last().timestamp * 1000L))
        timeRangeLabel?.text = "$start  →  $end  (IST)"
    }

    /**
     * Converts the selected chart timeframe to seconds.
     * Used by the live candle aggregator so the active candle
     * matches the timeframe currently displayed by the user.
     */
    private fun timeframeToSeconds(timeframe: String): Long {
        return when (timeframe) {
            "1m" -> 60L
            "5m" -> 5L * 60L
            "15m" -> 15L * 60L
            "30m" -> 30L * 60L
            "1h" -> 60L * 60L
            "1D" -> 24L * 60L * 60L
            else -> 5L * 60L
        }
    }


    /**
     * Evaluates the currently selected real strategy against the same
     * historical candles shown on the chart.
     *
     * HOLD signals are deliberately omitted from the chart.
     * No orders or portfolio mutations occur here.
     */
    private fun updateStrategySignals(uiCandles: List<Candle>) {
        if (uiCandles.isEmpty()) {
            mainChartView?.setSignals(emptyList())
            return
        }

        val domainInstrument =
            com.algotrader.domain.Instrument(
                symbol = selectedInstrument.backendSymbol,
                exchange = selectedInstrument.backendSymbol.substringBefore(":")
            )

        val domainTimeframe =
            when (selectedTimeframe) {
                "1m" -> com.algotrader.domain.Timeframe.MINUTE_1
                "5m" -> com.algotrader.domain.Timeframe.MINUTE_5
                "15m" -> com.algotrader.domain.Timeframe.MINUTE_15
                "30m" -> com.algotrader.domain.Timeframe.MINUTE_30
                "1h" -> com.algotrader.domain.Timeframe.HOUR_1
                "4h" -> com.algotrader.domain.Timeframe.HOUR_4
                "1D" -> com.algotrader.domain.Timeframe.DAY_1
                else -> com.algotrader.domain.Timeframe.MINUTE_5
            }

        val domainCandles = uiCandles.map {
            com.algotrader.domain.Candle(
                instrument = domainInstrument,
                timeframe = domainTimeframe,
                timestamp = java.time.Instant.ofEpochSecond(it.timestamp),
                open = it.open.toDouble(),
                high = it.high.toDouble(),
                low = it.low.toDouble(),
                close = it.close.toDouble(),
                volume = it.volume.toDouble()
            )
        }

        try {
            val signals =
                strategyRunner.evaluate(
                    configuration = StrategyConfiguration(
                        strategyId = selectedSignalStrategyId
                    ),
                    candles = domainCandles,
                    portfolio = com.algotrader.domain.Portfolio(
                        cash = 0.0
                    )
                ).filter { it.type != SignalType.HOLD }

            mainChartView?.setSignals(signals)
        } catch (_: Exception) {
            // Signal visualization must never break the market-data/chart path.
            mainChartView?.setSignals(emptyList())
        }
    }

    /** Updates only the price/change header text — from live LTP if available, else last close. */
    private fun updateHeaderPrice() {
        if (!isHomeScreenActive) return

        val symbol = selectedInstrument.backendSymbol
        val candles = candlesByInstrument["$symbol|$selectedTimeframe"]
        val liveLtp = liveLtpByInstrument[symbol]

        val price: Float = when {
            liveLtp != null -> liveLtp.toFloat()
            candles != null && candles.isNotEmpty() -> candles.last().close
            else -> return
        }

        val baseline =
            if (homePreviousDayClose > 0f) homePreviousDayClose else price
        val change = price - baseline
        val pct = if (baseline != 0f) change / baseline * 100f else 0f

        headerPriceLabel?.text = formatNumber(price)
        headerChangeLabel?.text = "${formatChange(change)} (${formatPercent(pct)})"
        headerChangeLabel?.setTextColor(
            if (change >= 0) AltrixaColors.positive else AltrixaColors.negative
        )

        if (liveLtp != null) {
            mainChartView?.setLatestPrice(price)
        }
    }

    // ---------------------------------------------------------------------
    // Other tabs (unchanged in spirit — simple status screens)
    // ---------------------------------------------------------------------

    private fun showMarketData() {
        isBacktestScreenVisible = false
        backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
        bottomNav.setSelected(AltrixaDestination.MARKET_DATA)
        clearContent()
        // isLiveConnected reads the existing quotesWebSocket reference as-is —
        // no change to how/when it is set (see connectQuotesWebSocket()).
        MarketDataScreen.render(this, content, isLiveConnected = quotesWebSocket != null)
    }

    private fun showStrategies() {
        isBacktestScreenVisible = false
        backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
        bottomNav.setSelected(AltrixaDestination.STRATEGIES)
        clearContent()
        StrategiesScreen.render(this, content)
    }

    private fun showExecution() {
        isBacktestScreenVisible = false
        backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
        bottomNav.setSelected(AltrixaDestination.EXECUTION)
        clearContent()
        ExecutionScreen.render(this, content)
    }


private var selectedBacktestConfigurations: List<StrategyConfiguration> = emptyList()
private var selectedBacktestCapital: Double = 100_000.0
private var selectedBacktestSizing: PositionSizing = PositionSizing.FixedQuantity(1.0)
// P3P11: generic assumed research costs (zero = legacy gross-only behaviour).
private var selectedBacktestCostModel: ResearchCostModel = ResearchCostModel()


private fun showBacktest() {
    bottomNav.setSelected(AltrixaDestination.BACKTEST)
    clearContent()

    renderedBacktestJobId = null
    isBacktestScreenVisible = true
    isBacktestResultsScreen = false
    isHomeScreenActive = false

    backtestProgressHandler.removeCallbacks(backtestProgressRunnable)

    val jobId = activeBacktestJobId

    if (jobId != null) {
        val job = backtestJobStore.get(jobId)

        if (job != null) {
            restoreBacktestJob(job)

            if (
                job.status != BacktestJobStore.Status.COMPLETED &&
                job.status != BacktestJobStore.Status.FAILED &&
                job.status != BacktestJobStore.Status.CANCELLED
            ) {
                backtestProgressHandler.post(backtestProgressRunnable)
            }

            return
        }

        activeBacktestJobId = null
        selectedBacktestJobId = null
    }

    renderBacktestConfiguration()
}

/**
 * Cancels the whole logical launch the given job belongs to: every job that
 * shares its runId (standard: all strategy jobs; OOS: every In-Sample and
 * Out-of-Sample job). The CANCELLED state is persisted by the existing
 * BacktestJobStore; the WorkManager requests are then cancelled so queued jobs
 * never start and running Workers stop. Other runs are untouched.
 *
 * Only called from the explicit Cancel button, never from navigation.
 */
private fun cancelBacktestLaunch(jobId: String) {
    val cancelledIds = backtestJobStore.cancelRun(jobId)

    val workManager = WorkManager.getInstance(applicationContext)
    cancelledIds.forEach { id ->
        workManager.cancelUniqueWork("altrixa_backtest_$id")
    }

    backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
    renderedBacktestJobId = null

    val job = backtestJobStore.get(jobId)
    if (job != null) {
        isBacktestScreenVisible = true
        restoreBacktestJob(job)
    } else {
        activeBacktestJobId = null
        selectedBacktestJobId = null
        content.removeAllViews()
        renderBacktestConfiguration()
    }
}

/**
 * Confirmation first; only then is the saved test (the whole logical run for
 * runId jobs) permanently removed, and the saved-test list refreshed.
 */
private fun confirmAndDeleteBacktest(jobId: String) {
    val job = backtestJobStore.get(jobId) ?: run {
        content.removeAllViews()
        renderBacktestConfiguration()
        return
    }

    BacktestScreen.confirmDelete(this, job) {
        when (val outcome = backtestJobStore.deleteRun(jobId)) {
            is BacktestJobStore.DeleteOutcome.Deleted -> {
                if (activeBacktestJobId in outcome.jobIds) activeBacktestJobId = null
                if (selectedBacktestJobId in outcome.jobIds) selectedBacktestJobId = null
                if (renderedBacktestJobId in outcome.jobIds) renderedBacktestJobId = null
            }

            BacktestJobStore.DeleteOutcome.StillActive ->
                Toast.makeText(
                    this,
                    "Cancel the running backtest before deleting it.",
                    Toast.LENGTH_LONG
                ).show()

            BacktestJobStore.DeleteOutcome.NotFound -> Unit
        }

        content.removeAllViews()
        renderBacktestConfiguration()
    }
}

private fun futuresBacktestResolution() =
    com.algotrader.app.backtest.BacktestInstrumentResolver.resolve(
        com.algotrader.app.backtest.BacktestInstrumentType.FUTURES,
        selectedInstrument,
        selectedFuturesContract
    )

private fun futuresBacktestReady(): Boolean =
    futuresBacktestResolution() is
        com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution.Resolved

private fun futuresBacktestNote(): String =
    when (val r = futuresBacktestResolution()) {
        is com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution.Rejected ->
            if (selectedFuturesContract != null) {
                "Lot size: unavailable. " +
                    (futuresMetadataStatus ?: r.reason)
            } else {
                r.reason
            }

        is com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution.Resolved ->
            "Contract ${r.instrument.backendSymbol} \u00b7 " +
                "Lot size: ${r.lotSize} units " +
                "(${r.instrument.contractLotSizeSource}). " +
                "Uses futures candles; index candles are never substituted."
    }

private fun renderBacktestConfiguration() {
    // Retry the lot-size lookup if an earlier attempt failed; the status line
    // reflects the result the next time this screen is drawn.
    selectedFuturesContract?.let { contract ->
        if (contract.contractLotSize == null) fetchFuturesContractMetadata(contract)
    }
    // Keep the configuration UI on the same authoritative futures resolution
    // used by runBacktest(). This avoids depending on selectedInstrument being
    // the discovered futures contract at the exact moment the screen redraws.
    val futuresResolution = futuresBacktestResolution()
    val configurationLotSize = when (futuresResolution) {
        is com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution.Resolved ->
            futuresResolution.lotSize

        is com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution.Rejected ->
            if (selectedInstrument.isFutures) {
                selectedInstrument.contractLotSize ?: 1
            } else {
                selectedInstrument.lotSize
            }
    }

    BacktestScreen.renderConfig(
        this,
        content,
        instrumentName = selectedInstrument.displayName,
        timeframe = selectedTimeframe,
        candleCount = candlesByInstrument[
            "${selectedInstrument.backendSymbol}|$selectedTimeframe"
        ]?.size ?: 0,
        initialCapital = 100_000.0,
        positionQuantity = 1.0,
        // For futures, use the authoritative resolver lot size. If metadata is
        // unavailable, keep the safe UI placeholder of 1; futuresReady remains
        // false, so the run is still blocked until the real lot size is known.
        lotSize = configurationLotSize,
        instrumentType = com.algotrader.app.backtest.BacktestInstrumentType.INDEX,
        futuresNote = futuresBacktestNote(),
        futuresReady = futuresBacktestReady(),
        strategies = StrategyFactory().let { factory ->
            listOf(
                "moving_average_crossover",
                "rsi",
                "macd",
                "bollinger_bands",
                "donchian_channel",
                "donchian_ema",
                "cpr_ema"
            ).map { strategyId -> factory.create(strategyId) }
        }
    ) { configurations, capital, sizing, instrumentType, outOfSample, costModel ->
        selectedBacktestConfigurations = configurations
        selectedBacktestCapital = capital
        selectedBacktestSizing = sizing
        selectedBacktestCostModel = costModel
        runBacktest(instrumentType, outOfSample)
    }

    // Show persisted backtests underneath the configuration screen.
    // The BacktestJobStore is app-private persistent storage, so this
    // list survives navigation and app restarts.
    BacktestScreen.renderSavedTests(
        this,
        content,
        backtestJobStore.list(),
        onDeleteJob = { jobId -> confirmAndDeleteBacktest(jobId) }
    ) { jobId ->
        val job = backtestJobStore.get(jobId)

        if (job == null) {
            android.widget.Toast.makeText(
                this,
                "Saved backtest is no longer available.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return@renderSavedTests
        }

        activeBacktestJobId = job.id
        selectedBacktestJobId = job.id
        restoreBacktestJob(job)

        backtestProgressHandler.removeCallbacks(backtestProgressRunnable)

        if (
            job.status != BacktestJobStore.Status.COMPLETED &&
            job.status != BacktestJobStore.Status.FAILED &&
            job.status != BacktestJobStore.Status.CANCELLED
        ) {
            backtestProgressHandler.post(backtestProgressRunnable)
        }
    }
}

private fun restoreBacktestJob(job: BacktestJobStore.Job) {
    selectedBacktestConfigurations = job.strategies
    selectedBacktestCapital = job.initialCapital
    selectedBacktestSizing = job.positionSizing
    selectedBacktestCostModel = job.researchCostModel

    val strategyFactory = StrategyFactory()
    val strategies = job.strategies.map {
        strategyFactory.create(it)
    }

    when (job.status) {
        BacktestJobStore.Status.COMPLETED -> {
            if (renderedBacktestJobId == job.id) {
                return
            }

            /*
             * A multi-strategy launch creates one Job per strategy, all
             * carrying the same runId. Do not render a partial comparison
             * while sibling strategy Workers are still running.
             *
             * Legacy jobs have runId == null and therefore continue to
             * restore exactly as a single-job result.
             */
            if (job.runId != null) {
                val runJobs = backtestJobStore.list()
                    .filter { it.runId == job.runId }

                val terminalStatuses = setOf(
                    BacktestJobStore.Status.COMPLETED,
                    BacktestJobStore.Status.FAILED,
                    BacktestJobStore.Status.CANCELLED
                )

                val runIsTerminal = runJobs.isNotEmpty() &&
                    runJobs.all { it.status in terminalStatuses }

                if (!runIsTerminal) {
                    val completedCount = runJobs.count {
                        it.status == BacktestJobStore.Status.COMPLETED
                    }

                    BacktestScreen.updateRunning(
                        content,
                        if (runJobs.isNotEmpty()) {
                            ((completedCount * 100) / runJobs.size)
                                .coerceAtMost(99)
                        } else {
                            0
                        },
                        "Waiting for strategies: $completedCount/${runJobs.size} complete"
                    )
                    return
                }

                /*
                 * A launch the user cancelled is never presented as a
                 * (partial) comparison, even if some of its jobs had already
                 * completed before the cancel.
                 */
                if (runJobs.any { it.status == BacktestJobStore.Status.CANCELLED }) {
                    renderedBacktestJobId = job.id
                    renderBacktestFailure(
                        "This backtest was cancelled before it finished.",
                        "Backtest cancelled"
                    )
                    return
                }

                /*
                 * An out-of-sample run is only meaningful when every
                 * in-sample and out-of-sample job completed. Do not present a
                 * partial IS/OOS comparison as if it were complete.
                 */
                val didNotComplete = runJobs.count {
                    it.status != BacktestJobStore.Status.COMPLETED
                }

                if (
                    runJobs.any { it.sample != BacktestSample.FULL } &&
                    didNotComplete > 0
                ) {
                    renderedBacktestJobId = job.id
                    content.removeAllViews()

                    BacktestScreen.renderError(
                        this,
                        content,
                        message = "Incomplete OOS run \u2014 $didNotComplete of " +
                            "${runJobs.size} jobs did not complete.",
                        title = "Incomplete OOS run",
                        detail = "Out-of-sample results are shown only when every " +
                            "in-sample and out-of-sample job has completed."
                    ) {
                        activeBacktestJobId = null
                        renderedBacktestJobId = null
                        renderBacktestConfiguration()
                    }

                    return
                }
            }

            val resultsWithAccounting = try {
                val runJobs = if (job.runId != null) {
                    backtestJobStore.list()
                        .filter { it.runId == job.runId }
                        .sortedBy { it.createdAt }
                } else {
                    listOf(job)
                }

                runJobs
                    .filter { it.status == BacktestJobStore.Status.COMPLETED }
                    .flatMap { runJob ->
                        val runResults = backtestJobStore.getResults(runJob.id)
                        val runAccounting =
                            backtestJobStore.getFuturesAccounting(runJob.id)

                        runResults.mapIndexed { index, result ->
                            result to (
                                runAccounting.getOrNull(index)
                                    ?: BacktestJobStore.RestoredFuturesAccounting.Absent
                                )
                        }
                    }
            } catch (e: Exception) {
                content.removeAllViews()

                BacktestScreen.renderError(
                    this,
                    content,
                    message = "The saved results for this backtest could not be loaded.",
                    title = "Couldn't load results",
                    detail = "${e.javaClass.simpleName}: ${e.message}\n\n" +
                        backtestJobStore.debugResultsStorage(job.id)
                ) {
                    activeBacktestJobId = null
                    renderedBacktestJobId = null
                    renderBacktestConfiguration()
                }

                return
            }

            val results = resultsWithAccounting.map { it.first }
            val futuresAccounting = resultsWithAccounting.map { it.second }

            android.util.Log.i(
                "ALTRIXA_BACKTEST",
                "RESTORE_TEST job=${job.id} runId=${job.runId} " +
                    "status=${job.status} results=${results.size}"
            )

            if (results.isNotEmpty()) {
                renderedBacktestJobId = job.id
                isBacktestResultsScreen = true
                content.removeAllViews()
                BacktestScreen.renderResults(
                    this,
                    content,
                    instrumentName = BacktestFormat.instrumentName(job.instrumentSymbol),
                    timeframe = timeframeLabel(job.timeframe),
                    candleCount = if (
                        job.runId != null && job.sample != BacktestSample.FULL
                    ) {
                        // An OOS launch splits one candle set across its jobs:
                        // report the total, not this job's segment.
                        backtestJobStore.list()
                            .filter { it.runId == job.runId }
                            .groupBy { it.sample }
                            .values
                            .sumOf { it.first().candleCount }
                    } else {
                        job.candleCount
                    },
                    initialCapital = job.initialCapital,
                    positionSizing = job.positionSizing,
                    results = results,
                    job = job,
                    onRunAgain = {
                        activeBacktestJobId = null
                        renderedBacktestJobId = null
                        renderBacktestConfiguration()
                    },
                    futuresAccounting = futuresAccounting
                )
            } else {
                content.removeAllViews()

                BacktestScreen.renderError(
                    this,
                    content,
                    message = "This backtest finished, but no results were stored for it.",
                    title = "Results unavailable",
                    detail = "Job ID: ${job.id}\nStatus: ${job.status}\n" +
                        "Results loaded: ${results.size}\n\n" +
                        backtestJobStore.debugResultsStorage(job.id)
                ) {
                    activeBacktestJobId = null
                    renderedBacktestJobId = null
                    renderBacktestConfiguration()
                }
            }
        }

        BacktestJobStore.Status.FAILED -> {
            if (renderedBacktestJobId == job.id) {
                return
            }

            renderedBacktestJobId = job.id
            renderBacktestFailure(
                job.errorMessage ?: "Backtest failed."
            )
        }

        BacktestJobStore.Status.CANCELLED -> {
            if (renderedBacktestJobId == job.id) {
                return
            }

            renderedBacktestJobId = job.id
            renderBacktestFailure(
                "This backtest was cancelled before it finished.",
                "Backtest cancelled"
            )
        }

        else -> {
            renderedBacktestJobId = null

            content.removeAllViews()

            BacktestScreen.renderRunning(
                this,
                content,
                instrumentName = BacktestFormat.instrumentName(job.instrumentSymbol),
                timeframe = timeframeLabel(job.timeframe),
                candleCount = job.candleCount,
                strategies = strategies,
                progress = job.progress,
                currentStep = job.currentStep,
                onCancel = { cancelBacktestLaunch(job.id) }
            )
        }
    }
}

private fun addBacktestProgressView(
    progress: Int,
    currentStep: String
) {
    BacktestScreen.updateRunning(content, progress, currentStep)
}

private fun refreshBacktestJob(jobId: String) {
    if (!isBacktestScreenVisible || activeBacktestJobId != jobId) return

    val job = backtestJobStore.get(jobId) ?: return

    when (job.status) {
        BacktestJobStore.Status.COMPLETED -> {
            /*
             * A completed strategy does not necessarily mean the whole
             * multi-strategy run is finished. Keep polling until every
             * sibling job is terminal so the results screen receives the
             * complete comparison set.
             */
            if (job.runId != null) {
                val runJobs = backtestJobStore.list()
                    .filter { it.runId == job.runId }

                val terminalStatuses = setOf(
                    BacktestJobStore.Status.COMPLETED,
                    BacktestJobStore.Status.FAILED,
                    BacktestJobStore.Status.CANCELLED
                )

                val runIsTerminal = runJobs.isNotEmpty() &&
                    runJobs.all { it.status in terminalStatuses }

                if (!runIsTerminal) {
                    val completedCount = runJobs.count {
                        it.status == BacktestJobStore.Status.COMPLETED
                    }

                    val progress = if (runJobs.isNotEmpty()) {
                        ((completedCount * 100) / runJobs.size)
                            .coerceAtMost(99)
                    } else {
                        0
                    }

                    BacktestScreen.updateRunning(
                        content,
                        progress,
                        "Waiting for strategies: $completedCount/${runJobs.size} complete"
                    )
                    return
                }
            }

            backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
            isBacktestScreenVisible = true
            restoreBacktestJob(job)
        }

        BacktestJobStore.Status.FAILED,
        BacktestJobStore.Status.CANCELLED -> {
            backtestProgressHandler.removeCallbacks(backtestProgressRunnable)
            isBacktestScreenVisible = true
            restoreBacktestJob(job)
        }

        else -> {
            BacktestScreen.updateRunning(
                content,
                job.progress,
                job.currentStep
            )
        }
    }
}

private fun renderBacktestFailure(
    message: String,
    title: String = "Backtest failed"
) {
    content.removeAllViews()

    BacktestScreen.renderError(
        this,
        content,
        message,
        title
    ) {
        activeBacktestJobId = null
        renderedBacktestJobId = null
        renderBacktestConfiguration()
    }
}

private fun timeframeLabel(
    timeframe: com.algotrader.domain.Timeframe
): String {
    return when (timeframe) {
        com.algotrader.domain.Timeframe.MINUTE_1 -> "1m"
        com.algotrader.domain.Timeframe.MINUTE_5 -> "5m"
        com.algotrader.domain.Timeframe.MINUTE_15 -> "15m"
        com.algotrader.domain.Timeframe.MINUTE_30 -> "30m"
        com.algotrader.domain.Timeframe.HOUR_1 -> "1h"
        com.algotrader.domain.Timeframe.HOUR_4 -> "4h"
        com.algotrader.domain.Timeframe.DAY_1 -> "1D"
    }
}


private fun runBacktest(
    instrumentType: com.algotrader.app.backtest.BacktestInstrumentType =
        com.algotrader.app.backtest.BacktestInstrumentType.INDEX,
    outOfSample: Boolean = false
) {
    /*
     * Phase 3 Patch 6: the instrument, candle source and accounting lot size
     * are decided by one pure resolver. INDEX behaves exactly as before (the
     * selected index, its index candles, its index lot size). FUTURES uses the
     * exact contract discovered by Patch 5, its futures candles (loaded through
     * /futures/history), and only its authoritative contract lot size; it is
     * refused while that lot size is unknown.
     */
    val resolved = when (
        val resolution = com.algotrader.app.backtest.BacktestInstrumentResolver.resolve(
            instrumentType,
            selectedInstrument,
            selectedFuturesContract
        )
    ) {
        is com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution.Rejected -> {
            Toast.makeText(this, resolution.reason, Toast.LENGTH_LONG).show()
            return
        }

        is com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution.Resolved ->
            resolution
    }
    val backtestInstrument = resolved.instrument

    if (resolved.type == com.algotrader.app.backtest.BacktestInstrumentType.FUTURES &&
        selectedBacktestSizing !is PositionSizing.FixedLots
    ) {
        Toast.makeText(
            this,
            "Futures are traded in whole lots. Set the position size in lots.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val uiCandles = candlesByInstrument[
        "${backtestInstrument.backendSymbol}|$selectedTimeframe"
    ]

    if (uiCandles.isNullOrEmpty()) {
        Toast.makeText(
            this,
            if (resolved.type == com.algotrader.app.backtest.BacktestInstrumentType.FUTURES) {
                "Futures candles are not loaded yet. Select the futures contract " +
                    "on Home and wait for candles."
            } else {
                "Historical data is not loaded yet. Open Home first and wait for candles."
            },
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val domainCandles = uiCandles.mapNotNull { candle ->
        try {
            com.algotrader.domain.Candle(
                instrument = com.algotrader.domain.Instrument(
                    symbol = backtestInstrument.backendSymbol,
                    exchange = backtestInstrument.backendSymbol.substringBefore(":")
                ),
                timeframe = when (selectedTimeframe) {
                    "5m" -> com.algotrader.domain.Timeframe.MINUTE_5
                    "15m" -> com.algotrader.domain.Timeframe.MINUTE_15
                    "30m" -> com.algotrader.domain.Timeframe.MINUTE_30
                    "1h" -> com.algotrader.domain.Timeframe.HOUR_1
                    "1D" -> com.algotrader.domain.Timeframe.DAY_1
                    else -> com.algotrader.domain.Timeframe.MINUTE_5
                },
                timestamp = java.time.Instant.ofEpochSecond(candle.timestamp),
                open = candle.open.toDouble(),
                high = candle.high.toDouble(),
                low = candle.low.toDouble(),
                close = candle.close.toDouble(),
                volume = candle.volume.toDouble()
            )
        } catch (_: Exception) {
            null
        }
    }

    if (domainCandles.size < 50) {
        Toast.makeText(
            this,
            "Not enough candles for the selected strategies.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    if (selectedBacktestConfigurations.isEmpty()) {
        Toast.makeText(
            this,
            "Select at least one strategy before running the backtest.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val instrument = com.algotrader.domain.Instrument(
        symbol = backtestInstrument.backendSymbol,
        exchange = backtestInstrument.backendSymbol.substringBefore(":")
    )

    val timeframe = when (selectedTimeframe) {
        "5m" -> com.algotrader.domain.Timeframe.MINUTE_5
        "15m" -> com.algotrader.domain.Timeframe.MINUTE_15
        "30m" -> com.algotrader.domain.Timeframe.MINUTE_30
        "1h" -> com.algotrader.domain.Timeframe.HOUR_1
        "1D" -> com.algotrader.domain.Timeframe.DAY_1
        else -> com.algotrader.domain.Timeframe.MINUTE_5
    }

    /*
     * Each unit of work is one Job: one strategy on one candle set.
     *
     * Standard mode: one FULL unit per strategy, on all candles (unchanged).
     *
     * Out-of-sample mode: per strategy, one IN_SAMPLE unit that receives ONLY
     * the first 70% of the candles and one OUT_OF_SAMPLE unit that receives
     * ONLY the final 30%. The split is chronological, requires strictly
     * increasing timestamps, and is rejected here, before any Job is created
     * or any candle is saved, if the data does not qualify.
     */
    val workUnits = when (
        val plan = BacktestLaunchPlan.plan(
            selectedBacktestConfigurations,
            domainCandles,
            outOfSample
        )
    ) {
        is BacktestLaunchPlan.Outcome.Rejected -> {
            Toast.makeText(this, plan.reason, Toast.LENGTH_LONG).show()
            return
        }

        is BacktestLaunchPlan.Outcome.Planned -> plan.units
    }

    try {
        val workManager = WorkManager.getInstance(applicationContext)
        val factory = StrategyFactory()

        /*
         * All strategies selected in one Backtest launch share a durable
         * runId. Each strategy still remains an independent Job/Worker.
         *
         * This lets the results screen reconstruct the complete strategy
         * comparison without relying on timestamps, strategy names, or
         * other heuristics.
         */
        val backtestRunId = java.util.UUID.randomUUID().toString()

        // P3P11: one cost model for the whole launch, so every strategy and
        // both the in-sample and out-of-sample jobs use identical assumptions.
        val launchCostModel = selectedBacktestCostModel

        /*
         * One selected strategy = one persisted Job + one WorkManager task.
         *
         * This deliberately does NOT create a single multi-strategy job.
         * Each strategy therefore has its own:
         * - Job ID
         * - persisted progress/checkpoint
         * - result
         * - WorkManager work ID
         * - unique WorkManager name
         */
        val jobs = workUnits.map { unit ->
            val configuration = unit.configuration

            val job = backtestJobStore.create(
                instrument = instrument,
                timeframe = timeframe,
                strategies = listOf(configuration),
                initialCapital = selectedBacktestCapital,
                positionSizing = selectedBacktestSizing,
                candleCount = unit.candles.size,
                instrumentType = resolved.type,
                futuresContract = resolved.futuresContract,
                runId = backtestRunId,
                sample = unit.sample,
                researchCostModel = launchCostModel
            )

            // Only this unit's own candles are persisted for the job, so an
            // in-sample job can never read out-of-sample candles (and vice versa).
            backtestJobStore.saveCandles(job.id, unit.candles)

            val request = OneTimeWorkRequestBuilder<BacktestWorker>()
                .setInputData(
                    workDataOf(
                        BacktestWorker.KEY_JOB_ID to job.id
                    )
                )
                .setExpedited(
                    androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .addTag("altrixa_backtest")
                .addTag("altrixa_backtest_${job.id}")
                .build()

            workManager.enqueueUniqueWork(
                "altrixa_backtest_${job.id}",
                ExistingWorkPolicy.REPLACE,
                request
            )

            android.util.Log.d(
                "ALTRIXA_BACKTEST",
                "Queued independent backtest: " +
                    "job=${job.id}, strategy=${configuration.strategyId}, " +
                    "sample=${unit.sample}, candles=${unit.candles.size}, " +
                    "workId=${request.id}"
            )

            job
        }

        if (jobs.isEmpty()) {
            throw IllegalStateException("No backtest jobs were created")
        }

        /*
         * Keep the first created job as the foreground/viewed job.
         * The Active Backtests renderer obtains the complete job list
         * from BacktestJobStore, so all independent jobs remain visible.
         */
        val firstJob = jobs.first()
        selectedBacktestJobId = firstJob.id
        activeBacktestJobId = firstJob.id
        renderedBacktestJobId = null
        isBacktestResultsScreen = false
        isBacktestScreenVisible = true
        backtestProgressHandler.removeCallbacks(backtestProgressRunnable)

        content.removeAllViews()

        val firstStrategy = factory.create(firstJob.strategies.first())

        BacktestScreen.renderRunning(
            this,
            content,
            instrumentName = backtestInstrument.displayName,
            timeframe = selectedTimeframe,
            candleCount = domainCandles.size,
            strategies = listOf(firstStrategy),
            onCancel = { cancelBacktestLaunch(firstJob.id) }
        )

        addBacktestProgressView(
            progress = 0,
            currentStep = if (jobs.size == 1) {
                "Queued"
            } else {
                "Queued ${jobs.size} independent backtests"
            }
        )

        /*
         * WorkManager owns execution independently of the Activity.
         * The persisted BacktestJobStore state is refreshed by
         * backtestProgressRunnable, so no blocking WorkManager query
         * is needed on the Activity/UI thread.
         */

        backtestProgressHandler.post(backtestProgressRunnable)


    } catch (e: Exception) {
        android.util.Log.e(
            "ALTRIXA_BACKTEST",
            "Unable to start backtest jobs",
            e
        )

        Toast.makeText(
            this,
            "Unable to start backtest: ${e.message ?: e.javaClass.simpleName}",
            Toast.LENGTH_LONG
        ).show()
    }
}

}

// Indicator math — computed locally from real candle closes, never fetched.
// ---------------------------------------------------------------------------

fun computeEma(values: List<Float>, period: Int): List<Float> {
    if (values.isEmpty()) return emptyList()
    val result = MutableList(values.size) { 0f }
    val k = 2f / (period + 1)
    result[0] = values[0]
    for (i in 1 until values.size) {
        result[i] = values[i] * k + result[i - 1] * (1 - k)
    }
    return result
}

fun computeRsi(closes: List<Float>, period: Int = 14): List<Float> {
    val size = closes.size
    val rsi = MutableList(size) { 50f }
    if (size <= period) return rsi

    var gainSum = 0f
    var lossSum = 0f
    for (i in 1..period) {
        val diff = closes[i] - closes[i - 1]
        if (diff >= 0) gainSum += diff else lossSum -= diff
    }

    var avgGain = gainSum / period
    var avgLoss = lossSum / period

    fun rsiFrom(avgG: Float, avgL: Float): Float {
        if (avgL == 0f) return 100f
        val rs = avgG / avgL
        return 100f - 100f / (1f + rs)
    }

    for (i in 0..period) rsi[i] = rsiFrom(avgGain, avgLoss)

    for (i in period + 1 until size) {
        val diff = closes[i] - closes[i - 1]
        val gain = if (diff > 0) diff else 0f
        val loss = if (diff < 0) -diff else 0f
        avgGain = (avgGain * (period - 1) + gain) / period
        avgLoss = (avgLoss * (period - 1) + loss) / period
        rsi[i] = rsiFrom(avgGain, avgLoss)
    }

    return rsi
}

fun formatVolume(v: Float): String {
    return when {
        v >= 1_000_000f -> String.format(Locale.US, "%.2fM", v / 1_000_000f)
        v >= 1_000f -> String.format(Locale.US, "%.2fK", v / 1_000f)
        else -> String.format(Locale.US, "%.0f", v)
    }
}

// ---------------------------------------------------------------------------
// Custom views — dark, TradingView-style candlestick chart + volume + RSI.
// ---------------------------------------------------------------------------

// Note: plain `val`, not `const val` — `.toInt()` is a function call, not a
// compile-time constant expression, so `const val` would fail to compile here.
private val CHART_BG = 0xFF0D1117.toInt()
private val CHART_GRID = 0xFF262B33.toInt()
private val BULLISH_COLOR = 0xFF26A65B.toInt()
private val BEARISH_COLOR = 0xFFEF5350.toInt()
private val EMA20_COLOR = 0xFF29B6F6.toInt()
private val EMA50_COLOR = 0xFFFFA726.toInt()
private val PRICE_LINE_COLOR = 0xFF4DD0E1.toInt()
private val AXIS_TEXT_COLOR = 0xFF8B93A1.toInt()


class TradingChartView(context: android.content.Context) : View(context) {

    private var candles: List<Candle> = emptyList()
    private var ema20: List<Float> = emptyList()
    private var ema50: List<Float> = emptyList()
    private var isDaily = false
    private var latestPrice: Float? = null
    private var candleIntervalSeconds = 300L

    // Phase 3.2: signals for the currently selected strategy.
    // Kept independent from chart navigation/live-candle state.
    private var strategySignals: List<com.algotrader.strategy.Signal> = emptyList()

    // Live countdown for the currently forming candle.
    // The timer is rendered beside the live LTP marker and refreshed
    // once per second without changing any chart interaction state.
    private val candleCountdownRunnable = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L)
        }
    }

    private var visibleCount = 80
    private var endIndex = 0
    private var crosshairIndex = -1

    // Explicit live candle. This is kept separate from the historical
    // dataset so live ticks are always visible even when the backend's
    // latest historical candle belongs to the previous timeframe bucket.
    private var liveCandle: Candle? = null

    // Gesture state.
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragRemainderX = 0f
    private var isDragging = false

    // Long-press crosshair mode.
    private var isLongPressing = false
    private var longPressCancelled = false

    // Independent Y-axis viewport.
    // 1.0 = normal price scale.
    // >1 = zoomed into price movement.
    // <1 = zoomed out.
    private var priceZoom = 1f

    // Price-axis vertical translation, expressed as a fraction of the
    // currently visible price range.
    private var pricePanFraction = 0f

    // Gesture anchor values used for two-dimensional navigation.
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    // Double-tap resets the chart viewport to the latest candles.
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // Automatically follow the newest candle until the user manually pans
    // backward. Live ticks must never force a manually selected chart position.
    private var followLatest = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT_COLOR
        textSize = 26f
    }

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 7f), 0f)
    }

    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE151A21.toInt()
        style = Paint.Style.FILL
    }

    private val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 19f
        textAlign = Paint.Align.LEFT
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(candleCountdownRunnable)
        post(candleCountdownRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(candleCountdownRunnable)
        super.onDetachedFromWindow()
    }

    private fun candleCountdownText(): String {
        val interval = candleIntervalSeconds.coerceAtLeast(60L)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val bucket = candleBucketTimestamp(nowSeconds, interval)
        val remaining = (bucket + interval - nowSeconds)
            .coerceIn(0L, interval)

        /*
         * NSE regular session ends at 15:30 IST.
         * After the session closes, there is no active intraday
         * candle countdown to display.
         */
        val marketZone =
            java.time.ZoneId.of("Asia/Kolkata")

        val nowIst =
            java.time.Instant
                .ofEpochMilli(System.currentTimeMillis())
                .atZone(marketZone)

        val marketClose =
            nowIst
                .toLocalDate()
                .atTime(15, 30)
                .atZone(marketZone)

        if (
            nowIst.isAfter(marketClose) ||
            nowIst.isEqual(marketClose)
        ) {
            return ""
        }

        val minutes = remaining / 60L
        val seconds = remaining % 60L

        return String.format(
            Locale.US,
            "%02d:%02d",
            minutes,
            seconds
        )
    }

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScaleBegin(
                    detector: ScaleGestureDetector
                ): Boolean {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    isDragging = false
                    dragRemainderX = 0f
                    return true
                }

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    if (candles.isEmpty()) return false

                    val left = 8f
                    val right = width - 96f
                    val chartWidth =
                        (right - left).coerceAtLeast(1f)

                    val oldCount =
                        visibleCount.coerceIn(
                            2,
                            candles.size
                        )

                    val oldStart =
                        (endIndex - oldCount + 1)
                            .coerceAtLeast(0)

                    // Candle currently underneath the pinch centre.
                    val focusRatio =
                        (
                            (detector.focusX - left) /
                                chartWidth
                        ).coerceIn(0f, 1f)

                    val focusIndexFloat =
                        oldStart +
                            focusRatio *
                            (oldCount - 1)

                    // Android's scaleFactor:
                    //   > 1 = fingers moving apart = zoom IN
                    //   < 1 = fingers moving together = zoom OUT
                    val rawScaleFactor = detector.scaleFactor

                    android.util.Log.d(
                        "ALTRIXA_PINCH",
                        "scaleFactor=$rawScaleFactor focusX=${detector.focusX}"
                    )

                    val factor =
                        rawScaleFactor
                            .coerceIn(0.80f, 1.25f)

                    // Explicit pinch direction:
                    // fingers apart  -> zoom IN  -> fewer candles
                    // fingers together -> zoom OUT -> more candles
                    val zoomIn = detector.scaleFactor > 1f

                    val zoomAmount =
                        if (zoomIn) {
                            1f / detector.scaleFactor
                        } else {
                            1f / detector.scaleFactor
                        }

                    val newCount =
                        (
                            oldCount * zoomAmount
                        )
                            .roundToInt()
                            .coerceIn(
                                12,
                                candles.size
                            )

                    if (newCount != oldCount) {

                        // Keep the candle under the fingers
                        // at the same horizontal position.
                        val desiredStart =
                            (
                                focusIndexFloat -
                                    focusRatio *
                                    (newCount - 1)
                            ).roundToInt()

                        val maxStart =
                            (
                                candles.size -
                                    newCount
                            ).coerceAtLeast(0)

                        val newStart =
                            desiredStart.coerceIn(
                                0,
                                maxStart
                            )

                        endIndex =
                            (
                                newStart +
                                    newCount -
                                    1
                            ).coerceIn(
                                newCount - 1,
                                candles.lastIndex
                            )

                        visibleCount = newCount
                    }

                    // Pinch controls horizontal/time zoom only.
                    // Price-axis zoom remains independent so the chart
                    // does not vertically expand or compress unexpectedly.

                    // Pinching is an explicit viewport interaction.
                    // Do not let incoming live ticks move the viewport
                    // while the user is zooming.
                    followLatest = false

                    crosshairIndex =
                        indexForX(
                            detector.focusX,
                            left,
                            chartWidth
                        )

                    invalidate()
                    return true
                }

                override fun onScaleEnd(
                    detector: ScaleGestureDetector
                ) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragRemainderX = 0f
                }
            }
        )


    private val intradayFormat =
        SimpleDateFormat("dd MMM HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }

    private val dailyFormat =
        SimpleDateFormat("dd MMM yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }

    fun setData(
        candles: List<Candle>,
        ema20: List<Float>,
        ema50: List<Float>,
        isDaily: Boolean,
        candleIntervalSeconds: Long = 300L,
        preserveViewport: Boolean = false
    ) {
        this.candles = candles
        this.ema20 = ema20
        this.ema50 = ema50
        this.isDaily = isDaily
        this.candleIntervalSeconds = candleIntervalSeconds.coerceAtLeast(60L)

        /*
         * Initial loads and explicit timeframe changes establish a
         * fresh latest-candle viewport.
         *
         * Background OHLCV refreshes must not destroy a viewport that
         * the user is actively dragging/zooming.
         */
        if (!preserveViewport) {
            // Keep the default view readable on phone-sized screens.
            visibleCount =
                minOf(50, candles.size.coerceAtLeast(1))

            endIndex = candles.lastIndex
            crosshairIndex = -1
            followLatest = true
            liveCandle = null
            dragRemainderX = 0f
            isDragging = false
            priceZoom = 1f
            pricePanFraction = 0f
        } else {
            /*
             * Preserve the user's navigation state while replacing
             * the underlying candle data.
             */
            visibleCount =
                visibleCount.coerceIn(
                    1,
                    candles.size.coerceAtLeast(1)
                )

            endIndex =
                if (followLatest) {
                    candles.lastIndex
                } else {
                    endIndex.coerceIn(
                        visibleCount - 1,
                        candles.lastIndex
                    )
                }

            crosshairIndex =
                crosshairIndex.coerceIn(
                    -1,
                    candles.lastIndex
                )

            /*
             * Preserve liveCandle during a background history refresh.
             * The live candle belongs to the WebSocket stream and must not
             * be discarded when historical data is refreshed.
             */
        }

        invalidate()
    }

    private fun candleBucketTimestamp(
        timestampSeconds: Long,
        interval: Long
    ): Long {
        if (interval >= 86400L) {
            val zone =
                java.time.ZoneId.of("Asia/Kolkata")

            return java.time.Instant
                .ofEpochSecond(timestampSeconds)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toEpochSecond()
        }

        /*
         * P3P10:
         * NSE intraday buckets are anchored at 09:15 IST.
         *
         * 30m -> 09:15, 09:45, 10:15, ...
         * 60m -> 09:15, 10:15, 11:15, ...
         */
        val zone = java.time.ZoneId.of("Asia/Kolkata")

        val localDate =
            java.time.Instant
                .ofEpochSecond(timestampSeconds)
                .atZone(zone)
                .toLocalDate()

        val sessionStart =
            localDate
                .atTime(9, 15)
                .atZone(zone)
                .toEpochSecond()

        if (timestampSeconds >= sessionStart) {
            val offset =
                timestampSeconds - sessionStart

            return sessionStart +
                (offset / interval) * interval
        }

        return (timestampSeconds / interval) * interval
    }

    fun setSignals(signals: List<com.algotrader.strategy.Signal>) {
        strategySignals = signals
        invalidate()
    }

    fun setLatestPrice(
        price: Float,
        timestampSeconds: Long? = null
    ) {
        if (!price.isFinite() || price <= 0f) return

        latestPrice = price

        val interval =
            candleIntervalSeconds.coerceAtLeast(60L)

        /*
         * Prefer the authoritative FYERS market timestamp.
         * Fall back to device time only when no market timestamp exists.
         */
        val quoteTimestamp =
            timestampSeconds
                ?.takeIf { it > 0L }
                ?: (System.currentTimeMillis() / 1000L)

        val bucketTimestamp =
            candleBucketTimestamp(
                quoteTimestamp,
                interval
            )

        /*
         * No historical candles yet: start a standalone live candle.
         */
        if (candles.isEmpty()) {
            liveCandle =
                Candle(
                    timestamp = bucketTimestamp,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = 0f
                )

            visibleCount = 1
            endIndex = 0
            followLatest = true

            invalidate()
            return
        }

        val lastHistorical = candles.last()

        val lastHistoricalBucket =
            candleBucketTimestamp(
                lastHistorical.timestamp,
                interval
            )

        android.util.Log.d(
            "ALTRIXA_P3_LIVE",
            "LIVE_INPUT " +
                "price=$price " +
                "quoteTs=$quoteTimestamp " +
                "bucketTs=$bucketTimestamp " +
                "interval=$interval " +
                "historicalLastTs=${lastHistorical.timestamp} " +
                "historicalLastBucket=$lastHistoricalBucket " +
                "existingLiveTs=${liveCandle?.timestamp ?: 0L} " +
                "followLatest=$followLatest " +
                "visibleCount=$visibleCount " +
                "endIndex=$endIndex"
        )

        /*
         * P3 diagnostic: expose the live-candle routing decision in the
         * existing header status so it can be verified directly on-device
         * without ADB/logcat.
         */
        /*
         * The current live bucket already exists.
         *
         * Keep updating the same candle rather than committing it to the
         * historical list on every incoming WebSocket tick.
         */
        val existingLive = liveCandle

        if (
            existingLive != null &&
            existingLive.timestamp == bucketTimestamp
        ) {
            liveCandle =
                existingLive.copy(
                    high = maxOf(existingLive.high, price),
                    low = minOf(existingLive.low, price),
                    close = price
                )

            if (followLatest) {
                endIndex = candles.lastIndex
            }

            invalidate()
            return
        }

        /*
         * If historical data already contains the current bucket, update
         * that authoritative candle directly.
         */
        if (lastHistoricalBucket == bucketTimestamp) {

            val existingLive = liveCandle

            val updated =
                lastHistorical.copy(
                    high =
                        maxOf(
                            lastHistorical.high,
                            existingLive?.high ?: price,
                            price
                        ),
                    low =
                        minOf(
                            lastHistorical.low,
                            existingLive?.low ?: price,
                            price
                        ),
                    close = price
                )

            candles =
                candles.dropLast(1) + updated

            liveCandle = null

        } else {

            /*
             * A new timeframe bucket has started. Commit the previous
             * live candle, then start the new candle from the first actual
             * observed market price.
             */
            liveCandle?.let { completed ->
                if (completed.timestamp > lastHistorical.timestamp) {
                    candles = candles + completed
                }
            }

            liveCandle =
                Candle(
                    timestamp = bucketTimestamp,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    volume = 0f
                )
        }

        if (followLatest) {
            endIndex = candles.lastIndex
        }

        invalidate()
    }

    private val longPressRunnable = Runnable {
        if (
            !longPressCancelled &&
            !isDragging
        ) {
            isLongPressing = true

            val left = 8f
            val right = width - 96f
            val chartWidth =
                (right - left).coerceAtLeast(1f)

            crosshairIndex =
                indexForX(
                    lastTouchX,
                    left,
                    chartWidth
                )

            parent?.requestDisallowInterceptTouchEvent(true)

            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        // Always pass every pointer event to the scale detector.
        scaleDetector.onTouchEvent(event)

        if (candles.isEmpty() && liveCandle == null) {
            return true
        }

        val left = 8f
        val right = width - 96f
        val chartWidth =
            (right - left).coerceAtLeast(1f)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                lastTouchX = event.x
                lastTouchY = event.y

                gestureStartX = event.x
                gestureStartY = event.y

                dragRemainderX = 0f
                isDragging = false
                isLongPressing = false
                longPressCancelled = false

                removeCallbacks(longPressRunnable)
                postDelayed(
                    longPressRunnable,
                    500L
                )

                // A fresh touch immediately selects the candle.
                // Movement beyond touch-slop will transition this
                // gesture into chart navigation.
                crosshairIndex =
                    indexForX(
                        event.x,
                        left,
                        chartWidth
                    )

                parent?.requestDisallowInterceptTouchEvent(true)

                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {

                // Multi-touch belongs to the scale detector.
                longPressCancelled = true
                removeCallbacks(longPressRunnable)

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                // Pinch gestures are handled exclusively by the
                // ScaleGestureDetector.
                if (
                    scaleDetector.isInProgress ||
                    event.pointerCount > 1
                ) {
                    longPressCancelled = true
                    removeCallbacks(longPressRunnable)

                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                val dx =
                    event.x - lastTouchX

                val dy =
                    event.y - lastTouchY

                val totalDx =
                    event.x - gestureStartX

                val totalDy =
                    event.y - gestureStartY

                /*
                 * Long-press mode:
                 * keep the viewport fixed and move only the crosshair.
                 */
                if (isLongPressing) {

                    crosshairIndex =
                        indexForX(
                            event.x,
                            left,
                            chartWidth
                        )

                    lastTouchX = event.x
                    lastTouchY = event.y

                    parent?.requestDisallowInterceptTouchEvent(true)

                    invalidate()
                    return true
                }

                /*
                 * Before the drag threshold is crossed, this remains
                 * a crosshair interaction rather than chart panning.
                 */
                if (!isDragging) {

                    val movement =
                        kotlin.math.hypot(
                            totalDx,
                            totalDy
                        )

                    if (movement < 8f) {

                        crosshairIndex =
                            indexForX(
                                event.x,
                                left,
                                chartWidth
                            )

                        invalidate()
                        return true
                    }

                    /*
                     * Touch-slop crossed: switch permanently into
                     * viewport navigation for this gesture.
                     */
                    isDragging = true
                    followLatest = false
                    crosshairIndex = -1

                    longPressCancelled = true
                    removeCallbacks(longPressRunnable)

                    dragRemainderX = 0f

                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                // --------------------------------------------------------
                // X AXIS — time navigation.
                // --------------------------------------------------------
                dragRemainderX += dx

                val slot =
                    chartWidth /
                        visibleCount
                            .coerceAtLeast(1)
                            .toFloat()

                if (slot > 0f) {

                    val candleShift =
                        (-dragRemainderX / slot)
                            .toInt()

                    if (candleShift != 0) {

                        endIndex =
                            (
                                endIndex +
                                    candleShift
                            ).coerceIn(
                                visibleCount - 1,
                                candles.lastIndex
                            )

                        dragRemainderX -=
                            candleShift * -slot
                    }
                }

                // --------------------------------------------------------
                // Y AXIS — price/value navigation.
                // --------------------------------------------------------
                val historicalRange =
                    currentRawPriceRange()

                if (historicalRange > 0f) {

                    val chartHeight =
                        (height - 46f)
                            .coerceAtLeast(1f)

                    pricePanFraction +=
                        dy / chartHeight

                    pricePanFraction =
                        pricePanFraction.coerceIn(
                            -3f,
                            3f
                        )
                }

                lastTouchX = event.x
                lastTouchY = event.y

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                removeCallbacks(longPressRunnable)

                parent?.requestDisallowInterceptTouchEvent(false)

                val wasDragging =
                    isDragging

                val wasLongPressing =
                    isLongPressing

                isDragging = false
                isLongPressing = false
                longPressCancelled = true
                dragRemainderX = 0f

                if (
                    event.actionMasked ==
                    MotionEvent.ACTION_UP
                ) {

                    val now =
                        event.eventTime

                    val tapDistance =
                        kotlin.math.hypot(
                            event.x - lastTapX,
                            event.y - lastTapY
                        )

                    val isDoubleTap =
                        !wasDragging &&
                        !wasLongPressing &&
                        now - lastTapTime in 1L..350L &&
                        tapDistance <= 48f

                    if (isDoubleTap) {

                        // --------------------------------------------
                        // RESET VIEW
                        // --------------------------------------------
                        visibleCount =
                            minOf(
                                50,
                                candles.size.coerceAtLeast(1)
                            )

                        endIndex =
                            candles.lastIndex

                        priceZoom = 1f
                        pricePanFraction = 0f
                        followLatest = true
                        crosshairIndex = -1
                        dragRemainderX = 0f

                        lastTapTime = 0L
                        lastTapX = event.x
                        lastTapY = event.y

                        invalidate()
                        return true
                    }

                    if (wasLongPressing) {

                        /*
                         * Long press is a temporary inspection mode.
                         * Release exits it cleanly.
                         */
                        crosshairIndex = -1

                    } else if (!wasDragging) {

                        /*
                         * A tap releases the selected candle and
                         * leaves the crosshair visible.
                         */
                        crosshairIndex =
                            indexForX(
                                event.x,
                                left,
                                chartWidth
                            )

                    } else {

                        /*
                         * A completed drag remains a navigation
                         * gesture. Do not accidentally select a
                         * different candle on release.
                         */
                        crosshairIndex = -1
                    }

                    lastTapTime = now
                    lastTapX = event.x
                    lastTapY = event.y
                }

                invalidate()
                return true
            }
        }

        return true
    }

    private fun currentRawPriceRange(): Float {
        if (candles.isEmpty()) return 0f

        val count =
            visibleCount.coerceIn(
                1,
                candles.size
            )

        val startIndex =
            (endIndex - count + 1)
                .coerceAtLeast(0)

        val endVisible =
            (startIndex + count - 1)
                .coerceAtMost(candles.lastIndex)

        val visible =
            candles.subList(
                startIndex,
                endVisible + 1
            )

        val min =
            visible.minOf { it.low }

        val max =
            visible.maxOf { it.high }

        return (max - min).coerceAtLeast(0.01f)
    }

    private fun indexForX(
        x: Float,
        left: Float,
        chartWidth: Float
    ): Int {
        if (candles.isEmpty()) return -1

        val count =
            visibleCount.coerceIn(
                1,
                candles.size
            )

        val startIndex =
            (endIndex - count + 1)
                .coerceAtLeast(0)

        val slot =
            chartWidth / count.toFloat()

        val localIndex =
            ((x - left) / slot)
                .toInt()
                .coerceIn(
                    0,
                    count - 1
                )

        return (
            startIndex + localIndex
        ).coerceIn(
            0,
            candles.lastIndex
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // TEMP DIAG (P3P10): remove after viewport investigation.
        run {
            val dCount =
                visibleCount.coerceIn(1, candles.size.coerceAtLeast(1))
            val dStart =
                (endIndex - dCount + 1).coerceAtLeast(0)
            val dEnd =
                (dStart + dCount - 1).coerceAtMost(candles.lastIndex)
            android.util.Log.d(
                "ALTRIXA_P3P10",
                "DRAW_RANGE " +
                    "candles.size=${candles.size} " +
                    "startIndex=$dStart " +
                    "endIndex=$endIndex " +
                    "visibleCount=$visibleCount " +
                    "followLatest=$followLatest " +
                    "firstVisibleTs=${candles.getOrNull(dStart)?.timestamp ?: 0L} " +
                    "lastVisibleTs=${candles.getOrNull(dEnd)?.timestamp ?: 0L} " +
                    "lastCandleTs=${candles.lastOrNull()?.timestamp ?: 0L} " +
                    "liveTs=${liveCandle?.timestamp ?: 0L}"
            )
        }

        canvas.drawColor(CHART_BG)

        if (candles.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "Loading chart…",
                width / 2f,
                height / 2f,
                textPaint
            )
            return
        }

        val rightMargin = 96f
        val bottomMargin = 34f
        val left = 8f
        val right = width - rightMargin
        val top = 12f
        val bottom = height - bottomMargin

        val count =
            visibleCount.coerceIn(
                1,
                candles.size
            )

        val startIndex =
            (endIndex - count + 1)
                .coerceAtLeast(0)

        val endVisible =
            (startIndex + count - 1)
                .coerceAtMost(candles.lastIndex)

        val historicalVisibleCandles =
            candles.subList(
                startIndex,
                endVisible + 1
            )

        val visibleCandles =
            if (
                followLatest &&
                liveCandle != null &&
                liveCandle!!.timestamp >
                    candles.last().timestamp
            ) {
                historicalVisibleCandles + liveCandle!!
            } else {
                historicalVisibleCandles
            }

        var minPrice =
            visibleCandles.minOf { it.low }

        var maxPrice =
            visibleCandles.maxOf { it.high }

        ema20
            .drop(startIndex)
            .take(visibleCandles.size)
            .forEach { value ->
                if (value > 0f) {
                    minPrice = minOf(minPrice, value)
                    maxPrice = maxOf(maxPrice, value)
                }
            }

        ema50
            .drop(startIndex)
            .take(visibleCandles.size)
            .forEach { value ->
                if (value > 0f) {
                    minPrice = minOf(minPrice, value)
                    maxPrice = maxOf(maxPrice, value)
                }
            }

        // Live price is already represented by the live candle when
        // available. Do not let every tick reset the user's Y viewport.
        val rawMinPrice = minPrice
        val rawMaxPrice = maxPrice

        val rawRange =
            (rawMaxPrice - rawMinPrice)
                .coerceAtLeast(0.01f)

        // Base padding keeps candles from touching the chart edges.
        val padding =
            (rawRange * 0.08f)
                .coerceAtLeast(0.5f)

        val baseMin =
            rawMinPrice - padding

        val baseMax =
            rawMaxPrice + padding

        val baseRange =
            (baseMax - baseMin)
                .coerceAtLeast(0.01f)

        // Y zoom changes the amount of price space visible.
        val viewportRange =
            (baseRange / priceZoom)
                .coerceAtLeast(0.01f)

        val baseCenter =
            (baseMin + baseMax) / 2f

        // Positive panFraction means the viewport follows the user's
        // vertical drag rather than continuously snapping to auto-fit.
        val center =
            baseCenter +
            pricePanFraction * baseRange

        minPrice =
            center - viewportRange / 2f

        maxPrice =
            center + viewportRange / 2f

        val range =
            (maxPrice - minPrice)
                .coerceAtLeast(0.01f)

        fun priceY(price: Float): Float =
            bottom -
                (price - minPrice) /
                range *
                (bottom - top)

        // Grid.
        paint.style = Paint.Style.STROKE
        paint.color = CHART_GRID
        paint.strokeWidth = 1f

        textPaint.color = AXIS_TEXT_COLOR
        textPaint.textAlign = Paint.Align.LEFT

        for (i in 0..4) {
            val y =
                top +
                (bottom - top) *
                i / 4f

            canvas.drawLine(
                left,
                y,
                right,
                y,
                paint
            )

            val price =
                maxPrice -
                (maxPrice - minPrice) *
                i / 4f

            canvas.drawText(
                String.format(
                    Locale.US,
                    "%,.2f",
                    price
                ),
                right + 8f,
                y + 9f,
                textPaint
            )
        }

        val slot =
            (right - left) /
            visibleCandles.size.coerceAtLeast(1).toFloat()

        val bodyWidth =
            (slot * 0.68f)
                .coerceAtLeast(3f)

        // Candles.
        visibleCandles.forEachIndexed { index, candle ->

            val x =
                left +
                slot * index +
                slot / 2f

            val bullish =
                candle.close >= candle.open

            paint.color =
                if (bullish)
                    BULLISH_COLOR
                else
                    BEARISH_COLOR

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f

            canvas.drawLine(
                x,
                priceY(candle.high),
                x,
                priceY(candle.low),
                paint
            )

            paint.style = Paint.Style.FILL

            val bodyTop =
                priceY(
                    maxOf(
                        candle.open,
                        candle.close
                    )
                )

            val bodyBottom =
                priceY(
                    minOf(
                        candle.open,
                        candle.close
                    )
                )

            canvas.drawRect(
                x - bodyWidth / 2f,
                bodyTop,
                x + bodyWidth / 2f,
                maxOf(
                    bodyBottom,
                    bodyTop + 2f
                ),
                paint
            )
        }

        // Explicit live-candle outline. This makes the active FYERS candle
        // visually obvious even when its body is only a few pixels high.
        liveCandle?.let { live ->
            if (
                visibleCandles.isNotEmpty() &&
                live.timestamp >= visibleCandles.last().timestamp
            ) {
                val liveIndex = visibleCandles.lastIndex
                val x =
                    left +
                    slot * liveIndex +
                    slot / 2f

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color =
                    if (live.close >= live.open)
                        BULLISH_COLOR
                    else
                        BEARISH_COLOR

                val liveTop =
                    priceY(maxOf(live.open, live.close))

                val liveBottom =
                    priceY(minOf(live.open, live.close))

                canvas.drawRect(
                    x - bodyWidth / 2f,
                    liveTop,
                    x + bodyWidth / 2f,
                    maxOf(liveBottom, liveTop + 2f),
                    paint
                )
            }
        }

        // EMA20.
        drawEmaLine(
            canvas,
            ema20.drop(startIndex).take(visibleCandles.size),
            slot,
            left,
            ::priceY,
            EMA20_COLOR
        )

        // EMA50.
        drawEmaLine(
            canvas,
            ema50.drop(startIndex).take(visibleCandles.size),
            slot,
            left,
            ::priceY,
            EMA50_COLOR
        )

        // Live price.
        latestPrice?.let { price ->

            val y = priceY(price)

            // Live LTP is intentionally drawn independently from the
            // completed candle series. Do NOT modify candle OHLC data.
            dashedPaint.color =
                PRICE_LINE_COLOR

            canvas.drawLine(
                left,
                y,
                right,
                y,
                dashedPaint
            )

            // Live LTP badge.
            // This represents the real-time quote, not the last candle close.
            paint.style = Paint.Style.FILL
            paint.color = PRICE_LINE_COLOR

            canvas.drawRoundRect(
                right,
                y - 17f,
                width.toFloat(),
                y + 17f,
                4f,
                4f,
                paint
            )

            textPaint.color = Color.BLACK
            textPaint.textAlign =
                Paint.Align.LEFT

            canvas.drawText(
                String.format(
                    Locale.US,
                    "%,.2f",
                    price
                ),
                right + 7f,
                y + 8f,
                textPaint
            )

            // Candle countdown badge.
            // Keep it close to the LTP while clamping it inside the
            // chart so it never gets clipped at the top/bottom.
            val timerText = candleCountdownText()
            val timerWidth = 58f
            val timerHeight = 22f

            val timerLeft =
                (width.toFloat() - timerWidth)
                    .coerceAtLeast(right)

            val timerCenterY =
                (y - 29f)
                    .coerceIn(
                        top + timerHeight / 2f,
                        bottom - timerHeight / 2f
                    )

            paint.color = 0xDD151A21.toInt()

            canvas.drawRoundRect(
                timerLeft,
                timerCenterY - timerHeight / 2f,
                width.toFloat(),
                timerCenterY + timerHeight / 2f,
                6f,
                6f,
                paint
            )

            textPaint.color = PRICE_LINE_COLOR
            textPaint.textSize = 18f
            textPaint.textAlign = Paint.Align.CENTER

            canvas.drawText(
                timerText,
                (timerLeft + width.toFloat()) / 2f,
                timerCenterY + 6f,
                textPaint
            )

            textPaint.textSize = 26f
            textPaint.color =
                AXIS_TEXT_COLOR
        }

        // Time labels.
        textPaint.textAlign =
            Paint.Align.CENTER

        val labelCount =
            minOf(5, visibleCandles.size)

        for (i in 0 until labelCount) {

            val localIndex =
                if (labelCount == 1)
                    0
                else
                    i *
                    (visibleCandles.size - 1) /
                    (labelCount - 1)

            val x =
                left +
                slot * localIndex +
                slot / 2f

            val timestamp =
                visibleCandles[localIndex]
                    .timestamp * 1000L

            val label =
                if (isDaily)
                    dailyFormat.format(Date(timestamp))
                else
                    intradayFormat.format(Date(timestamp))

            canvas.drawText(
                label,
                x,
                height - 8f,
                textPaint
            )
        }

        // Crosshair.
        if (
            crosshairIndex in
            startIndex..endVisible
        ) {
            val localIndex =
                crosshairIndex - startIndex

            val candle =
                candles[crosshairIndex]

            val x =
                left +
                slot * localIndex +
                slot / 2f

            val y =
                priceY(candle.close)

            crosshairPaint.color =
                AXIS_TEXT_COLOR

            canvas.drawLine(
                x,
                top,
                x,
                bottom,
                crosshairPaint
            )

            canvas.drawLine(
                left,
                y,
                right,
                y,
                crosshairPaint
            )

            // Crosshair price tag.
            paint.style = Paint.Style.FILL
            paint.color =
                AXIS_TEXT_COLOR

            canvas.drawRect(
                right,
                y - 16f,
                width.toFloat(),
                y + 16f,
                paint
            )

            textPaint.color = Color.BLACK
            textPaint.textAlign =
                Paint.Align.LEFT

            canvas.drawText(
                String.format(
                    Locale.US,
                    "%.2f",
                    candle.close
                ),
                right + 8f,
                y + 7f,
                textPaint
            )

            // OHLC information.
            val timestamp =
                candle.timestamp * 1000L

            val time =
                if (isDaily)
                    dailyFormat.format(Date(timestamp))
                else
                    intradayFormat.format(Date(timestamp))

            val info =
                "$time  O ${price(candle.open)}  " +
                "H ${price(candle.high)}  " +
                "L ${price(candle.low)}  " +
                "C ${price(candle.close)}"

            val boxLeft = 12f
            val boxTop = top + 8f
            val boxRight =
                minOf(
                    right - 4f,
                    boxLeft + 510f
                )
            val boxBottom =
                boxTop + 40f

            infoPaint.color =
                0xEE151A21.toInt()

            canvas.drawRoundRect(
                boxLeft,
                boxTop,
                boxRight,
                boxBottom,
                8f,
                8f,
                infoPaint
            )

            infoTextPaint.color =
                Color.WHITE

            canvas.drawText(
                info,
                boxLeft + 10f,
                boxTop + 26f,
                infoTextPaint
            )
        }
        drawStrategySignals(canvas)

    }


    /**
     * Draws BUY/SELL markers for the selected strategy.
     *
     * Signals are matched to candle timestamps and only BUY/SELL signals
     * are rendered. HOLD signals are intentionally ignored.
     */
    private fun drawStrategySignals(canvas: Canvas) {
        if (strategySignals.isEmpty() || candles.isEmpty()) return

        val visibleStart = (endIndex - visibleCount + 1).coerceAtLeast(0)
        val visibleEnd = endIndex.coerceAtMost(candles.lastIndex)
        if (visibleStart > visibleEnd) return

        val left = 8f
        val right = width - 96f
        val chartWidth = (right - left).coerceAtLeast(1f)

        val candleWidth = chartWidth / visibleCount.coerceAtLeast(1)

        val visibleCandles = candles.subList(
            visibleStart,
            visibleEnd + 1
        )

        if (visibleCandles.isEmpty()) return

        var minPrice = visibleCandles.minOf { it.low }
        var maxPrice = visibleCandles.maxOf { it.high }

        ema20.drop(visibleStart).take(visibleCandles.size).forEach {
            minPrice = minOf(minPrice, it)
            maxPrice = maxOf(maxPrice, it)
        }

        ema50.drop(visibleStart).take(visibleCandles.size).forEach {
            minPrice = minOf(minPrice, it)
            maxPrice = maxOf(maxPrice, it)
        }

        latestPrice?.let {
            minPrice = minOf(minPrice, it)
            maxPrice = maxOf(maxPrice, it)
        }

        val range = (maxPrice - minPrice).coerceAtLeast(0.000001f)

        // Keep marker placement aligned with the existing chart's
        // visible price region.
        val top = 16f
        val bottom = height - 18f
        val chartHeight = (bottom - top).coerceAtLeast(1f)

        fun priceToY(price: Float): Float {
            return bottom - ((price - minPrice) / range) * chartHeight
        }

        strategySignals.forEach { signal ->
            if (signal.type != com.algotrader.strategy.SignalType.BUY &&
                signal.type != com.algotrader.strategy.SignalType.SELL
            ) {
                return@forEach
            }

            val signalSecond = signal.timestamp.epochSecond

            val candleIndex = candles.indexOfFirst {
                it.timestamp == signalSecond
            }

            if (candleIndex !in visibleStart..visibleEnd) return@forEach

            val visibleIndex = candleIndex - visibleStart
            val x = left + (visibleIndex + 0.5f) * candleWidth

            val candle = candles[candleIndex]

            val isBuy = signal.type == com.algotrader.strategy.SignalType.BUY
            val price = if (isBuy) candle.low else candle.high
            val baseY = priceToY(price)

            val markerSize = 10f

            paint.style = Paint.Style.FILL
            paint.color = if (isBuy) BULLISH_COLOR else BEARISH_COLOR

            val path = Path()

            if (isBuy) {
                path.moveTo(x, baseY + markerSize)
                path.lineTo(x - markerSize, baseY - markerSize)
                path.lineTo(x + markerSize, baseY - markerSize)
            } else {
                path.moveTo(x, baseY - markerSize)
                path.lineTo(x - markerSize, baseY + markerSize)
                path.lineTo(x + markerSize, baseY + markerSize)
            }

            path.close()
            canvas.drawPath(path, paint)

            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = 20f
            textPaint.color = if (isBuy) BULLISH_COLOR else BEARISH_COLOR

            val label = if (isBuy) "BUY" else "SELL"
            val labelY = if (isBuy) {
                baseY + markerSize + 22f
            } else {
                baseY - markerSize - 8f
            }

            canvas.drawText(
                label,
                x - textPaint.measureText(label) / 2f,
                labelY,
                textPaint
            )
        }
    }


    private fun price(value: Float): String =
        String.format(
            Locale.US,
            "%.2f",
            value
        )

    private fun drawEmaLine(
        canvas: Canvas,
        values: List<Float>,
        slot: Float,
        left: Float,
        priceY: (Float) -> Float,
        color: Int
    ) {
        if (values.size < 2) return

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = color

        val path = Path()

        values.forEachIndexed { index, value ->

            if (value <= 0f) return@forEachIndexed

            val x =
                left +
                slot * index +
                slot / 2f

            val y = priceY(value)

            if (index == 0)
                path.moveTo(x, y)
            else
                path.lineTo(x, y)
        }

        canvas.drawPath(path, paint)
    }
}

class VolumeChartView(context: android.content.Context) : View(context) {

    private var candles: List<Candle> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT_COLOR
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }

    fun setData(candles: List<Candle>) {
        this.candles = candles
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(CHART_BG)
        if (candles.isEmpty()) return

        val rightMargin = 96f
        val left = 8f
        val right = width - rightMargin
        val top = 6f
        val bottom = height - 6f

        val maxVolume = candles.maxOf { it.volume }.coerceAtLeast(1f)
        val slot = (right - left) / candles.size
        val barWidth = (slot * 0.6f).coerceAtLeast(2f)

        candles.forEachIndexed { index, candle ->
            val x = left + slot * index + slot / 2f
            val barHeight = (candle.volume / maxVolume) * (bottom - top)
            val bullish = candle.close >= candle.open

            paint.color = if (bullish) BULLISH_COLOR else BEARISH_COLOR
            paint.style = Paint.Style.FILL
            canvas.drawRect(
                x - barWidth / 2f,
                bottom - barHeight,
                x + barWidth / 2f,
                bottom,
                paint
            )
        }

        val latestVolume = candles.last().volume
        canvas.drawText(formatVolume(latestVolume), right + 8f, top + 20f, textPaint)
    }
}

class RsiChartView(context: android.content.Context) : View(context) {

    private var rsiValues: List<Float> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHART_GRID
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT_COLOR
        textSize = 22f
        textAlign = Paint.Align.LEFT
    }

    fun setData(rsiValues: List<Float>) {
        this.rsiValues = rsiValues
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(CHART_BG)
        if (rsiValues.isEmpty()) return

        val rightMargin = 96f
        val left = 8f
        val right = width - rightMargin
        val top = 8f
        val bottom = height - 8f

        fun y(value: Float) = bottom - (value / 100f) * (bottom - top)

        listOf(70f, 50f, 30f).forEach { level ->
            canvas.drawLine(left, y(level), right, y(level), levelPaint)
            canvas.drawText(level.toInt().toString(), right + 8f, y(level) + 8f, textPaint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xFFFF7043.toInt()

        val slot = (right - left) / rsiValues.size
        val path = Path()
        rsiValues.forEachIndexed { index, value ->
            val x = left + slot * index + slot / 2f
            val yy = y(value)
            if (index == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        canvas.drawPath(path, paint)

        val latest = rsiValues.last()
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFF7043.toInt()
        val latestY = y(latest)
        canvas.drawRect(right, latestY - 14f, right + rightMargin, latestY + 14f, paint)
        textPaint.color = Color.BLACK
        canvas.drawText(String.format(Locale.US, "%.2f", latest), right + 8f, latestY + 7f, textPaint)
        textPaint.color = AXIS_TEXT_COLOR
    }
}
