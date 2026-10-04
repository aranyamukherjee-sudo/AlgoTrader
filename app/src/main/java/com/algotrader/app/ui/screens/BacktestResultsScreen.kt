package com.algotrader.app.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.algotrader.app.backtest.BacktestFormat
import com.algotrader.app.backtest.BacktestJobStore
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.ui.chart.EquityCurveView
import com.algotrader.app.ui.components.AltrixaIconKind
import com.algotrader.app.ui.components.AltrixaProgressBar
import com.algotrader.app.ui.components.AltrixaSplitBar
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaCaption
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaChip
import com.algotrader.app.ui.components.altrixaDivider
import com.algotrader.app.ui.components.altrixaLabel
import com.algotrader.app.ui.components.altrixaMetricCard
import com.algotrader.app.ui.components.altrixaRounded
import com.algotrader.app.ui.components.altrixaSecondaryButton
import com.algotrader.app.ui.components.altrixaSectionHeader
import com.algotrader.app.ui.components.altrixaStatusBadge
import com.algotrader.app.ui.components.altrixaStatusBlock
import com.algotrader.app.ui.components.altrixaToneColor
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.PositionSizing
import com.algotrader.backtest.TradeDirection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Premium backtest results. Every number shown is read from a real
 * [BacktestResult] / [BacktestJobStore.Job]; nothing is estimated or invented.
 */
internal object BacktestResultsScreen {

