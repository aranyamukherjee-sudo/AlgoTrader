package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.ResearchCostModel

/**
 * Phase 3 Patch 11 — what the Backtest UI says about P&L and assumed costs.
 *
 * Pure and Android-free so the wording and the visibility rule are unit-tested
 * on the JVM. The screens render exactly what this returns.
 *
 * The headline P&L of a backtest has always been gross (final equity minus
 * initial capital, no costs), so it is labelled [PNL_BEFORE_COSTS] for every
 * instrument type. Cost-adjusted figures appear only when the run used a
 * non-zero assumed-cost model, and always with [assumptionCaption].
 */
object BacktestCostPresentation {

    /** Label for the gross headline P&L, for index and futures alike. */
    const val PNL_BEFORE_COSTS = "P&L Before Costs"

    const val COSTS_SECTION_TITLE = "Assumed research costs"

    const val SETUP_SECTION_TITLE = "Assumed research costs (optional)"

    const val RANKING_CAPTION = "Ranked by P&L before costs."

    /**
     * Cost-adjusted ranking is comparable only when every result uses the
     * same non-zero research-cost assumptions.
     */
    fun usesCostAdjustedComparison(results: List<BacktestResult>): Boolean {
        if (results.isEmpty()) return false
        val model = results.first().config.researchCostModel
        return isVisible(model) &&
            results.all { it.config.researchCostModel == model }
    }

    fun comparisonCaption(results: List<BacktestResult>): String = when {
        usesCostAdjustedComparison(results) ->
            "Ranked by cost-adjusted P&L using the same assumed cost model."
        results.map { it.config.researchCostModel }.distinct().size > 1 ->
            "Cost assumptions differ; ranked by P&L before costs."
        else -> RANKING_CAPTION
    }

    fun comparisonPnl(
        result: BacktestResult,
        useCostAdjusted: Boolean
    ): Double = if (useCostAdjusted) {
        result.metrics.costAdjustedNetProfit
    } else {
        result.metrics.netProfit
    }

    /**
     * P3P15: rank by the selected P&L metric, using strategy name to resolve
     * exact ties deterministically. This does not introduce a composite score.
     */
    fun rankResults(
        results: List<BacktestResult>,
        useCostAdjusted: Boolean
    ): List<BacktestResult> = results.sortedWith(
        compareByDescending<BacktestResult> {
            comparisonPnl(it, useCostAdjusted)
        }.thenBy {
            it.strategyName.trim().lowercase(java.util.Locale.ROOT)
        }.thenBy {
            it.strategyName
        }
    )

    /** Setup-screen explanation, shown whether or not costs are entered. */
    const val SETUP_CAPTION =
        "Generic assumptions for research only \u2014 not your broker's charges and " +
            "not statutory F&O charges. All zero (the default) leaves results unchanged."

    private const val GENERIC_CAPTION =
        "Generic assumed research friction applied to the simulated trades. " +
            "These are not broker charges and not statutory F&O charges " +
            "(STT, exchange charges, GST, SEBI fees, stamp duty)."

    private const val FUTURES_CAPTION_SUFFIX =
        " For futures, statutory charges, net P&L after charges and break-even " +
            "remain NOT MODELLED."

    data class Row(val label: String, val value: String)

    data class Card(
        val title: String,
        val rows: List<Row>,
        val caption: String
    )

    /** True only when the model actually charges something. */
    fun isVisible(model: ResearchCostModel): Boolean =
        !BacktestCostAssumptions.isZero(model)

    fun assumptionCaption(isFutures: Boolean): String =
        if (isFutures) GENERIC_CAPTION + FUTURES_CAPTION_SUFFIX else GENERIC_CAPTION

    /**
     * The assumed-cost card for one result, or null when the run used no
     * assumed costs (including every result saved before this patch).
     */
    fun card(result: BacktestResult, isFutures: Boolean): Card? {
        val model = result.config.researchCostModel
        if (!isVisible(model)) return null

        val m = result.metrics
        return Card(
            title = COSTS_SECTION_TITLE,
            rows = listOf(
                Row(
                    "Commission",
                    "${BacktestCostAssumptions.plain(model.commissionRatePercent)}% of entry + exit notional"
                ),
                Row(
                    "Slippage",
                    "${BacktestCostAssumptions.plain(model.slippageBps)} bps of entry + exit notional"
                ),
                Row(
                    "Fixed cost",
                    "\u20b9${BacktestCostAssumptions.plain(model.fixedCostPerTrade)} per round trip"
                ),
                Row("Assumed costs", BacktestFormat.money(m.researchCosts)),
                Row("Cost-adjusted P&L", BacktestFormat.signedMoney(m.costAdjustedNetProfit)),
                Row("Cost-adjusted return", BacktestFormat.signedPercent(m.costAdjustedReturnPercent))
            ),
            caption = assumptionCaption(isFutures)
        )
    }
}
