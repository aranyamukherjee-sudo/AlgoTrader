package com.algotrader.app.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaEmptyState
import com.algotrader.app.ui.components.altrixaErrorState
import com.algotrader.app.ui.components.altrixaLabel
import com.algotrader.app.ui.components.altrixaLoadingState
import com.algotrader.app.ui.components.altrixaMetricCard
import com.algotrader.app.ui.components.altrixaPrimaryButton
import com.algotrader.app.ui.components.altrixaSecondaryButton
import com.algotrader.app.ui.components.altrixaSectionHeader
import com.algotrader.app.ui.components.altrixaStatusBadge
import com.algotrader.app.ui.components.altrixaTitle
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.EquityPoint
import com.algotrader.backtest.TradeDirection
import com.algotrader.strategy.Strategy
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Backtest screen (Part 1.9 — UI Foundation).
 *
 * This screen is a restyle of MainActivity's existing, already-real
 * showBacktest()/runBacktest()/renderBacktestResults() flow onto the ALTRIXA
 * visual language. The actual backtest execution path is untouched: the
 * caller still builds `BacktestConfig`/`BacktestEngine` itself and passes the
 * real `Strategy` instances and `BacktestResult`s in here — nothing here
 * fabricates a result, a trade, or a metric.
 *
 * `BacktestResult.equityCurve` and `BacktestResult.trades` were already
 * being computed by the engine but were not previously rendered anywhere;
 * this screen surfaces them (see [equityCurveSection] / [tradeResultsSection])
 * since real data for both already exists.
 *
 * Three render entry points mirror the three states MainActivity already
 * had: idle configuration, running, and results.
 */
object BacktestScreen {

    private const val MAX_TRADES_SHOWN = 20

    // -----------------------------------------------------------------
    // Idle / configuration state
    // -----------------------------------------------------------------