    private const val TRADES_PAGE = 20
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    fun render(
        context: Context,
        container: LinearLayout,
        instrumentName: String,
        timeframe: String,
        candleCount: Int,
        initialCapital: Double,
        positionSizing: PositionSizing,
        results: List<BacktestResult>,
        job: BacktestJobStore.Job?,
        onRunAgain: () -> Unit
    ) {
        BacktestScreen.header(context, container, "Performance report")

        val intraday = timeframe != "1D"

        if (results.isEmpty()) {
            container.addView(altrixaSectionHeader(context, "Results"))
            val card = altrixaCard(context)
            card.addView(
                altrixaStatusBlock(
                    context,
                    AltrixaIconKind.CHART,
                    AltrixaColors.accentBright,
                    "No results available",
                    "This backtest did not produce any results."
                ),
                BacktestScreen.matchWidth(context)
            )
            container.addView(card, BacktestScreen.topGap(context))
        } else if (results.size == 1) {
            val result = results.first()
            resultBody(context, container, result, instrumentName, timeframe)
            configurationCard(
                context, container, results, instrumentName, timeframe,
                candleCount, initialCapital, positionSizing, job
            )
            tradesSection(context, container, result.trades, intraday, result.config.lotSize)
        } else {
            comparisonCard(context, container, results)
            results.forEach { result ->
                resultBody(context, container, result, instrumentName, timeframe)
                tradesSection(context, container, result.trades, intraday, result.config.lotSize)
            }
            configurationCard(
                context, container, results, instrumentName, timeframe,
                candleCount, initialCapital, positionSizing, job
            )
        }

        container.addView(
            altrixaSecondaryButton(context, "NEW BACKTEST") { onRunAgain() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceLg) }
        )
    }

    // -----------------------------------------------------------------
    // Strategy comparison (when a job holds more than one strategy result)
    // -----------------------------------------------------------------

    private fun comparisonCard(context: Context, container: LinearLayout, results: List<BacktestResult>) {
        container.addView(altrixaSectionHeader(context, "Strategy comparison"))
        val card = altrixaCard(context)

        val ranked = results.sortedByDescending { it.metrics.netProfit }
        val maxAbs = max(1.0, ranked.maxOf { abs(it.metrics.netProfit) })

        ranked.forEachIndexed { index, result ->
            val m = result.metrics
            val tone = pnlTone(m.netProfit, m.totalTrades)

            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(
                TextView(context).apply {
                    text = "${index + 1}"
                    textSize = AltrixaDimens.textSmall
                    setTextColor(AltrixaColors.textFaint)
                    setTypeface(typeface, Typeface.BOLD)
                    minWidth = context.dpToPx(18)
                }
            )
            top.addView(
                TextView(context).apply {
                    text = result.strategyName
                    textSize = AltrixaDimens.textSubtitle
                    setTextColor(AltrixaColors.textPrimary)
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (index == 0 && m.totalTrades > 0 && m.netProfit > 0.0) {
                top.addView(
                    altrixaStatusBadge(context, "TOP", AltrixaTone.POSITIVE),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = context.dpToPx(AltrixaDimens.spaceSm) }
                )
            }
            top.addView(
                TextView(context).apply {
                    text = BacktestFormat.signedMoney(m.netProfit)
                    textSize = AltrixaDimens.textSubtitle
                    setTextColor(altrixaToneColor(tone))
                    setTypeface(typeface, Typeface.BOLD)
                }
            )

            val bar = AltrixaProgressBar(context).apply {
                setSolidColor(altrixaToneColor(if (m.netProfit >= 0.0) AltrixaTone.POSITIVE else AltrixaTone.NEGATIVE))
                setProgress(max(2, (abs(m.netProfit) / maxAbs * 100.0).toInt()))
            }

            val detail = TextView(context).apply {
                text = "Return ${BacktestFormat.signedPercent(m.totalReturnPercent)}  \u00b7  " +
                    "Win ${BacktestFormat.percent(m.winRate * 100.0)}  \u00b7  " +
                    "${m.totalTrades} trades  \u00b7  DD ${BacktestFormat.percent(m.maxDrawdownPercent)}"
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.textMuted)
            }

            if (index > 0) card.addView(altrixaDivider(context))
            card.addView(top, BacktestScreen.matchWidth(context))
            card.addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dpToPx(5)
                ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceSm) }
            )
            card.addView(detail, BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceSm))
        }

        container.addView(card, BacktestScreen.topGap(context))
    }

    // -----------------------------------------------------------------
    // One strategy: hero, KPIs, equity curve, statistics
    // -----------------------------------------------------------------

    private fun pnlTone(net: Double, trades: Int): AltrixaTone = when {
        trades == 0 -> AltrixaTone.NEUTRAL
        net > 0.0 -> AltrixaTone.POSITIVE
        net < 0.0 -> AltrixaTone.NEGATIVE
        else -> AltrixaTone.NEUTRAL
    }

    private fun resultBody(
        context: Context,
        container: LinearLayout,
        result: BacktestResult,
        instrumentName: String,
        timeframe: String
    ) {
        val m = result.metrics
        val tone = pnlTone(m.netProfit, m.totalTrades)
        val toneColor = altrixaToneColor(tone)
        val initial = result.config.initialCapital

        // ---- Hero ----
        val hero = altrixaCard(context)
        hero.background = altrixaRounded(
            context,
            AltrixaColors.surfaceElevated,
            AltrixaDimens.radiusLg,
            if (tone == AltrixaTone.NEUTRAL) AltrixaColors.borderStrong else toneColor
        )

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = result.strategyName
                textSize = AltrixaDimens.textLarge
                setTextColor(AltrixaColors.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val verdict = when {
            m.totalTrades == 0 -> "NO TRADES"
            m.netProfit > 0.0 -> "PROFITABLE"
            m.netProfit < 0.0 -> "LOSS"
            else -> "BREAKEVEN"
        }
        titleRow.addView(altrixaStatusBadge(context, verdict, tone))
        hero.addView(titleRow, BacktestScreen.matchWidth(context))

        hero.addView(
            altrixaLabel(
                context,
                "$instrumentName \u00b7 $timeframe" + (dateRangeText(result)?.let { " \u00b7 $it" } ?: "")
            ).apply { setTextColor(AltrixaColors.textSecondary) },
            BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceXs)
        )

        hero.addView(
            altrixaCaption(context, "Net P&L"),
            BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceLg)
        )
        val pnlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pnlRow.addView(
            TextView(context).apply {
                text = BacktestFormat.signedMoney(m.netProfit)
                textSize = AltrixaDimens.textDisplay + 4f
                setTextColor(toneColor)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        pnlRow.addView(altrixaStatusBadge(context, BacktestFormat.signedPercent(m.totalReturnPercent), tone))
        hero.addView(pnlRow, BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceXs))

        hero.addView(altrixaDivider(context))
        val capitalRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        capitalRow.addView(
            labelledValue(context, "Initial capital", BacktestFormat.money(initial), AltrixaColors.textPrimary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        capitalRow.addView(
            labelledValue(context, "Final equity", BacktestFormat.money(result.finalEquity), toneColor),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        hero.addView(capitalRow, BacktestScreen.matchWidth(context))
        container.addView(hero, BacktestScreen.topGap(context))

        // ---- KPI grid ----
        container.addView(altrixaSectionHeader(context, "Performance"))
        val returnTone = if (m.totalReturnPercent >= 0.0) AltrixaTone.POSITIVE else AltrixaTone.NEGATIVE

        val winRateCard = altrixaMetricCard(
            context, "Win rate", BacktestFormat.percent(m.winRate * 100.0), AltrixaTone.NEUTRAL
        )
        winRateCard.addView(
            AltrixaProgressBar(context).apply { setProgress((m.winRate * 100.0).toInt()) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dpToPx(5)
            ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceSm) }
        )

        val profitFactorCard = altrixaMetricCard(
            context,
            "Profit factor",
            m.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A",
            AltrixaTone.NEUTRAL
        )
        if (m.profitFactor == null) {
            profitFactorCard.addView(
                smallNote(context, if (m.totalTrades == 0) "No trades" else "No losing trades")
            )
        }

        val drawdownCard = altrixaMetricCard(
            context,
            "Max drawdown",
            BacktestFormat.percent(m.maxDrawdownPercent),
            if (m.maxDrawdown > 0.0) AltrixaTone.NEGATIVE else AltrixaTone.NEUTRAL
        )
        drawdownCard.addView(smallNote(context, BacktestFormat.money(m.maxDrawdown)))

        pairRow(
            context, container,
            altrixaMetricCard(context, "Return", BacktestFormat.signedPercent(m.totalReturnPercent), returnTone),
            altrixaMetricCard(context, "Final equity", BacktestFormat.money(result.finalEquity), tone)
        )
        pairRow(
            context, container,
            altrixaMetricCard(context, "Total trades", m.totalTrades.toString(), AltrixaTone.NEUTRAL),
            winRateCard
        )
        pairRow(
            context, container,
            altrixaMetricCard(context, "Winning trades", m.winningTrades.toString(), AltrixaTone.POSITIVE),
            altrixaMetricCard(
                context, "Losing trades", m.losingTrades.toString(),
                if (m.losingTrades > 0) AltrixaTone.NEGATIVE else AltrixaTone.NEUTRAL
            )
        )
        pairRow(context, container, profitFactorCard, drawdownCard)

        // ---- Equity curve ----
        container.addView(altrixaSectionHeader(context, "Equity curve"))
        val equityCard = altrixaCard(context)
        if (result.equityCurve.size < 2) {
            equityCard.addView(
                altrixaStatusBlock(
                    context,
                    AltrixaIconKind.CHART,
                    AltrixaColors.textSecondary,
                    "No equity curve",
                    "This run did not record enough data points to draw a curve."
                ),
                BacktestScreen.matchWidth(context)
            )
        } else {
            val chart = EquityCurveView(context).apply {
                setData(result.equityCurve, initial, intraday = timeframe != "1D")
            }
            equityCard.addView(
                chart,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpToPx(210))
            )
            val first = result.equityCurve.first().equity
            val last = result.equityCurve.last().equity
            equityCard.addView(
                altrixaLabel(
                    context,
                    "${BacktestFormat.money(first)}  \u2192  ${BacktestFormat.money(last)}   \u00b7   touch and drag to inspect"
                ).apply {
                    textSize = AltrixaDimens.textSmall
                    setTextColor(AltrixaColors.textMuted)
                },
                BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceSm)
            )
        }
        container.addView(equityCard, BacktestScreen.topGap(context))

        // ---- Trade statistics ----
        container.addView(altrixaSectionHeader(context, "Trade statistics"))
        val stats = altrixaCard(context)
        if (m.totalTrades > 0) {
            val split = AltrixaSplitBar(context).apply { setCounts(m.winningTrades, m.losingTrades) }
            stats.addView(
                split,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dpToPx(8)
                ).apply { bottomMargin = context.dpToPx(AltrixaDimens.spaceMd) }
            )
        }
        stats.addView(fieldRow(context, "Average trade", BacktestFormat.signedMoney(m.averageTradePnl)), BacktestScreen.matchWidth(context))
        stats.addView(fieldRow(context, "Average win", m.averageWinningTrade?.let { BacktestFormat.signedMoney(it) } ?: "\u2014"), BacktestScreen.matchWidth(context))
        stats.addView(fieldRow(context, "Average loss", m.averageLosingTrade?.let { BacktestFormat.signedMoney(it) } ?: "\u2014"), BacktestScreen.matchWidth(context))
        stats.addView(fieldRow(context, "Gross profit", BacktestFormat.signedMoney(m.grossProfit)), BacktestScreen.matchWidth(context))
        stats.addView(fieldRow(context, "Gross loss", BacktestFormat.signedMoney(m.grossLoss)), BacktestScreen.matchWidth(context))
        container.addView(stats, BacktestScreen.topGap(context))
    }

    // -----------------------------------------------------------------
    // Test configuration summary
    // -----------------------------------------------------------------

    private fun configurationCard(
        context: Context,
        container: LinearLayout,
        results: List<BacktestResult>,
        instrumentName: String,
        timeframe: String,
        candleCount: Int,
        initialCapital: Double,
        positionSizing: PositionSizing,
        job: BacktestJobStore.Job?
    ) {
        container.addView(altrixaSectionHeader(context, "Test configuration"))
        val card = altrixaCard(context)

        val exchange = job?.instrumentExchange?.takeIf { it.isNotBlank() }
        card.addView(
            fieldRow(context, "Instrument", if (exchange != null) "$instrumentName ($exchange)" else instrumentName),
            BacktestScreen.matchWidth(context)
        )
        card.addView(fieldRow(context, "Timeframe", timeframe), BacktestScreen.matchWidth(context))

        val range = results.firstNotNullOfOrNull { dateRangeText(it) }
        if (range != null) {
            card.addView(fieldRow(context, "Date range", range), BacktestScreen.matchWidth(context))
        }
        card.addView(
            fieldRow(context, "Candles tested", "%,d".format(Locale.US, candleCount)),
            BacktestScreen.matchWidth(context)
        )
        card.addView(fieldRow(context, "Initial capital", BacktestFormat.money(initialCapital)), BacktestScreen.matchWidth(context))
        val lotSize = results.firstOrNull()?.config?.lotSize ?: 1
        if (lotSize > 1) {
            card.addView(fieldRow(context, "Contract size", "1 lot = $lotSize units"), BacktestScreen.matchWidth(context))
        }
        card.addView(
            fieldRow(context, "Position sizing", BacktestFormat.sizingLabel(positionSizing, lotSize)),
            BacktestScreen.matchWidth(context)
        )

        if (job != null) {
            val runtime = java.time.Duration.between(job.createdAt, job.updatedAt).toMillis()
            if (job.status == BacktestJobStore.Status.COMPLETED && runtime >= 0L) {
                card.addView(fieldRow(context, "Run time", BacktestFormat.duration(runtime)), BacktestScreen.matchWidth(context))
                val completed = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.US)
                    .withZone(zone)
                    .format(job.updatedAt)
                card.addView(fieldRow(context, "Completed", completed), BacktestScreen.matchWidth(context))
            }
        }

        card.addView(altrixaDivider(context))
        card.addView(altrixaCaption(context, if (results.size > 1) "Strategies & parameters" else "Strategy & parameters"))

        results.forEachIndexed { index, result ->
            val configuration = job?.strategies?.firstOrNull {
                BacktestFormat.strategyName(it.strategyId) == result.strategyName
            } ?: job?.strategies?.getOrNull(index)

            val parameterText = when {
                configuration == null -> null
                configuration.parameters.isEmpty() -> "Default parameters"
                else -> configuration.parameters.entries.joinToString("  \u00b7  ") {
                    "${BacktestFormat.parameterLabel(it.key)} ${BacktestFormat.parameterValue(it.value)}"
                }
            }

            card.addView(
                TextView(context).apply {
                    text = result.strategyName
                    textSize = AltrixaDimens.textBody
                    setTextColor(AltrixaColors.textPrimary)
                    setTypeface(typeface, Typeface.BOLD)
                },
                BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceSm)
            )
            if (parameterText != null) {
                card.addView(
                    TextView(context).apply {
                        text = parameterText
                        textSize = AltrixaDimens.textSmall
                        setTextColor(AltrixaColors.textSecondary)
                    },
                    BacktestScreen.matchWidth(context, topMargin = 2)
                )
            }
        }

        container.addView(card, BacktestScreen.topGap(context))
    }

    private fun dateRangeText(result: BacktestResult): String? {
        val first = result.equityCurve.firstOrNull()?.timestamp ?: return null
        val last = result.equityCurve.lastOrNull()?.timestamp ?: return null
        val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US).withZone(zone)
        val a = fmt.format(first)
        val b = fmt.format(last)
        return if (a == b) a else "$a \u2013 $b"
    }

    // -----------------------------------------------------------------
    // Trades
    // -----------------------------------------------------------------

    private fun tradesSection(
        context: Context,
        container: LinearLayout,
        trades: List<BacktestTrade>,
        intraday: Boolean,
        lotSize: Int
    ) {
        container.addView(altrixaSectionHeader(context, "Trades (${trades.size})"))
        val card = altrixaCard(context)

        if (lotSize > 1 && trades.isNotEmpty()) {
            card.addView(
                altrixaLabel(
                    context,
                    "Notional exposure is contract value (price \u00d7 quantity), not the margin required."
                ).apply {
                    textSize = AltrixaDimens.textSmall
                    setTextColor(AltrixaColors.textMuted)
                },
                BacktestScreen.matchWidth(context, bottomMargin = AltrixaDimens.spaceMd)
            )
        }

        if (trades.isEmpty()) {
            card.addView(
                altrixaStatusBlock(
                    context,
                    AltrixaIconKind.CHART,
                    AltrixaColors.textSecondary,
                    "No trades taken",
                    "The strategy produced no completed trades in this backtest window."
                ),
                BacktestScreen.matchWidth(context)
            )
            container.addView(card, BacktestScreen.topGap(context))
            return
        }

        val format = DateTimeFormatter
            .ofPattern(if (intraday) "dd MMM HH:mm" else "dd MMM yy", Locale.US)
            .withZone(zone)
        val numbered = trades.withIndex().toList()

        val filters = listOf("All", "Winners", "Losers")
        var filter = 0
        var shown = TRADES_PAGE

        val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val moreButton = altrixaSecondaryButton(context, "SHOW MORE") {}
        val emptyNote = altrixaLabel(context, "No trades match this filter.").apply {
            visibility = View.GONE
        }

        fun rebuild() {
            val filtered = when (filter) {
                1 -> numbered.filter { it.value.isWin }
                2 -> numbered.filter { !it.value.isWin }
                else -> numbered
            }.asReversed()

            list.removeAllViews()
            filtered.take(shown).forEachIndexed { position, indexed ->
                list.addView(
                    tradeRow(context, indexed.index + 1, indexed.value, format, lotSize),
                    BacktestScreen.matchWidth(context, topMargin = if (position == 0) 0 else AltrixaDimens.spaceSm)
                )
            }
            emptyNote.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            moreButton.visibility = if (filtered.size > shown) View.VISIBLE else View.GONE
            moreButton.text = "SHOW MORE (${filtered.size - shown} remaining)"
        }

        fun buildChips() {
            chipRow.removeAllViews()
            filters.forEachIndexed { index, name ->
                chipRow.addView(
                    altrixaChip(context, name, index == filter) {
                        filter = index
                        shown = TRADES_PAGE
                        buildChips()
                        rebuild()
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = context.dpToPx(AltrixaDimens.spaceSm) }
                )
            }
        }

        moreButton.setOnClickListener {
            shown += TRADES_PAGE
            rebuild()
        }

        card.addView(chipRow, BacktestScreen.matchWidth(context, bottomMargin = AltrixaDimens.spaceMd))
        card.addView(list, BacktestScreen.matchWidth(context))
        card.addView(emptyNote, BacktestScreen.matchWidth(context))
        card.addView(moreButton, BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceMd))
        container.addView(card, BacktestScreen.topGap(context))

        buildChips()
        rebuild()
    }

    private fun tradeRow(
        context: Context,
        number: Int,
        trade: BacktestTrade,
        format: DateTimeFormatter,
        lotSize: Int
    ): LinearLayout {
        val tone = if (trade.isWin) AltrixaTone.POSITIVE else AltrixaTone.NEGATIVE
        val color = altrixaToneColor(tone)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = altrixaRounded(context, AltrixaColors.surfaceVariant, AltrixaDimens.radiusMd, AltrixaColors.border)
            setPadding(
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd)
            )
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            altrixaStatusBadge(
                context,
                if (trade.direction == TradeDirection.LONG) "LONG" else "SHORT",
                if (trade.direction == TradeDirection.LONG) AltrixaTone.ACCENT else AltrixaTone.WARNING
            )
        )
        top.addView(
            TextView(context).apply {
                text = "#$number"
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textFaint)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dpToPx(AltrixaDimens.spaceSm)
            }
        )
        top.addView(
            TextView(context).apply {
                text = BacktestFormat.signedMoney(trade.grossPnl)
                textSize = AltrixaDimens.textSubtitle
                setTextColor(color)
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        row.addView(top, BacktestScreen.matchWidth(context))

        val times = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        times.addView(
            TextView(context).apply {
                text = "${format.format(trade.entryTimestamp)}  \u2192  ${format.format(trade.exitTimestamp)}"
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textLabel)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        times.addView(
            TextView(context).apply {
                text = BacktestFormat.signedPercent(trade.returnPercent)
                textSize = AltrixaDimens.textSmall
                setTextColor(color)
            }
        )
        row.addView(times, BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceSm))

        val lots = BacktestFormat.lotsLabel(trade.quantity, lotSize)
        val quantityPart = if (lots != null) {
            "Qty ${quantityText(trade.quantity)} ($lots)"
        } else {
            "Qty ${quantityText(trade.quantity)}"
        }
        row.addView(
            TextView(context).apply {
                text = "Entry ${BacktestFormat.money(trade.entryPrice)}  \u2192  Exit ${BacktestFormat.money(trade.exitPrice)}" +
                    "  \u00b7  $quantityPart  \u00b7  ${trade.holdingPeriodBars} bars"
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.textMuted)
            },
            BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceXs)
        )

        // Notional exposure is shown on its own line: it is contract value,
        // not margin and not profit/loss.
        row.addView(
            TextView(context).apply {
                text = "Notional exposure ${BacktestFormat.money(trade.notionalExposure)}"
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.textMuted)
            },
            BacktestScreen.matchWidth(context, topMargin = 2)
        )

        // Exit reason exactly as recorded by the engine.
        row.addView(
            TextView(context).apply {
                text = "Exit reason: ${BacktestFormat.exitReasonLabel(trade)}"
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textLabel)
                setTypeface(typeface, Typeface.BOLD)
            },
            BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceSm)
        )
        trade.exitDetail?.let { detail ->
            row.addView(
                TextView(context).apply {
                    text = "Signal basis: $detail"
                    textSize = AltrixaDimens.textCaption
                    setTextColor(AltrixaColors.textMuted)
                },
                BacktestScreen.matchWidth(context, topMargin = 2)
            )
        }
        return row
    }

    private fun quantityText(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
    }

    // -----------------------------------------------------------------
    // Small building blocks
    // -----------------------------------------------------------------

    private fun pairRow(context: Context, container: LinearLayout, left: View, right: View) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = context.dpToPx(AltrixaDimens.spaceSm)
            }
        )
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        container.addView(row, BacktestScreen.topGap(context))
    }

    private fun smallNote(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = AltrixaDimens.textSmall
        setTextColor(AltrixaColors.textMuted)
        setPadding(0, context.dpToPx(2), 0, 0)
    }

    private fun labelledValue(context: Context, label: String, value: String, color: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(altrixaCaption(context, label))
            addView(
                TextView(context).apply {
                    text = value
                    textSize = AltrixaDimens.textSection
                    setTextColor(color)
                    setTypeface(typeface, Typeface.BOLD)
                },
                BacktestScreen.matchWidth(context, topMargin = AltrixaDimens.spaceXs)
            )
        }

    private fun fieldRow(context: Context, label: String, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dpToPx(AltrixaDimens.spaceXs), 0, context.dpToPx(AltrixaDimens.spaceXs))
            addView(
                altrixaLabel(context, label).apply { setTextColor(AltrixaColors.textSecondary) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = AltrixaDimens.textBody
                    setTextColor(AltrixaColors.textPrimary)
                    gravity = Gravity.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f)
            )
        }
}
