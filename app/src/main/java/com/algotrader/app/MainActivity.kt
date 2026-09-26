package com.algotrader.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestEngine
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.PositionSizing
import com.algotrader.strategy.CprEmaTrendStrategy
import com.algotrader.strategy.DonchianEmaTrendStrategy
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
    val backendSymbol: String
)

object Instruments {
    val NIFTY = InstrumentInfo("NIFTY 50", "NSE:NIFTY50-INDEX")
    val BANK_NIFTY = InstrumentInfo("BANK NIFTY", "NSE:NIFTYBANK-INDEX")
    val SENSEX = InstrumentInfo("SENSEX", "BSE:SENSEX-INDEX")
    val all = listOf(NIFTY, BANK_NIFTY, SENSEX)
}

class MainActivity : Activity() {

    companion object {
        private const val BACKEND_HTTP_BASE = "https://algotrader-backend-kras.onrender.com"
        private const val BACKEND_WS_URL = "wss://algotrader-backend-kras.onrender.com/ws/quotes"
        private val TIMEFRAMES = listOf("1m", "5m", "15m", "30m", "1h", "1D")
    }

    private lateinit var content: LinearLayout
    private lateinit var bottomNav: LinearLayout

    private val liveHandler = Handler(Looper.getMainLooper())
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var quotesWebSocket: WebSocket? = null

    // ---- Home / trading workspace state ----
    private var selectedInstrument: InstrumentInfo = Instruments.NIFTY
    private var selectedTimeframe = "5m"
    private var isHomeScreenActive = false

    // Historical candles cached by backend symbol + timeframe.
    // Example key: "NSE:NIFTY50-INDEX|1h"
    private val candlesByInstrument = mutableMapOf<String, List<Candle>>()
    // Latest live LTP received over the websocket, per backend symbol.
    private val liveLtpByInstrument = mutableMapOf<String, Double>()
    // Reference price for computing the header's +/- change, captured from the
    // oldest candle in the currently loaded window (real data, not synthetic).
    private var homeBaselineOpen: Float = 0f

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

    // ---------------------------------------------------------------------
    // Networking
    // ---------------------------------------------------------------------

    private fun timeframeResolution(): String {
        return when (selectedTimeframe) {
            "1m" -> "1"
            "5m" -> "5"
            "15m" -> "15"
            "30m" -> "30"
            "1h" -> "60"
            "1D" -> "D"
            else -> "1"
        }
    }

    /** Loads history for the currently selected instrument + timeframe from the backend. */
    private fun loadHistoryForSelected() {
        val instrument = selectedInstrument
        val requestedSymbol = instrument.backendSymbol
        val requestedTimeframe = selectedTimeframe
        val cacheKey = "$requestedSymbol|$requestedTimeframe"

        // Use already-loaded historical data immediately.
        candlesByInstrument[cacheKey]?.let { cached ->
            if (cached.isNotEmpty()) {
                setHeaderStatus(null)
                renderHomeData(cached)
                return
            }
        }

        val resolution = timeframeResolution()

        setHeaderStatus("Loading ${instrument.displayName}…")

        val historyDays = 365
        val request = Request.Builder()
            .url(
                "$BACKEND_HTTP_BASE/history" +
                    "?symbol=$requestedSymbol&resolution=$resolution&days=$historyDays"
            )
            .build()

        wsClient.newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                        setHeaderStatus(
                            "Network error: ${e.javaClass.simpleName} — ${e.message ?: "unknown"}"
                        )
                    }
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
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
                        val array = root.optJSONArray("candles")

