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

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var bottomNav: LinearLayout

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

        val demo = label("● DEMO DATA — live market feed will be connected later")
        demo.setTextColor(Color.YELLOW)
        content.addView(demo)

        content.addView(section("Timeframe"))

        val timeframeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        listOf("1m", "5m", "15m", "1h", "1D").forEach {
            val button = Button(this).apply {
                text = it
                textSize = 11f
            }

            timeframeRow.addView(
                button,
                LinearLayout.LayoutParams(0, 55, 1f)
            )
        }

        content.addView(timeframeRow)

        content.addView(section("NIFTY 50"))
        addInstrumentSelector("NIFTY 50", "NIFTY FUT")
        addChart(
            "NIFTY 50",
            25000f,
            floatArrayOf(
                24780f, 24840f, 24720f, 24900f,
                24860f, 24980f, 25040f, 24920f,
                25080f, 25140f, 25000f, 25180f
            )
        )

        content.addView(section("BANK NIFTY"))
        addInstrumentSelector("BANK NIFTY", "BANK NIFTY FUT")
        addChart(
            "BANK NIFTY",
            57500f,
            floatArrayOf(
                57100f, 57350f, 57200f, 57500f,
                57400f, 57800f, 57650f, 57900f,
                57750f, 58100f, 57950f, 58200f
            )
        )

        content.addView(section("SENSEX"))
        addInstrumentSelector("SENSEX", "SENSEX FUT")
        addChart(
            "SENSEX",
            82000f,
            floatArrayOf(
                81600f, 81800f, 81750f, 82000f,
                81900f, 82250f, 82100f, 82400f,
                82300f, 82600f, 82500f, 82800f
            )
        )
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
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 12)
        }

        card.addView(
            label("$name   ${formatNumber(current)}")
        )

        card.addView(
            SimpleChartView(this, values),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                260
            )
        )

        content.addView(card)
    }

    private fun formatNumber(value: Float): String {
        return String.format("%,.0f", value)
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
            "DEMO DATA\n\n" +
            "Provider: Not connected\n" +
            "Streaming: Standby\n" +
            "Historical data: Pending integration"
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
}

class SimpleChartView(
    context: android.content.Context,
    private val values: FloatArray
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.rgb(15, 20, 27))

        if (values.isEmpty()) return

        val min = values.minOrNull() ?: return
        val max = values.maxOrNull() ?: return
        val range = (max - min).coerceAtLeast(1f)

        val left = 20f
        val right = width - 20f
        val top = 30f
        val bottom = height - 30f

        val path = Path()

        values.forEachIndexed { index, value ->
            val x = left +
                    (right - left) *
                    index.toFloat() /
                    (values.size - 1).coerceAtLeast(1)

            val y = bottom -
                    (value - min) /
                    range *
                    (bottom - top)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        paint.color = Color.rgb(80, 200, 120)
        canvas.drawPath(path, paint)

        paint.color = Color.DKGRAY
        paint.strokeWidth = 1f

        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, paint)
        }
    }
}
