package com.algotrader.app.ui.screens

import android.content.Context
import android.widget.LinearLayout
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaLabel
import com.algotrader.app.ui.components.altrixaSectionHeader
import com.algotrader.app.ui.components.altrixaStatusBadge
import com.algotrader.app.ui.components.altrixaTitle

/**
 * Strategies screen (Part 1 — Foundation).
 *
 * The previous version showed hardcoded, made-up numbers ("Fast MA: 20",
 * "Slow MA: 50", "Status: READY") for one strategy, unrelated to what
 * `core:strategy` actually contains and with no live status behind "READY".
 * This lists the real strategy classes that ship in `core:strategy` by name
 * only, each marked "Idle" — no fabricated parameters or performance, since
 * nothing here is currently executing outside the Backtest screen.
 */
object StrategiesScreen {

    private val strategyNames = listOf(
        "Moving Average Crossover",
        "RSI",
        "MACD",
        "Bollinger Bands",
        "Donchian Channel",
        "Donchian + EMA Trend",
        "CPR + EMA Trend"
    )

    fun render(context: Context, container: LinearLayout) {
        val topGap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dpToPx(4) }

        container.addView(altrixaTitle(context, "Strategies"))
        container.addView(
            altrixaLabel(context, "Strategy classes available in core:strategy \u2014 run any of them from Backtest")
        )

        container.addView(altrixaSectionHeader(context, "Available"))
        strategyNames.forEach { name ->
            val card = altrixaCard(context)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                altrixaLabel(context, name),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(altrixaStatusBadge(context, "IDLE", AltrixaTone.NEUTRAL))
            card.addView(row)
            container.addView(card, topGap)
        }

        container.addView(altrixaSectionHeader(context, "Live Execution"))
        val engineCard = altrixaCard(context)
        engineCard.addView(
            altrixaLabel(context, "No strategy is currently running live \u2014 use Backtest to evaluate one against loaded candles.")
        )
        container.addView(engineCard, topGap)
    }
}
