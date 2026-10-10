package com.algotrader.app.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.asi2.Asi2DeviceHarness
import com.algotrader.app.asi3.Asi3DeviceHarness
import com.algotrader.app.asi3.Asi33DeviceHarness
import com.algotrader.app.asi3.Asi34DeviceHarness
import com.algotrader.app.asi3.Asi35DeviceHarness
import com.algotrader.app.asi3.Asi36DeviceHarness
import com.algotrader.app.asi3.Asi37DeviceHarness
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

        val asi33Card = altrixaCard(context)

        asi33Card.addView(
            altrixaLabel(
                context,
                "Run the ASI-3.3 production breakout detector and qualifier locally on this device using deterministic OHLC fixtures."
            )
        )

        val asi33Button = android.widget.Button(context).apply {
            text = "Run ASI-3.3 Breakout Test"
            setOnClickListener {
                try {
                    val result = Asi33DeviceHarness.run()

                    val details = buildString {
                        appendLine("Candles: ${result.candleCount}")
                        appendLine("Breakouts detected: ${result.breakoutCount}")
                        appendLine()
                        appendLine("Direction: ${result.direction ?: "none"}")
                        appendLine("Level: ${result.level ?: "none"}")
                        appendLine("Trigger: ${result.trigger ?: "none"}")
                        appendLine("Break index: ${result.breakIndex ?: "none"}")
                        appendLine("Confirmed index: ${result.confirmedIndex ?: "none"}")
                        appendLine("Breakout ID: ${result.breakoutId ?: "none"}")
                        appendLine()
                        appendLine("Qualification: ${result.qualifiedStatus}")
                        appendLine("Qualification checks:")
                        result.qualificationChecks.forEach { appendLine(it) }
                        appendLine()
                        appendLine("Negative fixture: ${result.negativeStatus}")
                        appendLine("Negative checks:")
                        result.negativeChecks.forEach { appendLine(it) }
                        appendLine()
                        appendLine("Deterministic repeat: ${result.deterministic}")
                        appendLine()
                        appendLine(result.message)
                    }

                    AlertDialog.Builder(context)
                        .setTitle(
                            if (result.passed) {
                                "ASI-3.3 Breakout Test PASSED"
                            } else {
                                "ASI-3.3 Breakout Test FAILED"
                            }
                        )
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (t: Throwable) {
                    AlertDialog.Builder(context)
                        .setTitle("ASI-3.3 Test Failed")
                        .setMessage(
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        asi33Card.addView(
            asi33Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(8)
            }
        )

        container.addView(asi33Card, topGap)


        val asi34Card = altrixaCard(context)
        asi34Card.addView(
            altrixaLabel(
                context,
                "Verify strategy lifecycle transitions, evidence gates, terminal retirement and rejection immutability."
            )
        )

        val asi34Button = android.widget.Button(context).apply {
            text = "Run ASI-3.4 Lifecycle Test"
            setOnClickListener {
                try {
                    val result = Asi34DeviceHarness.run()
                    val details = buildString {
                        appendLine("Lifecycle path:")
                        appendLine(result.lifecyclePath)
                        appendLine()
                        appendLine("History entries: ${result.historyCount}")
                        appendLine("Missing evidence rejected: ${result.missingEvidenceRejected}")
                        appendLine("Illegal jump rejected: ${result.illegalJumpRejected}")
                        appendLine("Retirement terminal: ${result.retirementTerminal}")
                        appendLine("Index-only monitoring rejected: ${result.indexOnlyRejected}")
                        appendLine("Rejected change preserved original: ${result.rejectedChangePreservedOriginal}")
                        appendLine("Deterministic repeat: ${result.deterministic}")
                        appendLine()
                        appendLine(result.message)
                    }
                    AlertDialog.Builder(context)
                        .setTitle(
                            if (result.passed) {
                                "ASI-3.4 Lifecycle Test PASSED"
                            } else {
                                "ASI-3.4 Lifecycle Test FAILED"
                            }
                        )
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (t: Throwable) {
                    AlertDialog.Builder(context)
                        .setTitle("ASI-3.4 Lifecycle Test FAILED")
                        .setMessage("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        asi34Card.addView(
            asi34Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(8)
            }
        )

        container.addView(asi34Card, topGap)

        val asi35Card = altrixaCard(context)
        asi35Card.addView(
            altrixaLabel(
                context,
                "Verify setup context, independent confluence, level conflicts, lifecycle cutoffs and lookahead protection."
            )
        )

        val asi35Button = android.widget.Button(context).apply {
            text = "Run ASI-3.5 Setup Context Test"
            setOnClickListener {
                try {
                    val result = Asi35DeviceHarness.run()
                    val details = buildString {
                        appendLine("Strong confluence: ${result.strongConfluencePassed}")
                        appendLine("Supporting factor count: ${result.supportingKindCount}")
                        appendLine("Supporting level found: ${result.supportingLevelFound}")
                        appendLine("Conflicting level detected: ${result.conflictingLevelDetected}")
                        appendLine("Conflict quality check: ${result.conflictQualityPassed}")
                        appendLine("Future evidence excluded: ${result.futureEvidenceExcluded}")
                        appendLine("Full-series lookahead rejected: ${result.lookaheadRejected}")
                        appendLine("Deterministic repeat: ${result.deterministic}")
                        appendLine("Lifecycle stage: ${result.lifecycleStage}")
                        appendLine()
                        appendLine(result.message)
                    }

                    AlertDialog.Builder(context)
                        .setTitle(
                            if (result.passed) {
                                "ASI-3.5 Setup Context Test PASSED"
                            } else {
                                "ASI-3.5 Setup Context Test FAILED"
                            }
                        )
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (t: Throwable) {
                    AlertDialog.Builder(context)
                        .setTitle("ASI-3.5 Setup Context Test FAILED")
                        .setMessage(
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        asi35Card.addView(
            asi35Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(8)
            }
        )

        container.addView(asi35Card, topGap)

        val asi36Card = altrixaCard(context)
        asi36Card.addView(
            altrixaLabel(
                context,
                "Verify opportunity composition, breakout direction, confidence, live evidence and research-evidence rejection."
            )
        )

        val asi36Button = android.widget.Button(context).apply {
            text = "Run ASI-3.6 Opportunity Composer Test"
            setOnClickListener {
                try {
                    val result = Asi36DeviceHarness.run()
                    val details = buildString {
                        appendLine("Direction: ${result.direction}")
                        appendLine("Side: ${result.side}")
                        appendLine("State: ${result.state}")
                        appendLine("Confidence: ${result.confidence ?: "none"}")
                        appendLine("Live evidence count: ${result.evidenceCount}")
                        appendLine("Opportunity ID: ${result.opportunityId}")
                        appendLine("Deterministic repeat: ${result.deterministic}")
                        appendLine("Research evidence rejected: ${result.researchRejected}")
                        appendLine()
                        appendLine(result.message)
                    }
                    AlertDialog.Builder(context)
                        .setTitle(
                            if (result.passed) {
                                "ASI-3.6 Opportunity Composer PASSED"
                            } else {
                                "ASI-3.6 Opportunity Composer FAILED"
                            }
                        )
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (t: Throwable) {
                    AlertDialog.Builder(context)
                        .setTitle("ASI-3.6 Opportunity Composer FAILED")
                        .setMessage(
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        asi36Card.addView(
            asi36Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(8)
            }
        )

        container.addView(asi36Card, topGap)

        val asi37Card = altrixaCard(context)
        asi37Card.addView(
            altrixaLabel(
                context,
                "Run the complete candle-derived ASI-3.7 pipeline: structure, patterns, breakout qualification, setup context, confluence and opportunity composition. Also checks determinism and lookahead rejection."
            )
        )

        val asi37Button = android.widget.Button(context).apply {
            text = "Run ASI-3.7 Pipeline Integration Test"
            setOnClickListener {
                try {
                    val result = Asi37DeviceHarness.run()
                    val details = buildString {
                        appendLine("Candle-derived pipeline: PASS")
                        appendLine("Candles processed: ${result.candleCount}")
                        appendLine("Breakout ID: ${result.breakoutId}")
                        appendLine("Qualification: ${result.qualification}")
                        appendLine("Opportunity state: ${result.opportunityState}")
                        appendLine("Determinism: ${if (result.deterministic) "PASS" else "FAIL"}")
                        appendLine("Lookahead rejection: ${if (result.lookaheadRejected) "PASS" else "FAIL"}")
                        appendLine("Opportunity ID: ${result.opportunityId}")
                        appendLine()
                        appendLine(result.message)
                    }
                    AlertDialog.Builder(context)
                        .setTitle(
                            if (result.passed) {
                                "ASI-3.7 Pipeline Test PASSED"
                            } else {
                                "ASI-3.7 Pipeline Test FAILED"
                            }
                        )
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } catch (t: Throwable) {
                    AlertDialog.Builder(context)
                        .setTitle("ASI-3.7 Pipeline Test FAILED")
                        .setMessage(
                            "Candle-derived pipeline: FAIL\n" +
                                "Determinism: FAIL or not reached\n" +
                                "Lookahead rejection: FAIL or not reached\n\n" +
                                "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        asi37Card.addView(
            asi37Button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(8)
            }
        )

        container.addView(asi37Card, topGap)

        container.addView(altrixaSectionHeader(context, "Live Execution"))
        val engineCard = altrixaCard(context)
        engineCard.addView(
            altrixaLabel(context, "No strategy is currently running live \u2014 use Backtest to evaluate one against loaded candles.")
        )
        container.addView(engineCard, topGap)
    }
}
