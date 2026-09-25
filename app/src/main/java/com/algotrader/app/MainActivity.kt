package com.algotrader.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var bottomNav: LinearLayout

    private lateinit var niftyPriceLabel: TextView
    private lateinit var bankNiftyPriceLabel: TextView
    private lateinit var sensexPriceLabel: TextView

    private val liveHandler = Handler(Looper.getMainLooper())
    private val wsClient = OkHttpClient()
    private var quotesWebSocket: WebSocket? = null
    private var selectedTimeframe = "1m"
    private val chartViews = mutableMapOf<String, SimpleChartView>()

    private fun loadHistory(symbol: String, chartName: String) {
        val resolution = timeframeResolution()

        val request = Request.Builder()
            .url(
                "https://algotrader-backend-kras.onrender.com/history" +
                    "?symbol=$symbol&resolution=$resolution&days=5"
            )
            .build()

        wsClient.newCall(request).enqueue(object : okhttp3.Callback {

            override fun onFailure(
                call: okhttp3.Call,
                e: java.io.IOException
            ) {
                runOnUiThread {
                    when (chartName) {
                        "NIFTY 50" ->
                            niftyPriceLabel.text = "NIFTY 50  NETWORK ERROR"

                        "BANK NIFTY" ->
                            bankNiftyPriceLabel.text = "BANK NIFTY  NETWORK ERROR"

                        "SENSEX" ->
                            sensexPriceLabel.text = "SENSEX  NETWORK ERROR"
                    }
                }
            }

            override fun onResponse(
                call: okhttp3.Call,
                response: Response
            ) {
                response.use {
                    val body = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        runOnUiThread {
                            when (chartName) {
                                "NIFTY 50" ->
                                    niftyPriceLabel.text =
                                        "NIFTY 50  HTTP ${response.code}"

                                "BANK NIFTY" ->
                                    bankNiftyPriceLabel.text =
                                        "BANK NIFTY  HTTP ${response.code}"

                                "SENSEX" ->
                                    sensexPriceLabel.text =
                                        "SENSEX  HTTP ${response.code}"
                            }
                        }
                        return
                    }

                    try {
                        val root = JSONObject(body)
                        val array = root.optJSONArray("candles")

                        if (array == null || array.length() == 0) {
                            runOnUiThread {
                                when (chartName) {
                                    "NIFTY 50" ->
                                        niftyPriceLabel.text = "NIFTY 50  NO CANDLES"

                                    "BANK NIFTY" ->
                                        bankNiftyPriceLabel.text = "BANK NIFTY  NO CANDLES"

                                    "SENSEX" ->
                                        sensexPriceLabel.text = "SENSEX  NO CANDLES"
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
                                        open = candle.getDouble(1).toFloat(),
                                        high = candle.getDouble(2).toFloat(),
                                        low = candle.getDouble(3).toFloat(),
                                        close = candle.getDouble(4).toFloat()
                                    )
                                )
                            }
                        }

                        if (candles.isEmpty()) return

                        runOnUiThread {
                            chartViews[chartName]?.setCandles(candles)

                            val latestClose = candles.last().close

                            when (chartName) {
                                "NIFTY 50" ->
                                    niftyPriceLabel.text =
                                        "NIFTY 50  ${formatNumber(latestClose)}"

                                "BANK NIFTY" ->
                                    bankNiftyPriceLabel.text =
                                        "BANK NIFTY  ${formatNumber(latestClose)}"

                                "SENSEX" ->
                                    sensexPriceLabel.text =
                                        "SENSEX  ${formatNumber(latestClose)}"
                            }
                        }

                    } catch (e: Exception) {
                        runOnUiThread {
                            when (chartName) {
                                "NIFTY 50" ->
                                    niftyPriceLabel.text = "NIFTY 50  PARSE ERROR"

                                "BANK NIFTY" ->
                                    bankNiftyPriceLabel.text = "BANK NIFTY  PARSE ERROR"

                                "SENSEX" ->
                                    sensexPriceLabel.text = "SENSEX  PARSE ERROR"
                            }
                        }
                    }
                }
            }
        })
    }

    private fun timeframeResolution(): String {
        return when (selectedTimeframe) {
            "1m" -> "1"
            "3m" -> "3"
            "5m" -> "5"
            "15m" -> "15"
            "30m" -> "30"
            "1h" -> "60"
            "1D" -> "D"
            else -> "1"
        }
    }

    private fun connectQuotesWebSocket() {
        val request = Request.Builder()
            .url("wss://algotrader-backend-kras.onrender.com/ws/quotes")
            .removeHeader("Origin")
            .build()

        quotesWebSocket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runOnUiThread {
                        // Connection established.
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val root = JSONObject(text)
                        val quotes = root.optJSONObject("quotes") ?: return

                        val nifty = quotes.optJSONObject("NSE:NIFTY50-INDEX")
                            ?.optDouble("ltp", Double.NaN) ?: Double.NaN
                        val bankNifty = quotes.optJSONObject("NSE:NIFTYBANK-INDEX")
                            ?.optDouble("ltp", Double.NaN) ?: Double.NaN
                        val sensex = quotes.optJSONObject("BSE:SENSEX-INDEX")
                            ?.optDouble("ltp", Double.NaN) ?: Double.NaN

                        runOnUiThread {
                            if (!nifty.isNaN()) {
                                niftyPriceLabel.text = "NIFTY 50  %.2f".format(nifty)
                            }
                            if (!bankNifty.isNaN()) {
                                bankNiftyPriceLabel.text = "BANK NIFTY  %.2f".format(bankNifty)
                            }
                            if (!sensex.isNaN()) {
                                sensexPriceLabel.text = "SENSEX  %.2f".format(sensex)
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
                        // Reconnect automatically after a short delay.
                        liveHandler.postDelayed(
                            { connectQuotesWebSocket() },
                            2000
                        )
                    }
                }
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildApp()
        showHome()
    }

    private fun buildApp() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 14, 20))
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 12)
        }

        val contentScroll = ScrollView(this).apply {
            addView(content)
        }

        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
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
        content.removeAllViews()
    }

    private fun title(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 25f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 18)
        }

    private fun section(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 8)
        }

    private fun label(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 5, 0, 5)
        }

    private fun showHome() {
        clearContent()


        content.addView(title("AlgoTrader"))
        content.addView(label("Market Dashboard"))

        val demo = label("● LIVE DATA — FYERS")
        demo.setTextColor(Color.YELLOW)
        content.addView(demo)

        content.addView(section("Timeframe: $selectedTimeframe"))

        val timeframeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOf("1m", "5m", "15m", "1h", "1D").forEach { timeframe ->
            val button = Button(this).apply {
                text = if (timeframe == selectedTimeframe) {
                    "✓ $timeframe"
                } else {
                    timeframe
                }

                textSize = 11f

                setOnClickListener {
                    if (selectedTimeframe != timeframe) {
                        selectedTimeframe = timeframe
                        showHome()
                    }
                }
            }

            timeframeRow.addView(
                button,
                LinearLayout.LayoutParams(0, 55, 1f)
            )
        }

        content.addView(timeframeRow)

        content.addView(section("NIFTY 50"))
        addInstrumentSelector("NIFTY 50", "NIFTY FUT")
        niftyPriceLabel = addChart(
            "NIFTY 50",
            0f,
            floatArrayOf()
        )

        content.addView(section("BANK NIFTY"))
        addInstrumentSelector("BANK NIFTY", "BANK NIFTY FUT")
        bankNiftyPriceLabel = addChart(
            "BANK NIFTY",
            0f,
            floatArrayOf()
        )

        content.addView(section("SENSEX"))
        addInstrumentSelector("SENSEX", "SENSEX FUT")
        sensexPriceLabel = addChart(
            "SENSEX",
            0f,
            floatArrayOf()
        )

        loadAllCharts()
    }

    private fun addInstrumentSelector(indexName: String, futuresName: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val indexButton = Button(this).apply {
            text = indexName
            textSize = 11f
        }

        val futuresButton = Button(this).apply {
            text = futuresName
            textSize = 11f
        }

        row.addView(
            indexButton,
            LinearLayout.LayoutParams(0, 55, 1f)
        )

        row.addView(
            futuresButton,
            LinearLayout.LayoutParams(0, 55, 1f)
        )

        content.addView(row)
    }

    private fun addChart(
        name: String,
        current: Float,
        values: FloatArray
    ): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 12)
        }

        val priceLabel = label("$name   ${formatNumber(current)}")

        card.addView(priceLabel)

        val chartView = SimpleChartView(
            this,
            values.mapIndexed { index, close ->
                val previous = if (index == 0) close else values[index - 1]
                val open = previous
                val high = maxOf(open, close) + 30f
                val low = minOf(open, close) - 30f

                Candle(
                    open = open,
                    high = high,
                    low = low,
                    close = close
                )
            }
        )

        chartViews[name] = chartView

        card.addView(
            chartView,

            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                260
            )
        )

        content.addView(card)

        return priceLabel
    }

    private fun formatNumber(value: Float): String {
        return String.format("%,.2f", value)
    }

    private fun loadAllCharts() {
        loadHistory("NSE:NIFTY50-INDEX", "NIFTY 50")
        loadHistory("NSE:NIFTYBANK-INDEX", "BANK NIFTY")
        loadHistory("BSE:SENSEX-INDEX", "SENSEX")
    }



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

    private fun showBacktest() {
        clearContent()

        content.addView(title("Backtest"))

        content.addView(section("Moving Average Crossover"))
        content.addView(label("Capital: ₹100,000"))
        content.addView(label("Status: READY"))

        content.addView(section("Backtest Engine"))
        content.addView(label("Historical data: Pending integration"))
    }
    override fun onDestroy() {
        super.onDestroy()
    }

}

