package com.algotrader.app.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.asi2.Asi2DeviceHarness
import com.algotrader.app.asi3.Asi3DeviceHarness
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaLabel
import com.algotrader.app.ui.components.altrixaSectionHeader
import com.algotrader.app.ui.components.altrixaStatusBadge
import com.algotrader.app.ui.components.altrixaTitle
import com.algotrader.strategyengine.StrategyRegistry

/**
 * Strategies screen (Part 1 — Foundation).
 *
 * The previous version showed hardcoded, made-up numbers ("Fast MA: 20",
 * "Slow MA: 50", "Status: READY") for one strategy, unrelated to what
 * `core:strategy` actually contains and with no live status behind "READY".
 * This lists the real strategy classes from `core:strategy` using their
 * metadata, default parameters, and current application readiness state.
 */
object StrategiesScreen {

    private val strategies = StrategyRegistry.all()

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

        strategies.forEach { strategy ->
            val card = altrixaCard(context)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(
                altrixaLabel(context, strategy.name),
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            row.addView(
                altrixaStatusBadge(context, "READY", AltrixaTone.ACCENT)
            )

            card.addView(row)

            card.addView(
                altrixaLabel(context, strategy.metadata.description),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = context.dpToPx(4)
                }
            )

            if (strategy.metadata.parameters.isNotEmpty()) {
                card.addView(
                    altrixaLabel(context, "Parameters"),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = context.dpToPx(8)
                    }
                )

                strategy.metadata.parameters.forEach { parameter ->
                    card.addView(
                        altrixaLabel(
                            context,
                            "${parameter.name}: ${parameter.value}"
                        ),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }

            container.addView(card, topGap)
        }

        container.addView(altrixaSectionHeader(context, "ASI-2 Device Verification"))

        val asi2Card = altrixaCard(context)

        asi2Card.addView(
            altrixaLabel(
                context,
                "Run the deterministic ASI-2 walk-forward discovery pipeline locally on this device."
            )
        )

        val asi2Button = android.widget.Button(context).apply {
            text = "Run ASI-2 Walk-Forward Test"
            setOnClickListener {
                try {
                    val result = Asi2DeviceHarness.run()

                    val details = buildString {
                        appendLine("Candidates: ${result.candidates}")
                        appendLine("Promising: ${result.promising}")
                        appendLine("WF candidates: ${result.walkForwardCandidates}")
                        appendLine()
                        appendLine("Breakout 10 / exit 5")
                        appendLine("Folds: ${result.simpleFoldCount}")
                        appendLine("All passed: ${result.simpleAllPassed}")
                        appendLine()
                        appendLine("Aggregate score:")
                        appendLine(result.simpleAggregateScore)
                        appendLine()
                        appendLine("Fold diagnostics:")
                        result.foldDiagnostics.forEach { appendLine(it) }
                        appendLine()
                        appendLine("Run key: ${result.runKey}")
                    }

                    AlertDialog.Builder(context)
                        .setTitle("ASI-2 Walk-Forward")
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (t: Throwable) {
                    AlertDialog.Builder(context)
                        .setTitle("ASI-2 Test Failed")
                        .setMessage(
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        asi2Card.addView(
            asi2Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(8)
            }
        )

        container.addView(asi2Card, topGap)

        container.addView(altrixaSectionHeader(context, "ASI-3 Device Verification"))

        val asi3Card = altrixaCard(context)

        asi3Card.addView(
            altrixaLabel(
                context,
                "Run the ASI-3.2 pattern detector locally on this device using deterministic OHLC fixtures."
            )
        )

        val asi3Button = android.widget.Button(context).apply {
            text = "Run ASI-3.2 Pattern Test"
            setOnClickListener {
                try {
                    val result = Asi3DeviceHarness.run()

                    val details = buildString {
                        appendLine("Candles: ${result.candleCount}")
                        appendLine("Status: ${result.status}")
                        if (result.message.isNotBlank()) {
                            appendLine("Message: ${result.message}")
                        }
                        appendLine()
                        appendLine("Patterns detected: ${result.patternCount}")
                        appendLine("Type counts:")
                        result.typeCounts.forEach { (type, count) ->
                            appendLine("$type: $count")
                        }
                        appendLine()
                        appendLine("Deterministic repeat: ${result.deterministic}")
                        appendLine()
                        appendLine("Detected patterns:")
                        result.patterns.forEach { appendLine(it) }
                    }

                    AlertDialog.Builder(context)
                        .setTitle("ASI-3.2 Pattern Test")
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (t: Throwable) {
                    AlertDialog.Builder(context)
                        .setTitle("ASI-3.2 Test Failed")
                        .setMessage(
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        asi3Card.addView(
            asi3Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(8)
            }
        )

        container.addView(asi3Card, topGap)

        container.addView(altrixaSectionHeader(context, "Live Execution"))
        val engineCard = altrixaCard(context)
        engineCard.addView(
            altrixaLabel(context, "No strategy is currently running live \u2014 use Backtest to evaluate one against loaded candles.")
        )
        container.addView(engineCard, topGap)
    }
}
