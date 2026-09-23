package com.algotrader.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showDashboard()
    }

    private fun baseLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            setBackgroundColor(Color.rgb(15, 18, 24))
        }
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 24)
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 12)
        }
    }

    private fun button(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 6, 0, 6)
            }
        }
    }

    private fun showDashboard() {
        val root = baseLayout()

        root.addView(title("AlgoTrader"))

        val status = label("●  SYSTEM ONLINE").apply {
            setTextColor(Color.rgb(80, 220, 120))
            setTypeface(null, Typeface.BOLD)
        }

        root.addView(status)
        root.addView(label("Algorithmic Trading Dashboard"))

        root.addView(
            button("📊  Market Data") {
                showMarketData()
            }
        )

        root.addView(
            button("🧠  Strategy") {
                showStrategy()
            }
        )

        root.addView(
            button("⚡  Execution") {
                showExecution()
            }
        )

        root.addView(
            button("📈  Backtest") {
                showBacktest()
            }
        )

        root.addView(
            label("\nAlgoTrader v${packageManager.getPackageInfo(packageName, 0).versionName}")
        )

        setContentView(root)
    }

    private fun showMarketData() {
        val root = baseLayout()

        root.addView(title("Market Data"))

        root.addView(
            label("WATCHLIST")
        )

        val symbols = listOf(
            "NIFTY 50        25,000.00",
            "BANK NIFTY      57,500.00",
            "RELIANCE        1,450.00",
            "TCS             3,850.00",
            "INFY            1,520.00"
        )

        symbols.forEach {
            root.addView(
                label(it).apply {
                    textSize = 18f
                    setPadding(8, 18, 8, 18)
                }
            )
        }

        root.addView(
            label("\n⚠ DEMO DATA\nPrices above are placeholder values.")
                .apply {
                    setTextColor(Color.YELLOW)
                }
        )

        root.addView(
            button("↻  Refresh") {
                showMarketData()
            }
        )

        root.addView(
            button("←  Dashboard") {
                showDashboard()
            }
        )

        setContentView(
            ScrollView(this).apply {
                addView(root)
            }
        )
    }

    private fun showStrategy() {
        val root = baseLayout()

        root.addView(title("Strategy"))

        root.addView(label("MOVING AVERAGE CROSSOVER"))

        root.addView(label("Status: READY"))

        root.addView(label("Fast MA: 20"))
        root.addView(label("Slow MA: 50"))

        root.addView(
            button("Configure Strategy") {
                showStrategy()
            }
        )

        root.addView(
            button("←  Dashboard") {
                showDashboard()
            }
        )

        setContentView(root)
    }

    private fun showExecution() {
        val root = baseLayout()

        root.addView(title("Execution"))

        root.addView(label("Trading Mode"))
        root.addView(label("PAPER TRADING"))

        root.addView(label("Status: STANDBY"))

        root.addView(
            button("Start Paper Trading") {
                showExecution()
            }
        )

        root.addView(
            button("←  Dashboard") {
                showDashboard()
            }
        )

        setContentView(root)
    }

    private fun showBacktest() {
        val root = baseLayout()

        root.addView(title("Backtest"))

        root.addView(label("Strategy: Moving Average Crossover"))
        root.addView(label("Capital: ₹100,000"))
        root.addView(label("Status: READY"))

        root.addView(
            button("Run Backtest") {
                showBacktest()
            }
        )

        root.addView(
            button("←  Dashboard") {
                showDashboard()
            }
        )

        setContentView(root)
    }
}