data class Candle(
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float
)

class SimpleChartView(
    context: android.content.Context,
    private var candles: List<Candle>
) : View(context) {

    fun setCandles(newCandles: List<Candle>) {
        candles = newCandles
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.rgb(15, 20, 27))

        if (candles.isEmpty()) return

        val min = candles.minOf { it.low }
        val max = candles.maxOf { it.high }
        val range = (max - min).coerceAtLeast(1f)

        val left = 20f
        val right = width - 20f
        val top = 20f
        val bottom = height - 20f

        // Grid
        paint.color = Color.rgb(55, 62, 72)
        paint.strokeWidth = 1f

        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, paint)
        }

        val slot = (right - left) / candles.size
        val bodyWidth = (slot * 0.55f).coerceAtLeast(3f)

        candles.forEachIndexed { index, candle ->

            val x = left + slot * index + slot / 2f

            fun priceY(price: Float): Float {
                return bottom -
                        (price - min) / range *
                        (bottom - top)
            }

            val highY = priceY(candle.high)
            val lowY = priceY(candle.low)
            val openY = priceY(candle.open)
            val closeY = priceY(candle.close)

            val bullish = candle.close >= candle.open

            paint.color = if (bullish) {
                Color.rgb(60, 200, 120)
            } else {
                Color.rgb(230, 80, 90)
            }

            paint.strokeWidth = 2f
            canvas.drawLine(x, highY, x, lowY, paint)

            val bodyTop = minOf(openY, closeY)
            val bodyBottom = maxOf(openY, closeY)

            paint.style = Paint.Style.FILL

            canvas.drawRect(
                x - bodyWidth / 2f,
                bodyTop,
                x + bodyWidth / 2f,
                maxOf(bodyBottom, bodyTop + 2f),
                paint
            )

            paint.style = Paint.Style.FILL
        }
    }
}
