package com.algotrader.app.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.algotrader.app.backtest.BacktestFormat
import com.algotrader.app.backtest.BacktestJobStore
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.ui.components.AltrixaCheckIndicator
import com.algotrader.app.ui.components.AltrixaIconKind
import com.algotrader.app.ui.components.AltrixaProgressBar
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaBanner
import com.algotrader.app.ui.components.altrixaCaption
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaInput
import com.algotrader.app.ui.components.altrixaLabel
import com.algotrader.app.ui.components.altrixaPrimaryButton
import com.algotrader.app.ui.components.altrixaRounded
import com.algotrader.app.ui.components.altrixaSectionHeader
import com.algotrader.app.ui.components.altrixaSegmented
import com.algotrader.app.ui.components.altrixaStatusBadge
import com.algotrader.app.ui.components.altrixaStatusBlock
import com.algotrader.app.ui.components.altrixaStyleInput
import com.algotrader.app.ui.components.altrixaTint
import com.algotrader.app.ui.components.altrixaTitle
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.PositionSizing
import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.Strategy
import com.algotrader.strategyengine.StrategyConfiguration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Backtest screen — premium presentation layer.
 *
 * The backtest execution path (WorkManager, BacktestJobStore, persistence,
 * restoration, notifications) is untouched. The caller still builds jobs and
 * passes real [Strategy] instances and real [BacktestResult]s in here; nothing
 * on this screen fabricates a result, a trade, or a metric.
 *
 * Entry points mirror the states MainActivity already renders:
 * configuration, running, results, error, plus the saved/active job lists.
 */
object BacktestScreen {

    private const val TAG_PERCENT = "BT_PERCENT"
    private const val TAG_BAR = "BT_BAR"
    private const val TAG_STEP = "BT_STEP"
    private const val TAG_ETA = "BT_ETA"

    // -----------------------------------------------------------------
    // Idle / configuration state
    // -----------------------------------------------------------------

    private class StrategyEntry(val strategy: Strategy) {
        var selected: Boolean = true
        val fields = linkedMapOf<String, EditText>()
    }

