package com.algotrader.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            TextView(this).apply {
                text = "AlgoTrader\n\nBuild system online."
                textSize = 24f
                setPadding(48, 48, 48, 48)
            }
        )
    }
}