    fun renderConfig(
        context: Context,
        container: LinearLayout,
        instrumentName: String,
        timeframe: String,
        candleCount: Int,
        initialCapital: Double,
        positionQuantity: Double,
        strategies: List<Strategy>,
        onRunBacktest: () -> Unit
    ) {
        header(context, container)
        configurationCard(context, container, instrumentName, timeframe, candleCount, initialCapital, positionQuantity)
        strategySelectionCard(context, container, strategies)

        container.addView(altrixaSectionHeader(context, "Run"))
        val runCard = altrixaCard(context)
        val runButton = altrixaPrimaryButton(context, "RUN BACKTEST") { onRunBacktest() }
        runCard.addView(
            runButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        if (candleCount <= 0) {
            runCard.addView(
                altrixaLabel(context, "No historical candles loaded yet \u2014 open Home first, then come back here.")
                    .apply { setTextColor(AltrixaColors.warning) },
                matchWidth(context, topMargin = AltrixaDimens.spaceXs)
            )
        }
        container.addView(runCard, topGap(context))

        resultsEmptySection(context, container)
        equityEmptySection(context, container)
        tradesEmptySection(context, container)
    }

    // -----------------------------------------------------------------
    // Running state
    // -----------------------------------------------------------------

    fun renderRunning(
        context: Context,
        container: LinearLayout,
        instrumentName: String,
        timeframe: String,
        candleCount: Int,
        strategies: List<Strategy>
    ) {
        header(context, container)

        container.addView(altrixaSectionHeader(context, "Running"))
        val card = altrixaCard(context)
        card.addView(altrixaLabel(context, "$instrumentName \u00b7 $timeframe"), matchWidth(context))
        card.addView(altrixaLabel(context, "$candleCount real candles"), matchWidth(context))
        card.addView(
            altrixaLoadingState(context, "Testing ${strategies.joinToString(", ") { it.name }}\u2026"),
            matchWidth(context, topMargin = AltrixaDimens.spaceSm)
        )
        container.addView(card, topGap(context))
    }

    // -----------------------------------------------------------------
    // Error state
    // -----------------------------------------------------------------

    fun renderError(
        context: Context,
        container: LinearLayout,
        message: String,
        onRetry: () -> Unit
    ) {
        header(context, container)

        container.addView(altrixaSectionHeader(context, "Error"))
        val card = altrixaCard(context)
        card.addView(altrixaErrorState(context, message) { onRetry() })
        container.addView(card, topGap(context))
    }

    // -----------------------------------------------------------------
    // Results state
    // -----------------------------------------------------------------

    fun renderResults(
        context: Context,
        container: LinearLayout,
        instrumentName: String,
        timeframe: String,
        candleCount: Int,
        initialCapital: Double,
        positionQuantity: Double,
        results: List<BacktestResult>,
        onRunAgain: () -> Unit
    ) {
        header(context, container)

        container.addView(altrixaSectionHeader(context, "Test"))
        val testCard = altrixaCard(context)
        testCard.addView(fieldRow(context, "Instrument", "$instrumentName \u00b7 $timeframe"), matchWidth(context))
        testCard.addView(fieldRow(context, "Candles", "$candleCount"), matchWidth(context))
        testCard.addView(fieldRow(context, "Initial Capital", formatMoney(initialCapital)), matchWidth(context))
        testCard.addView(
            fieldRow(context, "Position Sizing", "Fixed qty ${formatQuantity(positionQuantity)}"),
            matchWidth(context)
        )
        container.addView(testCard, topGap(context))

        if (results.isEmpty()) {
            resultsEmptySection(context, container)
            equityEmptySection(context, container)
            tradesEmptySection(context, container)
        } else {
            results.forEach { result -> resultCard(context, container, result) }
        }

        val rerunButton = altrixaSecondaryButton(context, "RUN AGAIN") { onRunAgain() }
        container.addView(
            rerunButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceLg) }
        )
    }

    // -----------------------------------------------------------------
    // Shared pieces
    // -----------------------------------------------------------------

    private fun header(context: Context, container: LinearLayout) {
        container.addView(altrixaTitle(context, "Backtest"))
        container.addView(altrixaLabel(context, "Strategy performance analysis"))
    }

    private fun configurationCard(
        context: Context,
        container: LinearLayout,
        instrumentName: String,
        timeframe: String,
        candleCount: Int,
        initialCapital: Double,
        positionQuantity: Double
    ) {
        container.addView(altrixaSectionHeader(context, "Configuration"))
        val card = altrixaCard(context)
        card.addView(fieldRow(context, "Instrument", instrumentName), matchWidth(context))
        card.addView(fieldRow(context, "Timeframe", timeframe), matchWidth(context))
        card.addView(
            fieldRow(
                context,
                "Historical Candles",
                if (candleCount > 0) "$candleCount loaded" else "None loaded"
            ),
            matchWidth(context)
        )
        card.addView(fieldRow(context, "Initial Capital", formatMoney(initialCapital)), matchWidth(context))
        card.addView(
            fieldRow(context, "Position Sizing", "Fixed qty ${formatQuantity(positionQuantity)}"),
            matchWidth(context)
        )
        card.addView(
            altrixaLabel(
                context,
                "Data range uses whatever candles are currently loaded for this instrument/timeframe on Home \u2014 there is no separate date-range picker yet."
            ),
            matchWidth(context, topMargin = AltrixaDimens.spaceSm)
        )
        container.addView(card, topGap(context))
    }

    private fun strategySelectionCard(context: Context, container: LinearLayout, strategies: List<Strategy>) {
        container.addView(altrixaSectionHeader(context, "Strategies"))
        strategies.forEach { strategy ->
            val card = altrixaCard(context)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                altrixaLabel(context, strategy.name),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(altrixaStatusBadge(context, "READY", AltrixaTone.ACCENT))
            card.addView(row, matchWidth(context))
            card.addView(
                altrixaLabel(context, strategy.metadata.description),
                matchWidth(context, topMargin = AltrixaDimens.spaceXs)
            )
            container.addView(card, topGap(context))
        }
    }

    private fun resultsEmptySection(context: Context, container: LinearLayout) {
        container.addView(altrixaSectionHeader(context, "Results"))
        val card = altrixaCard(context)
        card.addView(altrixaEmptyState(context, "Run a backtest to view performance"))
        container.addView(card, topGap(context))
    }

    private fun equityEmptySection(context: Context, container: LinearLayout) {
        container.addView(altrixaSectionHeader(context, "Equity Curve"))
        val card = altrixaCard(context)
        card.addView(altrixaEmptyState(context, "No equity curve yet \u2014 run a backtest first"))
        container.addView(card, topGap(context))
    }

    private fun tradesEmptySection(context: Context, container: LinearLayout) {
        container.addView(altrixaSectionHeader(context, "Trade Results"))
        val card = altrixaCard(context)
        card.addView(altrixaEmptyState(context, "No trades yet \u2014 run a backtest first"))
        container.addView(card, topGap(context))
    }

    /** One strategy's full result: metrics grid, equity curve, trade list \u2014 all from real data. */
    private fun resultCard(context: Context, container: LinearLayout, result: BacktestResult) {
        val m = result.metrics

        container.addView(altrixaSectionHeader(context, result.strategyName))

        val summary = altrixaCard(context)
        summary.addView(fieldRow(context, "Final Equity", formatMoney(result.finalEquity)), matchWidth(context))
        summary.addView(
            fieldRow(context, "Winning / Losing", "${m.winningTrades} / ${m.losingTrades}"),
            matchWidth(context)
        )
        summary.addView(fieldRow(context, "Average Trade", formatMoney(m.averageTradePnl)), matchWidth(context))
        container.addView(summary, topGap(context))

        val netPositive = m.netProfit >= 0.0
        metricPairRow(
            context, container,
            "Net P&L", formatMoney(m.netProfit), if (netPositive) AltrixaTone.POSITIVE else AltrixaTone.NEGATIVE,
            "Return", formatPercent(m.totalReturnPercent), if (netPositive) AltrixaTone.POSITIVE else AltrixaTone.NEGATIVE
        )
        metricPairRow(
            context, container,
            "Win Rate", formatPercent(m.winRate * 100.0), AltrixaTone.NEUTRAL,
            "Trades", m.totalTrades.toString(), AltrixaTone.NEUTRAL
        )
        metricPairRow(
            context, container,
            "Max Drawdown", "${formatMoney(m.maxDrawdown)} (${formatPercent(m.maxDrawdownPercent)})", AltrixaTone.NEGATIVE,
            "Profit Factor", m.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A", AltrixaTone.NEUTRAL
        )

        equityCurveSection(context, container, result.equityCurve, result.config.initialCapital)
        tradeResultsSection(context, container, result.trades)
    }

    private fun equityCurveSection(
        context: Context,
        container: LinearLayout,
        equityCurve: List<EquityPoint>,
        initialCapital: Double
    ) {
        container.addView(altrixaSectionHeader(context, "Equity Curve"))
        val card = altrixaCard(context)
        if (equityCurve.isEmpty()) {
            card.addView(altrixaEmptyState(context, "No equity curve data for this run"))
        } else {
            val chart = EquityCurveView(context).apply { setData(equityCurve, initialCapital) }
            card.addView(
                chart,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpToPx(140))
            )
            val first = equityCurve.first().equity
            val last = equityCurve.last().equity
            card.addView(
                altrixaLabel(context, "Start ${formatMoney(first)}  \u2192  End ${formatMoney(last)}"),
                matchWidth(context, topMargin = AltrixaDimens.spaceSm)
            )
        }
        container.addView(card, topGap(context))
    }

    private fun tradeResultsSection(context: Context, container: LinearLayout, trades: List<BacktestTrade>) {
        container.addView(altrixaSectionHeader(context, "Trade Results"))
        val card = altrixaCard(context)
        if (trades.isEmpty()) {
            card.addView(altrixaEmptyState(context, "No trades were taken in this backtest window"))
        } else {
            val shown = trades.takeLast(MAX_TRADES_SHOWN)
            if (trades.size > shown.size) {
                card.addView(
                    altrixaLabel(context, "Showing last ${shown.size} of ${trades.size} trades"),
                    matchWidth(context, bottomMargin = AltrixaDimens.spaceXs)
                )
            }
            shown.forEachIndexed { index, trade ->
                card.addView(tradeRow(context, trade), matchWidth(context, topMargin = if (index == 0) 0 else AltrixaDimens.spaceXs))
            }
        }
        container.addView(card, topGap(context))
    }

    private fun tradeRow(context: Context, trade: BacktestTrade): LinearLayout {
        val dateFormat = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US).withZone(ZoneId.of("Asia/Kolkata"))
        val tone = if (trade.isWin) AltrixaTone.POSITIVE else AltrixaTone.NEGATIVE

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = "${trade.direction.label()}  ${dateFormat.format(trade.entryTimestamp)} \u2192 ${dateFormat.format(trade.exitTimestamp)}"
                    textSize = AltrixaDimens.textCaption
                    setTextColor(AltrixaColors.textSecondary)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(altrixaStatusBadge(context, formatMoney(trade.grossPnl), tone))
        }
    }

    private fun TradeDirection.label(): String = when (this) {
        TradeDirection.LONG -> "LONG"
        TradeDirection.SHORT -> "SHORT"
    }

    /** Two metric cards side by side, each reused from [altrixaMetricCard]. */
    private fun metricPairRow(
        context: Context, container: LinearLayout,
        labelA: String, valueA: String, toneA: AltrixaTone,
        labelB: String, valueB: String, toneB: AltrixaTone
    ) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            altrixaMetricCard(context, labelA, valueA, toneA),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = context.dpToPx(AltrixaDimens.spaceSm)
            }
        )
        row.addView(
            altrixaMetricCard(context, labelB, valueB, toneB),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(row, topGap(context))
    }

    /** A label + static value row, matching the pattern used by ExecutionScreen. */
    private fun fieldRow(context: Context, label: String, value: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                altrixaLabel(context, label),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = AltrixaDimens.textBody
                    setTextColor(AltrixaColors.textSecondary)
                }
            )
        }
    }

    private fun topGap(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dpToPx(4) }

    private fun matchWidth(context: Context, topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = context.dpToPx(topMargin)
            this.bottomMargin = context.dpToPx(bottomMargin)
        }

    private fun formatMoney(value: Double): String = String.format(Locale.US, "\u20b9%,.2f", value)

    private fun formatPercent(value: Double): String = String.format(Locale.US, "%.2f%%", value)

    private fun formatQuantity(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /**
     * Minimal equity-curve line chart. Self-contained to this screen \u2014 it
     * draws only the real [EquityPoint] series passed in via [setData]; it
     * never generates or interpolates data of its own.
     */
    private class EquityCurveView(context: Context) : View(context) {

        private var points: List<EquityPoint> = emptyList()
        private var initialCapital: Double = 0.0

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = AltrixaColors.chartGrid
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }

        fun setData(points: List<EquityPoint>, initialCapital: Double) {
            this.points = points
            this.initialCapital = initialCapital
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(AltrixaColors.chartBackground)
            if (points.size < 2) return

            val left = 8f
            val top = 8f
            val right = width - 8f
            val bottom = height - 8f

            val values = points.map { it.equity }
            val maxV = maxOf(values.max(), initialCapital)
            val minV = minOf(values.min(), initialCapital)
            val range = (maxV - minV).takeIf { it > 0.0 } ?: 1.0

            fun y(v: Double) = (bottom - ((v - minV) / range * (bottom - top))).toFloat()
            fun x(index: Int) = left + (right - left) * index / (points.size - 1)

            // Baseline at initial capital.
            val baseY = y(initialCapital)
            canvas.drawLine(left, baseY, right, baseY, baselinePaint)

            val finalPositive = values.last() >= initialCapital
            linePaint.color = if (finalPositive) AltrixaColors.positive else AltrixaColors.negative

            val path = Path()
            points.forEachIndexed { index, point ->
                val px = x(index)
                val py = y(point.equity)
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, linePaint)
        }
    }
}
