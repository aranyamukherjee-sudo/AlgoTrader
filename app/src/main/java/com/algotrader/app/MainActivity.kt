package com.algotrader.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.rgb(15, 18, 24))
        }

        fun text(
            value: String,
            size: Float,
            bold: Boolean = false
        ): TextView {
            return TextView(this).apply {
                text = value
                textSize = size
                setTextColor(Color.WHITE)
                if (bold) {
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                setPadding(0, 16, 0, 16)
            }
        }

        val title = text("AlgoTrader", 32f, true)

        val status = text(
            "●  SYSTEM ONLINE",
            18f,
            true
        ).apply {
            setTextColor(Color.rgb(80, 220, 120))
        }

        root.addView(title)
        root.addView(status)

        root.addView(
            text(
                "Algorithmic Trading Dashboard",
                18f
            )
        )

        root.addView(
            text(
                "━━━━━━━━━━━━━━━━━━━━\n\n" +
                "MARKET DATA\n" +
                "Ready\n\n" +
                "STRATEGY ENGINE\n" +
                "Ready\n\n" +
                "EXECUTION\n" +
                "Standby\n\n" +
                "BACKTEST\n" +
                "Ready",
                17f
            )
        )

        root.addView(
            text(
                "\nAlgoTrader v0.1.0",
                14f
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        setContentView(root)
    }
}