    fun renderConfig(
        context: Context,
        container: LinearLayout,
        instrumentName: String,
        timeframe: String,
        candleCount: Int,
        initialCapital: Double,
        positionQuantity: Double,
        strategies: List<Strategy>,
        lotSize: Int = 1,
        onRunBacktest: (
            List<StrategyConfiguration>,
            Double,
            PositionSizing
        ) -> Unit
    ) {
        header(context, container, "Strategy performance analysis")

        // ---- Test setup ----
        container.addView(altrixaSectionHeader(context, "Test setup"))
        val setup = altrixaCard(context)
        val tiles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(infoTile(context, "Instrument", instrumentName), weighted(context, 1.5f))
        tiles.addView(infoTile(context, "Timeframe", timeframe), weighted(context, 1f))
        tiles.addView(
            infoTile(context, "Candles", if (candleCount > 0) "%,d".format(Locale.US, candleCount) else "\u2014"),
            weighted(context, 1f)
        )
        setup.addView(tiles, matchWidth(context))
        if (candleCount > 0) {
            setup.addView(
                altrixaBanner(context, "Historical data ready \u2014 tests run on the loaded candles.", AltrixaTone.POSITIVE),
                matchWidth(context, topMargin = AltrixaDimens.spaceMd)
            )
        } else {
            setup.addView(
                altrixaBanner(
                    context,
                    "No historical candles loaded yet \u2014 open Home first, then come back here.",
                    AltrixaTone.WARNING
                ),
                matchWidth(context, topMargin = AltrixaDimens.spaceMd)
            )
        }
        container.addView(setup, topGap(context))

        // ---- Capital & sizing ----
        container.addView(altrixaSectionHeader(context, "Capital & sizing"))
        val risk = altrixaCard(context)

        risk.addView(altrixaCaption(context, "Initial capital (\u20b9)"))
        val capital = altrixaInput(context, BacktestFormat.quantity(initialCapital))
        risk.addView(capital, matchWidth(context, topMargin = AltrixaDimens.spaceXs + 2))

        risk.addView(
            altrixaCaption(context, "Position sizing"),
            matchWidth(context, topMargin = AltrixaDimens.spaceLg)
        )
        // Instruments with a contract size (F&O) trade in whole lots only.
        val lotMode = lotSize > 1
        val quantityCaption = altrixaCaption(context, if (lotMode) "Lots per trade" else "Units per trade")
        val quantity = altrixaInput(
            context,
            if (lotMode) "1" else BacktestFormat.quantity(positionQuantity)
        )
        if (lotMode) quantity.inputType = InputType.TYPE_CLASS_NUMBER
        val helper = altrixaLabel(
            context,
            if (lotMode) {
                "1 lot = $lotSize units. Quantity is always a whole number of lots. " +
                    "Notional exposure (price \u00d7 quantity) is contract value, not the margin required."
            } else {
                "Every entry trades this fixed number of units."
            }
        ).apply {
            textSize = AltrixaDimens.textSmall
            setTextColor(AltrixaColors.textMuted)
        }
        var percentMode = false
        if (lotMode) {
            risk.addView(
                altrixaBanner(context, "Contract: 1 lot = $lotSize units", AltrixaTone.ACCENT),
                matchWidth(context, topMargin = AltrixaDimens.spaceSm)
            )
        } else {
            val sizingToggle = altrixaSegmented(context, listOf("Fixed quantity", "% of equity"), 0) { index ->
                percentMode = index == 1
                quantityCaption.text = if (percentMode) "% OF EQUITY PER TRADE" else "UNITS PER TRADE"
                helper.text = if (percentMode) {
                    "Each entry allocates this percentage (up to 100) of current equity."
                } else {
                    "Every entry trades this fixed number of units."
                }
            }
            risk.addView(sizingToggle, matchWidth(context, topMargin = AltrixaDimens.spaceSm))
        }
        risk.addView(quantityCaption, matchWidth(context, topMargin = AltrixaDimens.spaceMd))
        risk.addView(quantity, matchWidth(context, topMargin = AltrixaDimens.spaceXs + 2))
        risk.addView(helper, matchWidth(context, topMargin = AltrixaDimens.spaceSm))
        container.addView(risk, topGap(context))

        // ---- Strategies ----
        val entries = strategies.map { StrategyEntry(it) }
        val countLabel = TextView(context).apply {
            textSize = AltrixaDimens.textSmall
            setTextColor(AltrixaColors.textMuted)
        }
        val toggleAll = TextView(context).apply {
            textSize = AltrixaDimens.textSmall
            setTextColor(AltrixaColors.accentBright)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            isClickable = true
            setPadding(
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceSm),
                0,
                context.dpToPx(AltrixaDimens.spaceSm)
            )
        }
        val errorBanner = altrixaBanner(context, "", AltrixaTone.NEGATIVE).apply { visibility = View.GONE }
        val runButton = altrixaPrimaryButton(context, "RUN BACKTEST") {}

        fun refreshSummary() {
            val selectedCount = entries.count { it.selected }
            countLabel.text = "$selectedCount of ${entries.size} selected"
            toggleAll.text = if (selectedCount == entries.size) "UNSELECT ALL" else "SELECT ALL"
            runButton.text = when (selectedCount) {
                0 -> "SELECT A STRATEGY"
                1 -> "RUN BACKTEST \u00b7 1 STRATEGY"
                else -> "RUN BACKTEST \u00b7 $selectedCount STRATEGIES"
            }
            runButton.isEnabled = selectedCount > 0
            runButton.alpha = if (selectedCount > 0) 1f else 0.5f
        }

        val strategyHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        headerText.addView(altrixaSectionHeader(context, "Strategies"))
        strategyHeader.addView(headerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        strategyHeader.addView(countLabel)
        strategyHeader.addView(toggleAll)
        container.addView(strategyHeader, matchWidth(context))

        val cardUpdaters = mutableListOf<() -> Unit>()
        entries.forEach { entry ->
            val (card, update) = strategyCard(context, entry) {
                errorBanner.visibility = View.GONE
                refreshSummary()
            }
            cardUpdaters += update
            container.addView(card, topGap(context))
        }

        toggleAll.setOnClickListener {
            val selectAll = entries.any { !it.selected }
            entries.forEach { it.selected = selectAll }
            cardUpdaters.forEach { it() }
            errorBanner.visibility = View.GONE
            refreshSummary()
        }

        // ---- Run ----
        container.addView(altrixaSectionHeader(context, "Run"))
        val runCard = altrixaCard(context)
        runCard.addView(errorBanner, matchWidth(context, bottomMargin = AltrixaDimens.spaceMd))
        runCard.addView(runButton, matchWidth(context))
        runCard.addView(
            altrixaLabel(
                context,
                "Backtests run in the background. You can leave this screen and you'll be notified when each one completes."
            ).apply {
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textMuted)
            },
            matchWidth(context, topMargin = AltrixaDimens.spaceSm)
        )
        container.addView(runCard, topGap(context))