                        if (array == null || array.length() == 0) {
                            runOnUiThread {
                                if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                    setHeaderStatus("No candle data available")
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

                        // Cache by both instrument and timeframe.
                        candlesByInstrument[cacheKey] = candles

                        runOnUiThread {
                            if (isStillCurrentSelection(requestedSymbol, requestedTimeframe)) {
                                setHeaderStatus(null)
                                renderHomeData(candles)
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

    private fun isStillCurrentSelection(symbol: String, timeframe: String): Boolean {
        return isHomeScreenActive &&
            symbol == selectedInstrument.backendSymbol &&
            timeframe == selectedTimeframe
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
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val root = JSONObject(text)
                        val quotes = root.optJSONObject("quotes") ?: return

                        for (instrument in Instruments.all) {
                            val ltp = quotes.optJSONObject(instrument.backendSymbol)
                                ?.optDouble("ltp", Double.NaN) ?: Double.NaN

                            if (!ltp.isNaN()) {
                                liveLtpByInstrument[instrument.backendSymbol] = ltp
                            }
                        }

                        runOnUiThread {
                            if (isHomeScreenActive) {
                                updateHeaderPrice()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildApp()
        connectQuotesWebSocket()
        showHome()
    }

    override fun onDestroy() {
        super.onDestroy()
        quotesWebSocket?.close(1000, "Activity destroyed")
        liveHandler.removeCallbacksAndMessages(null)
    }

    private fun buildApp() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 14, 20))
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        val contentScroll = ScrollView(this).apply {
            addView(content)
        }

        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), dp(6))
            setBackgroundColor(Color.rgb(20, 25, 32))
        }

        addNavButton("Home") { showHome() }
        addNavButton("Market Data") { showMarketData() }
        addNavButton("Strategies") { showStrategies() }
        addNavButton("Execution") { showExecution() }
        addNavButton("Backtest") { showBacktest() }

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

    private fun addNavButton(text: String, action: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            textSize = 11f
            setOnClickListener { action() }
        }

        bottomNav.addView(
            button,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
    }

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
        content.removeAllViews()
    }

    private fun title(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }

