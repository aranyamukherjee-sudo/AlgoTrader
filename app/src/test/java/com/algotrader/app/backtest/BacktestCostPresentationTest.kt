package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.PerformanceMetrics
import com.algotrader.backtest.ResearchCostModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens render exactly what BacktestCostPresentation returns, so these
 * tests pin the labels, the visibility rule and the "not broker / statutory
 * charges" wording without needing an Android runtime.
 */
class BacktestCostPresentationTest {

    private fun result(
        model: ResearchCostModel,
        researchCosts: Double = 0.0,
        costAdjustedNet: Double = 100.0,
        costAdjustedReturn: Double = 0.1
    ) = BacktestResult(
        strategyName = "t",
        config = BacktestConfig(researchCostModel = model),
        finalEquity = 100_100.0,
        trades = emptyList(),
        equityCurve = emptyList(),
        metrics = PerformanceMetrics(
            totalTrades = 1, winningTrades = 1, losingTrades = 0, winRate = 1.0,
            grossProfit = 100.0, grossLoss = 0.0, netProfit = 100.0, totalReturnPercent = 0.1,
            researchCosts = researchCosts,
            costAdjustedNetProfit = costAdjustedNet,
            costAdjustedReturnPercent = costAdjustedReturn,
            maxDrawdown = 0.0, maxDrawdownPercent = 0.0, averageTradePnl = 100.0,
            profitFactor = null, averageWinningTrade = 100.0, averageLosingTrade = null
        )
    )

    private val costed = ResearchCostModel(
        commissionRatePercent = 0.03,
        slippageBps = 2.5,
        fixedCostPerTrade = 20.0
    )

    // ---- Labelling ----

    @Test
    fun grossHeadlineIsLabelledPnlBeforeCosts() {
        assertEquals("P&L Before Costs", BacktestCostPresentation.PNL_BEFORE_COSTS)
        // The gross figure must never again be called "Net".
        assertFalse(BacktestCostPresentation.PNL_BEFORE_COSTS.contains("Net", ignoreCase = true))
    }

    @Test
    fun rankingCaptionSaysResultsAreRankedBeforeCosts() {
        assertEquals("Ranked by P&L before costs.", BacktestCostPresentation.RANKING_CAPTION)
    }

    // ---- Visibility ----

    @Test
    fun zeroCostModelShowsNoCostCard() {
        assertFalse(BacktestCostPresentation.isVisible(ResearchCostModel()))
        assertNull(BacktestCostPresentation.card(result(ResearchCostModel()), isFutures = false))
        assertNull(BacktestCostPresentation.card(result(ResearchCostModel()), isFutures = true))
    }

    @Test
    fun anyNonZeroComponentShowsTheCostCard() {
        listOf(
            ResearchCostModel(commissionRatePercent = 0.01),
            ResearchCostModel(slippageBps = 0.5),
            ResearchCostModel(fixedCostPerTrade = 1.0)
        ).forEach { model ->
            assertTrue(BacktestCostPresentation.isVisible(model))
            assertNotNull(BacktestCostPresentation.card(result(model), isFutures = false))
        }
    }

    // ---- Card content ----

    @Test
    fun cardShowsAssumptionsWithUnitsAndCostAdjustedFigures() {
        val card = BacktestCostPresentation.card(
            result(costed, researchCosts = 23.25, costAdjustedNet = 46.75, costAdjustedReturn = 0.5),
            isFutures = false
        )!!

        assertEquals("Assumed research costs", card.title)
        val rows = card.rows.associate { it.label to it.value }
        assertEquals("0.03% of entry + exit notional", rows["Commission"])
        assertEquals("2.5 bps of entry + exit notional", rows["Slippage"])
        assertEquals("\u20b920 per round trip", rows["Fixed cost"])
        assertEquals("\u20b923.25", rows["Assumed costs"])
        assertEquals("+\u20b946.75", rows["Cost-adjusted P&L"])
        assertEquals("+0.50%", rows["Cost-adjusted return"])
    }

    @Test
    fun costAdjustedRowsAreNeverLabelledNetOrNetOfCharges() {
        val card = BacktestCostPresentation.card(result(costed, 23.25, 46.75, 0.5), isFutures = true)!!
        card.rows.forEach { row ->
            assertFalse("'${row.label}'", row.label.contains("Net", ignoreCase = true))
            assertFalse("'${row.label}'", row.label.contains("charges", ignoreCase = true))
        }
    }

    // ---- Caption: generic assumption, not broker / statutory charges ----

    @Test
    fun captionIdentifiesGenericAssumptionsNotBrokerOrStatutoryCharges() {
        val caption = BacktestCostPresentation.card(result(costed), isFutures = false)!!.caption
        assertTrue(caption.contains("Generic assumed research friction"))
        assertTrue(caption.contains("not broker charges"))
        assertTrue(caption.contains("not statutory F&O charges"))
    }

    @Test
    fun futuresCaptionAlsoStatesStatutoryChargesStayNotModelled() {
        val futures = BacktestCostPresentation.card(result(costed), isFutures = true)!!.caption
        val index = BacktestCostPresentation.card(result(costed), isFutures = false)!!.caption

        assertTrue(futures.contains("NOT MODELLED"))
        assertTrue(futures.contains("break-even"))
        assertFalse(index.contains("NOT MODELLED"))
        // The futures caption extends the generic one; it never replaces it.
        assertTrue(futures.startsWith(index))
        assertEquals(futures, BacktestCostPresentation.assumptionCaption(isFutures = true))
        assertEquals(index, BacktestCostPresentation.assumptionCaption(isFutures = false))
    }

    @Test
    fun setupCaptionStatesAssumptionsAreNotBrokerOrStatutoryChargesAndDefaultIsUnchanged() {
        val caption = BacktestCostPresentation.SETUP_CAPTION
        assertTrue(caption.contains("not your broker's charges"))
        assertTrue(caption.contains("not statutory F&O charges"))
        assertTrue(caption.contains("default"))
    }

    // ---- The futures accounting block stays NOT_MODELLED (unchanged by P3P11) ----

    @Test
    fun futuresStatutoryFieldsRemainNotModelledByDefault() {
        val block = FuturesBacktestAccounting.compute(contract = null, trades = emptyList())
        assertEquals(FuturesBacktestAccounting.NOT_MODELLED, block.charges)
        assertEquals(FuturesBacktestAccounting.NOT_MODELLED, block.netPnl)
        assertEquals(FuturesBacktestAccounting.NOT_MODELLED, block.breakEven)
        assertEquals(FuturesBacktestAccounting.NOT_MODELLED, block.leverage)
    }
}