        fun fail(message: String, field: EditText? = null) {
            errorBanner.text = message
            errorBanner.visibility = View.VISIBLE
            if (field != null) {
                altrixaStyleInput(field, hasError = true)
                field.requestFocus()
            }
        }

        runButton.setOnClickListener {
            altrixaStyleInput(capital, hasError = false)
            altrixaStyleInput(quantity, hasError = false)
            errorBanner.visibility = View.GONE

            val configurations = collectConfigurations(entries) { message, field -> fail(message, field) }
                ?: return@setOnClickListener
            if (configurations.isEmpty()) {
                fail("Select at least one strategy.")
                return@setOnClickListener
            }

            val capitalValue = capital.text.toString().trim().toDoubleOrNull()
            if (capitalValue == null || capitalValue <= 0.0) {
                fail("Initial capital must be greater than zero.", capital)
                return@setOnClickListener
            }

            val parsedQuantity = quantity.text.toString().trim().toDoubleOrNull()
            val quantityError: String? = when {
                lotMode ->
                    if (parsedQuantity == null || parsedQuantity < 1.0 || parsedQuantity != Math.floor(parsedQuantity)) {
                        "Lots must be a whole number of at least 1."
                    } else {
                        null
                    }
                parsedQuantity == null || parsedQuantity <= 0.0 ->
                    if (percentMode) "Equity percentage must be greater than zero."
                    else "Position quantity must be greater than zero."
                percentMode && (parsedQuantity ?: 0.0) > 100.0 -> "Equity percentage cannot exceed 100."
                else -> null
            }
            if (quantityError != null || parsedQuantity == null) {
                fail(quantityError ?: "Invalid quantity.", quantity)
                return@setOnClickListener
            }

            val sizing = when {
                lotMode -> PositionSizing.FixedLots(parsedQuantity.toInt())
                percentMode -> PositionSizing.PercentOfEquity(parsedQuantity)
                else -> PositionSizing.FixedQuantity(parsedQuantity)
            }

            onRunBacktest(configurations, capitalValue, sizing)
        }