    private fun section(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, dp(6))
        }

    private fun label(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(3), 0, dp(3))
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
        clearContent()
        isHomeScreenActive = true

        content.addView(buildHeader())
        content.addView(buildInstrumentSelector())
        content.addView(buildTimeframeToolbar())
        buildMainChart()
        buildVolumePanel()
        buildRsiPanel()
        buildTimeRangeRow()
        content.addView(buildTradeControls())

        // Show cached data immediately (if any) while a fresh reload is in flight,
        // then always reload from the backend for the current selection.
        candlesByInstrument["${selectedInstrument.backendSymbol}|$selectedTimeframe"]?.let { cached ->
            renderHomeData(cached)
        }
        updateHeaderPrice()
        loadHistoryForSelected()
    }

    private fun buildHeader(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val titleRow = TextView(this).apply {
            text = "${selectedInstrument.displayName} · $selectedTimeframe"
            textSize = 15f
            setTextColor(Color.rgb(160, 170, 185))
        }
        headerTitleLabel = titleRow

        val priceRow = TextView(this).apply {
            text = "—"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(2))
        }
        headerPriceLabel = priceRow

        val changeRow = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.rgb(60, 200, 120))
        }
        headerChangeLabel = changeRow

        val statusRow = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.rgb(200, 160, 60))
            setPadding(0, dp(2), 0, 0)
        }
        headerStatusLabel = statusRow

        container.addView(titleRow)
        container.addView(priceRow)
        container.addView(changeRow)
        container.addView(statusRow)
        return container
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

        Instruments.all.forEach { instrument ->
            val button = Button(this).apply {
                text = instrument.displayName
                textSize = 11f
                isAllCaps = false
                setPadding(dp(4), 0, dp(4), 0)
                applySelectorStyle(this, instrument.backendSymbol == selectedInstrument.backendSymbol)

                setOnClickListener {
                    if (selectedInstrument.backendSymbol != instrument.backendSymbol) {
                        selectedInstrument = instrument
                        showHome()
                    }
                }
            }

            instrumentButtons[instrument.backendSymbol] = button

            row.addView(
                button,
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
            val button = Button(this).apply {
                text = timeframe
                textSize = 11f
                isAllCaps = false
                applySelectorStyle(this, timeframe == selectedTimeframe)

                setOnClickListener {
                    if (selectedTimeframe != timeframe) {
                        selectedTimeframe = timeframe
                        showHome()
                    }
                }
            }

            timeframeButtons[timeframe] = button

            row.addView(
                button,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginEnd = dp(2)
                }
            )
        }

        return row
    }

    private fun applySelectorStyle(button: Button, selected: Boolean) {
        if (selected) {
            button.setBackgroundColor(Color.rgb(41, 121, 255))
            button.setTextColor(Color.WHITE)
        } else {
            button.setBackgroundColor(Color.rgb(30, 36, 46))
            button.setTextColor(Color.rgb(180, 188, 200))
        }
    }

    private fun buildMainChart(): TradingChartView {
        val chart = TradingChartView(this)
        mainChartView = chart
        content.addView(
            chart,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280))
        )
        return chart
    }

    private fun buildVolumePanel(): VolumeChartView {
        val volume = VolumeChartView(this)
        volumeChartView = volume
        content.addView(
            volume,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)).apply {
                topMargin = dp(2)
            }
        )
        return volume
    }

    private fun buildRsiPanel(): RsiChartView {
        val rsi = RsiChartView(this)
        rsiChartView = rsi
        content.addView(
            rsi,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)).apply {
                topMargin = dp(2)
            }
        )
        return rsi
    }

    private fun buildTimeRangeRow(): TextView {
        val label = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.rgb(120, 128, 140))
            setPadding(0, dp(6), 0, dp(6))
        }
        timeRangeLabel = label
        content.addView(label)
        return label
    }

    private fun buildTradeControls(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }

        val buy = Button(this).apply {
            text = "BUY"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(38, 166, 91))
            setOnClickListener { placeOrder("BUY") }
        }

        val sell = Button(this).apply {
            text = "SELL"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(239, 83, 80))
            setOnClickListener { placeOrder("SELL") }
        }

        row.addView(buy, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(6) })
        row.addView(sell, LinearLayout.LayoutParams(0, dp(56), 1f))

        return row
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
    private fun renderHomeData(candles: List<Candle>) {
        if (!isHomeScreenActive) return

        val closes = candles.map { it.close }
        val ema20 = computeEma(closes, 20)
        val ema50 = computeEma(closes, 50)
        val rsi = computeRsi(closes, 14)
        val isDaily = selectedTimeframe == "1D"

        mainChartView?.setData(candles, ema20, ema50, isDaily)
        volumeChartView?.setData(candles)
        rsiChartView?.setData(rsi)

        homeBaselineOpen = candles.first().open

        headerTitleLabel?.text = "${selectedInstrument.displayName} · $selectedTimeframe"
        updateHeaderPrice()

        val dateFormat = if (isDaily) {
            SimpleDateFormat("dd MMM yyyy", Locale.US)
        } else {
            SimpleDateFormat("dd MMM, HH:mm", Locale.US)
        }
        dateFormat.timeZone = TimeZone.getTimeZone("Asia/Kolkata")

        val start = dateFormat.format(Date(candles.first().timestamp * 1000L))
        val end = dateFormat.format(Date(candles.last().timestamp * 1000L))
        timeRangeLabel?.text = "$start  →  $end  (IST)"
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

        val baseline = if (homeBaselineOpen > 0f) homeBaselineOpen else price
        val change = price - baseline
        val pct = if (baseline != 0f) change / baseline * 100f else 0f

        headerPriceLabel?.text = formatNumber(price)
        headerChangeLabel?.text = "${formatChange(change)} (${formatPercent(pct)})"
        headerChangeLabel?.setTextColor(
            if (change >= 0) Color.rgb(60, 200, 120) else Color.rgb(239, 83, 80)
        )

        mainChartView?.setLatestPrice(price)
    }

    // ---------------------------------------------------------------------
    // Other tabs (unchanged in spirit — simple status screens)
    // ---------------------------------------------------------------------

    private fun showMarketData() {
        clearContent()

        content.addView(title("Market Data"))
        content.addView(label("Market data configuration and instruments"))

        content.addView(section("Indices"))
        content.addView(label("NIFTY 50"))
        content.addView(label("BANK NIFTY"))
        content.addView(label("SENSEX"))

        content.addView(section("Data Status"))

        val status = label(
            "LIVE MARKET DATA\n\n" +
                "Provider: FYERS\n" +
                "Streaming: Connected\n" +
                "Indices: NIFTY 50 • BANK NIFTY • SENSEX"
        )

        content.addView(status)
    }

    private fun showStrategies() {
        clearContent()

        content.addView(title("Strategies"))

        content.addView(section("Moving Average Crossover"))
        content.addView(label("Fast MA: 20"))
        content.addView(label("Slow MA: 50"))
        content.addView(label("Status: READY"))

        content.addView(section("Strategy Engine"))
        content.addView(label("Engine status: READY"))
    }

    private fun showExecution() {
        clearContent()

        content.addView(title("Execution"))

        content.addView(section("Trading Mode"))
        content.addView(label("PAPER TRADING"))
        content.addView(label("Status: STANDBY"))

        content.addView(section("Broker Connection"))
        content.addView(label("Not connected"))
    }

    