        refreshSummary()
    }

    /** Builds one selectable strategy card. Returns the card and a function that re-applies its selected state. */
    private fun strategyCard(
        context: Context,
        entry: StrategyEntry,
        onSelectionChanged: () -> Unit
    ): Pair<LinearLayout, () -> Unit> {
        val strategy = entry.strategy
        val parameters = strategy.metadata.parameters
        val card = altrixaCard(context)

        val indicator = AltrixaCheckIndicator(context)
        val nameView = TextView(context).apply {
            text = strategy.name
            textSize = AltrixaDimens.textSection
            setTextColor(AltrixaColors.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
        }
        val directionTag = when (strategy.metadata.direction) {
            PositionDirection.LONG_ONLY -> "LONG ONLY"
            PositionDirection.SHORT_ONLY -> "SHORT ONLY"
            PositionDirection.LONG_AND_SHORT -> "LONG / SHORT"
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        top.addView(indicator)
        top.addView(
            nameView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dpToPx(AltrixaDimens.spaceMd)
                marginEnd = context.dpToPx(AltrixaDimens.spaceSm)
            }
        )
        top.addView(altrixaStatusBadge(context, directionTag, AltrixaTone.ACCENT))
        card.addView(top, matchWidth(context))

        card.addView(
            altrixaLabel(context, strategy.metadata.description).apply {
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textSecondary)
            },
            matchWidth(context, topMargin = AltrixaDimens.spaceSm)
        )

        // Parameter summary + expandable editor.
        val editor = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val toggleEditor = TextView(context).apply {
            text = "EDIT PARAMETERS"
            textSize = AltrixaDimens.textCaption
            setTextColor(AltrixaColors.accentBright)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            isClickable = true
            setPadding(0, context.dpToPx(AltrixaDimens.spaceSm), 0, context.dpToPx(AltrixaDimens.spaceXs))
        }

        if (parameters.isNotEmpty()) {
            val summary = parameters.joinToString("  \u00b7  ") {
                "${BacktestFormat.parameterLabel(it.name)} ${BacktestFormat.parameterValue(it.value)}"
            }
            card.addView(
                altrixaCaption(context, "Parameters", AltrixaColors.textFaint),
                matchWidth(context, topMargin = AltrixaDimens.spaceMd)
            )
            card.addView(
                altrixaLabel(context, summary).apply {
                    textSize = AltrixaDimens.textSmall
                    setTextColor(AltrixaColors.textLabel)
                },
                matchWidth(context, topMargin = AltrixaDimens.spaceXs)
            )

            parameters.forEach { parameter ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    altrixaLabel(context, BacktestFormat.parameterLabel(parameter.name)),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                val field = altrixaInput(context, BacktestFormat.parameterValue(parameter.value), signed = true)
                entry.fields[parameter.name] = field
                row.addView(field, LinearLayout.LayoutParams(context.dpToPx(110), LinearLayout.LayoutParams.WRAP_CONTENT))
                editor.addView(row, matchWidth(context, topMargin = AltrixaDimens.spaceSm))
            }

            editor.addView(
                altrixaLabel(
                    context,
                    "Entry: ${strategy.metadata.entryRule}\nExit: ${strategy.metadata.exitRule}"
                ).apply {
                    textSize = AltrixaDimens.textSmall
                    setTextColor(AltrixaColors.textMuted)
                },
                matchWidth(context, topMargin = AltrixaDimens.spaceMd)
            )

            toggleEditor.setOnClickListener {
                val open = editor.visibility != View.VISIBLE
                editor.visibility = if (open) View.VISIBLE else View.GONE
                toggleEditor.text = if (open) "HIDE PARAMETERS" else "EDIT PARAMETERS"
            }
            card.addView(toggleEditor, matchWidth(context))
            card.addView(editor, matchWidth(context))
        }

        fun applyState() {
            indicator.setChecked(entry.selected)
            card.background = altrixaRounded(
                context,
                if (entry.selected) altrixaTint(AltrixaColors.accent, 22) else AltrixaColors.surface,
                AltrixaDimens.radiusLg,
                if (entry.selected) AltrixaColors.accent else AltrixaColors.border
            )
            nameView.setTextColor(if (entry.selected) AltrixaColors.textPrimary else AltrixaColors.textSecondary)
            entry.fields.values.forEach {
                it.isEnabled = entry.selected
                it.alpha = if (entry.selected) 1f else 0.45f
            }
        }

        top.setOnClickListener {
            entry.selected = !entry.selected
            applyState()
            onSelectionChanged()
        }

        applyState()
        return Pair(card, { applyState() })
    }

    /**
     * Collects the configurations of all selected strategies. Returns null
     * (after reporting through [onInvalid]) if any parameter is not a number.
     */
    private fun collectConfigurations(
        entries: List<StrategyEntry>,
        onInvalid: (String, EditText?) -> Unit
    ): List<StrategyConfiguration>? {
        val configurations = mutableListOf<StrategyConfiguration>()

        for (entry in entries) {
            if (!entry.selected) continue

            val parameters = mutableMapOf<String, Double>()
            for (parameter in entry.strategy.metadata.parameters) {
                val field = entry.fields[parameter.name]
                val value = field?.text?.toString()?.trim()?.toDoubleOrNull()
                if (value == null) {
                    onInvalid(
                        "Invalid ${BacktestFormat.parameterLabel(parameter.name)} for ${entry.strategy.name}.",
                        field
                    )
                    return null
                }
                parameters[parameter.name] = value
            }

            configurations += StrategyConfiguration(
                strategyId = strategyIdFor(entry.strategy),
                parameters = parameters
            )
        }
        return configurations
    }

    private fun strategyIdFor(strategy: Strategy): String = when (strategy.name) {
        "Moving Average Crossover" -> "moving_average_crossover"
        "RSI" -> "rsi"
        "MACD" -> "macd"
        "Bollinger Bands" -> "bollinger_bands"
        "Donchian Channel Breakout" -> "donchian_channel"
        "Donchian + EMA Trend" -> "donchian_ema"
        "CPR + EMA Trend" -> "cpr_ema"
        else -> error("Unregistered strategy: ${strategy.name}")
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
        strategies: List<Strategy>,
        progress: Int = 0,
        currentStep: String = "Queued"
    ) {
        header(context, container, "Strategy performance analysis")

        container.addView(altrixaSectionHeader(context, "In progress"))
        val card = altrixaCard(context)

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            altrixaStatusBadge(context, "RUNNING", AltrixaTone.ACCENT),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        topRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        topRow.addView(
            TextView(context).apply {
                tag = TAG_ETA
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textSecondary)
                visibility = View.GONE
            }
        )
        card.addView(topRow, matchWidth(context))

        card.addView(
            TextView(context).apply {
                text = strategies.joinToString(", ") { it.name }
                textSize = AltrixaDimens.textLarge
                setTextColor(AltrixaColors.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
            },
            matchWidth(context, topMargin = AltrixaDimens.spaceMd)
        )
        card.addView(
            altrixaLabel(context, "$instrumentName \u00b7 $timeframe \u00b7 ${"%,d".format(Locale.US, candleCount)} candles")
                .apply { setTextColor(AltrixaColors.textSecondary) },
            matchWidth(context, topMargin = AltrixaDimens.spaceXs)
        )

        card.addView(
            TextView(context).apply {
                tag = TAG_PERCENT
                text = "${progress.coerceIn(0, 100)}%"
                textSize = AltrixaDimens.textDisplay + 8f
                setTextColor(AltrixaColors.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
            },
            matchWidth(context, topMargin = AltrixaDimens.spaceLg)
        )
        val bar = AltrixaProgressBar(context).apply {
            tag = TAG_BAR
            setProgress(progress)
        }
        card.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dpToPx(8)
            ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceSm) }
        )
        card.addView(
            altrixaLabel(context, "").apply {
                tag = TAG_STEP
                setTextColor(AltrixaColors.textSecondary)
            },
            matchWidth(context, topMargin = AltrixaDimens.spaceMd)
        )
        card.addView(
            altrixaBanner(
                context,
                "Running in the background. You can safely leave this screen \u2014 you'll be notified when it completes.",
                AltrixaTone.ACCENT
            ),
            matchWidth(context, topMargin = AltrixaDimens.spaceMd)
        )
        container.addView(card, topGap(context))

        updateRunning(container, progress, currentStep)
    }

    /** Updates the live progress widgets created by [renderRunning]. No-op if they are not displayed. */
    fun updateRunning(container: LinearLayout, progress: Int, currentStep: String) {
        val value = progress.coerceIn(0, 100)
        container.findViewWithTag<TextView>(TAG_PERCENT)?.text = "$value%"
        container.findViewWithTag<AltrixaProgressBar>(TAG_BAR)?.setProgress(value)

        val etaIndex = currentStep.lastIndexOf("ETA ")
        val step = if (etaIndex >= 0) {
            currentStep.substring(0, etaIndex).trimEnd().removeSuffix("\u00b7").trim()
        } else {
            currentStep
        }
        val eta = if (etaIndex >= 0) currentStep.substring(etaIndex + 4).trim() else ""

        container.findViewWithTag<TextView>(TAG_STEP)?.text = step.ifBlank { "Working\u2026" }
        container.findViewWithTag<TextView>(TAG_ETA)?.let {
            if (eta.isBlank()) {
                it.visibility = View.GONE
            } else {
                it.text = "ETA $eta"
                it.visibility = View.VISIBLE
            }
        }
    }

    // -----------------------------------------------------------------
    // Active backtests / saved tests
    // -----------------------------------------------------------------

    private fun isActive(job: BacktestJobStore.Job): Boolean =
        job.status != BacktestJobStore.Status.COMPLETED &&
            job.status != BacktestJobStore.Status.FAILED &&
            job.status != BacktestJobStore.Status.CANCELLED

    fun renderActiveBacktests(
        context: Context,
        container: LinearLayout,
        jobs: List<BacktestJobStore.Job>,
        selectedJobId: String?,
        onSelectJob: (String) -> Unit
    ) {
        val activeJobs = jobs.filter { isActive(it) }
        if (activeJobs.isEmpty()) return

        container.addView(altrixaSectionHeader(context, "Active Backtests"))
        val card = altrixaCard(context).apply { tag = "BACKTEST_ACTIVE_JOBS" }
        activeJobs.forEachIndexed { index, job ->
            card.addView(
                historyRow(context, job, highlighted = job.id == selectedJobId, onClick = onSelectJob),
                matchWidth(context, topMargin = if (index == 0) 0 else AltrixaDimens.spaceSm)
            )
        }
        container.addView(card, topGap(context))
    }

    /**
     * Persisted backtest jobs, so completed results stay discoverable after
     * leaving the screen or reopening the app. Shows a premium empty state
     * when nothing has been run yet.
     */
    fun renderSavedTests(
        context: Context,
        container: LinearLayout,
        jobs: List<BacktestJobStore.Job>,
        onOpenJob: (String) -> Unit
    ) {
        container.addView(altrixaSectionHeader(context, "Saved Tests"))
        val card = altrixaCard(context)

        if (jobs.isEmpty()) {
            card.addView(
                altrixaStatusBlock(
                    context,
                    AltrixaIconKind.CHART,
                    AltrixaColors.accentBright,
                    "No backtests yet",
                    "Select strategies above and run a backtest. Net P&L, equity curve and every trade will appear here."
                ),
                matchWidth(context)
            )
            container.addView(card, topGap(context))
            return
        }

        jobs.take(10).forEachIndexed { index, job ->
            card.addView(
                historyRow(context, job, highlighted = false, onClick = onOpenJob),
                matchWidth(context, topMargin = if (index == 0) 0 else AltrixaDimens.spaceSm)
            )
        }

        if (jobs.size > 10) {
            card.addView(
                altrixaLabel(context, "Showing the 10 most recent saved tests").apply {
                    textSize = AltrixaDimens.textSmall
                    setTextColor(AltrixaColors.textMuted)
                },
                matchWidth(context, topMargin = AltrixaDimens.spaceSm)
            )
        }
        container.addView(card, topGap(context))
    }

    private fun historyRow(
        context: Context,
        job: BacktestJobStore.Job,
        highlighted: Boolean,
        onClick: (String) -> Unit
    ): LinearLayout {
        val names = job.strategies.map { BacktestFormat.strategyName(it.strategyId) }
        val title = when {
            names.isEmpty() -> "Backtest"
            names.size <= 2 -> names.joinToString(", ")
            else -> "${names.first()} +${names.size - 1} more"
        }

        val (badgeText, tone) = when (job.status) {
            BacktestJobStore.Status.COMPLETED -> "COMPLETE" to AltrixaTone.POSITIVE
            BacktestJobStore.Status.FAILED -> "FAILED" to AltrixaTone.NEGATIVE
            BacktestJobStore.Status.CANCELLED -> "CANCELLED" to AltrixaTone.NEUTRAL
            BacktestJobStore.Status.QUEUED -> "QUEUED" to AltrixaTone.WARNING
            else -> "${job.progress}%" to AltrixaTone.ACCENT
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = altrixaRounded(
                context,
                AltrixaColors.surfaceVariant,
                AltrixaDimens.radiusMd,
                if (highlighted) AltrixaColors.accent else AltrixaColors.border
            )
            setPadding(
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd)
            )
            setOnClickListener { onClick(job.id) }
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(
            TextView(context).apply {
                text = title
                textSize = AltrixaDimens.textSubtitle
                setTextColor(AltrixaColors.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        textCol.addView(
            TextView(context).apply {
                text = "${BacktestFormat.instrumentName(job.instrumentSymbol)} \u00b7 " +
                    BacktestFormat.timeframeLabel(job.timeframe)
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textSecondary)
            },
            matchWidth(context, topMargin = 2)
        )
        top.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(altrixaStatusBadge(context, badgeText, tone))
        row.addView(top, matchWidth(context))

        if (isActive(job)) {
            val bar = AltrixaProgressBar(context).apply { setProgress(job.progress) }
            row.addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dpToPx(6)
                ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceMd) }
            )
            row.addView(
                TextView(context).apply {
                    text = job.currentStep
                    textSize = AltrixaDimens.textCaption
                    setTextColor(AltrixaColors.textMuted)
                    maxLines = 2
                },
                matchWidth(context, topMargin = AltrixaDimens.spaceSm)
            )
        } else {
            val stamp = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.US)
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(job.createdAt)
            row.addView(
                TextView(context).apply {
                    text = "$stamp \u00b7 ${"%,d".format(Locale.US, job.candleCount)} candles"
                    textSize = AltrixaDimens.textCaption
                    setTextColor(AltrixaColors.textMuted)
                },
                matchWidth(context, topMargin = AltrixaDimens.spaceSm)
            )
        }
        return row
    }

    // -----------------------------------------------------------------
    // Error state
    // -----------------------------------------------------------------

    /**
     * Premium error card. [detail] (optional) is shown behind a
     * "Technical details" toggle so diagnostic text never overwhelms the UI.
     */
    fun renderError(
        context: Context,
        container: LinearLayout,
        message: String,
        title: String = "Backtest failed",
        detail: String? = null,
        onRetry: () -> Unit
    ) {
        header(context, container, "Strategy performance analysis")

        container.addView(altrixaSectionHeader(context, "Status"))
        val card = altrixaCard(context)
        val cancelled = title.contains("cancel", ignoreCase = true)
        card.addView(
            altrixaStatusBlock(
                context,
                if (cancelled) AltrixaIconKind.STOP else AltrixaIconKind.ALERT,
                if (cancelled) AltrixaColors.textSecondary else AltrixaColors.negative,
                title,
                message
            ),
            matchWidth(context)
        )

        if (!detail.isNullOrBlank()) {
            val detailView = altrixaLabel(context, detail).apply {
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.textMuted)
                visibility = View.GONE
                setTextIsSelectable(true)
            }
            val toggle = TextView(context).apply {
                text = "SHOW TECHNICAL DETAILS"
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.accentBright)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, context.dpToPx(AltrixaDimens.spaceSm), 0, context.dpToPx(AltrixaDimens.spaceSm))
                setOnClickListener {
                    val open = detailView.visibility != View.VISIBLE
                    detailView.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "HIDE TECHNICAL DETAILS" else "SHOW TECHNICAL DETAILS"
                }
            }
            card.addView(toggle, matchWidth(context))
            card.addView(detailView, matchWidth(context))
        }

        card.addView(
            altrixaPrimaryButton(context, "BACK TO BACKTEST SETUP") { onRetry() },
            matchWidth(context, topMargin = AltrixaDimens.spaceMd)
        )
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
        positionSizing: PositionSizing,
        results: List<BacktestResult>,
        job: BacktestJobStore.Job? = null,
        onRunAgain: () -> Unit
    ) {
        BacktestResultsScreen.render(
            context,
            container,
            instrumentName,
            timeframe,
            candleCount,
            initialCapital,
            positionSizing,
            results,
            job,
            onRunAgain
        )
    }

    // -----------------------------------------------------------------
    // Shared pieces
    // -----------------------------------------------------------------

    internal fun header(context: Context, container: LinearLayout, subtitle: String) {
        container.addView(altrixaCaption(context, "ALTRIXA", AltrixaColors.accent))
        container.addView(
            altrixaTitle(context, "Backtest").apply {
                setPadding(0, context.dpToPx(2), 0, 0)
            }
        )
        container.addView(
            altrixaLabel(context, subtitle).apply { setTextColor(AltrixaColors.textSecondary) },
            matchWidth(context, bottomMargin = AltrixaDimens.spaceXs)
        )
    }

    private fun infoTile(context: Context, label: String, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = altrixaRounded(context, AltrixaColors.surfaceVariant, AltrixaDimens.radiusMd, AltrixaColors.border)
            setPadding(
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd),
                context.dpToPx(AltrixaDimens.spaceMd)
            )
            addView(altrixaCaption(context, label))
            addView(
                TextView(context).apply {
                    text = value
                    textSize = AltrixaDimens.textSubtitle
                    setTextColor(AltrixaColors.textPrimary)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                },
                matchWidth(context, topMargin = AltrixaDimens.spaceXs)
            )
        }

    private fun weighted(context: Context, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            marginEnd = context.dpToPx(AltrixaDimens.spaceSm)
        }

    internal fun topGap(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dpToPx(4) }

    internal fun matchWidth(
        context: Context,
        topMargin: Int = 0,
        bottomMargin: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = context.dpToPx(topMargin)
            this.bottomMargin = context.dpToPx(bottomMargin)
        }
}