private fun runBacktest() {
    val uiCandles = candlesByInstrument["${selectedInstrument.backendSymbol}|$selectedTimeframe"]

    if (uiCandles.isNullOrEmpty()) {
        Toast.makeText(
            this,
            "Historical data is not loaded yet. Open Home first and wait for candles.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val domainCandles = uiCandles.mapNotNull { candle ->
        try {
            com.algotrader.domain.Candle(
                instrument = com.algotrader.domain.Instrument(
                    symbol = selectedInstrument.backendSymbol,
                    exchange = selectedInstrument.backendSymbol.substringBefore(":")
                ),
                timeframe = when (selectedTimeframe) {
                    "1m" -> com.algotrader.domain.Timeframe.MINUTE_1
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

    content.removeAllViews()

    content.addView(title("Backtest"))

    content.addView(section("Running"))
    content.addView(label(
        "${selectedInstrument.displayName} · $selectedTimeframe"
    ))
    content.addView(label(
        "${domainCandles.size} real FYERS candles"
    ))

    content.addView(label("Testing CPR + EMA and Donchian + EMA…"))

    Thread {
        try {
            val config = BacktestConfig(
                initialCapital = 100_000.0,
                positionSizing = PositionSizing.FixedQuantity(1.0)
            )

            val engine = BacktestEngine(config)

            val results = listOf(
                engine.run(
                    CprEmaTrendStrategy(),
                    domainCandles
                ),
                engine.run(
                    DonchianEmaTrendStrategy(),
                    domainCandles
                )
            )

            runOnUiThread {
                renderBacktestResults(results, domainCandles.size)
            }

        } catch (e: Exception) {
            runOnUiThread {
                content.removeAllViews()
                content.addView(title("Backtest"))
                content.addView(section("Error"))
                content.addView(label(
                    e.message ?: "Backtest failed"
                ))
            }
        }
    }.start()
}

private fun renderBacktestResults(
    results: List<BacktestResult>,
    candleCount: Int
) {
    content.removeAllViews()

    content.addView(title("Backtest Results"))

    content.addView(section("Test"))

    content.addView(label(
        "${selectedInstrument.displayName} · $selectedTimeframe"
    ))

    content.addView(label(
        "$candleCount real FYERS candles"
    ))

    content.addView(label("Capital: ₹100,000 · Position: 1 unit"))

    results.forEach { result ->
        val m = result.metrics

        content.addView(section(result.strategyName))

        content.addView(label(
            "Final Equity: ${formatMoney(result.finalEquity)}"
        ))

        content.addView(label(
            "Net P&L: ${formatMoney(m.netProfit)}"
        ))

        content.addView(label(
            "Return: ${formatPercent(m.totalReturnPercent)}"
        ))

        content.addView(label(
            "Trades: ${m.totalTrades}"
        ))

        content.addView(label(
            "Win Rate: ${formatPercent(m.winRate * 100.0)}"
        ))

        content.addView(label(
            "Profit Factor: ${
                m.profitFactor?.let {
                    String.format(Locale.US, "%.2f", it)
                } ?: "N/A"
            }"
        ))

        content.addView(label(
            "Max Drawdown: ${formatMoney(m.maxDrawdown)} " +
                "(${formatPercent(m.maxDrawdownPercent)})"
        ))

        content.addView(label(
            "Average Trade: ${formatMoney(m.averageTradePnl)}"
        ))

        content.addView(label(
            "Winning Trades: ${m.winningTrades} · " +
                "Losing Trades: ${m.losingTrades}"
        ))
    }

    val rerunButton = Button(this).apply {
        text = "RUN AGAIN"
        isAllCaps = false
        setOnClickListener {
            runBacktest()
        }
    }

    content.addView(rerunButton)
}

private fun showBacktest() {
    clearContent()

    content.addView(title("Backtest"))

    content.addView(section("Test Configuration"))
    content.addView(label("Instrument: ${selectedInstrument.displayName}"))
    content.addView(label("Timeframe: $selectedTimeframe"))
    content.addView(label("Initial Capital: ₹100,000"))
    content.addView(label("Position Size: 1 unit"))

    content.addView(section("Strategies"))

    content.addView(label("• CPR + EMA Trend"))
    content.addView(label("  Daily CPR + EMA(20), long/short"))

    content.addView(label("• Donchian + EMA Trend"))
    content.addView(label("  Donchian(20) + EMA(50), long/short"))

    val runButton = Button(this).apply {
        text = "RUN BACKTEST"
        isAllCaps = false
        setOnClickListener {
            runBacktest()
        }
    }

    content.addView(runButton)

    content.addView(section("Results"))

    content.addView(label(
        "Historical data: ${candlesByInstrument["${selectedInstrument.backendSymbol}|$selectedTimeframe"]?.size ?: 0} candles"
    ))

    content.addView(label(
        "Press RUN BACKTEST to test both strategies on the loaded FYERS data."
    ))
}
}

// ---------------------------------------------------------------------------
private fun formatMoney(value: Double): String {
    return String.format(
        Locale.US,
        "₹%,.2f",
        value
    )
}

private fun formatPercent(value: Double): String {
    return String.format(
        Locale.US,
        "%.2f%%",
        value
    )
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

    private var visibleCount = 80
    private var endIndex = 0
    private var crosshairIndex = -1
    private var lastTouchX = 0f

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

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    visibleCount =
                        (visibleCount / detector.scaleFactor)
                            .toInt()
                            .coerceIn(
                                20,
                                candles.size.coerceAtLeast(20)
                            )

                    endIndex =
                        endIndex.coerceIn(
                            visibleCount - 1,
                            candles.lastIndex
                        )

                    invalidate()
                    return true
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
        isDaily: Boolean
    ) {
        this.candles = candles
        this.ema20 = ema20
        this.ema50 = ema50
        this.isDaily = isDaily

        visibleCount =
            minOf(80, candles.size.coerceAtLeast(1))

        endIndex = candles.lastIndex
        crosshairIndex = -1

        invalidate()
    }

    fun setLatestPrice(price: Float) {
        latestPrice = price
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (candles.isEmpty()) return true

        val left = 8f
        val right = width - 96f
        val chartWidth = (right - left).coerceAtLeast(1f)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                crosshairIndex = indexForX(event.x, left, chartWidth)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1) {
                    return true
                }

                val dx = event.x - lastTouchX

                if (kotlin.math.abs(dx) >= 4f) {
                    val slot = chartWidth / visibleCount.toFloat()

                    if (slot > 0f) {
                        val candleShift = (-dx / slot).toInt()

                        if (candleShift != 0) {
                            endIndex = (
                                endIndex + candleShift
                            ).coerceIn(
                                visibleCount - 1,
                                candles.lastIndex
                            )

                            lastTouchX += candleShift * -slot
                        }
                    }

                    crosshairIndex = indexForX(event.x, left, chartWidth)
                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                crosshairIndex = indexForX(event.x, left, chartWidth)
                invalidate()
                return true
            }
        }

        return true
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

        val visibleCandles =
            candles.subList(
                startIndex,
                endVisible + 1
            )

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

        latestPrice?.let {
            minPrice = minOf(minPrice, it)
            maxPrice = maxOf(maxPrice, it)
        }

        val padding =
            ((maxPrice - minPrice) * 0.08f)
                .coerceAtLeast(0.5f)

        minPrice -= padding
        maxPrice += padding

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
            visibleCandles.size.toFloat()

        val bodyWidth =
            (slot * 0.6f)
                .coerceAtLeast(2f)

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

            dashedPaint.color =
                PRICE_LINE_COLOR

            canvas.drawLine(
                left,
                y,
                right,
                y,
                dashedPaint
            )

            paint.style = Paint.Style.FILL
            paint.color =
                PRICE_LINE_COLOR

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
                    "%,.2f",
                    price
                ),
                right + 8f,
                y + 9f,
                textPaint
            )

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
