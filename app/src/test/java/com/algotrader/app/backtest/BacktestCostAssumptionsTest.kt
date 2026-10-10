package com.algotrader.app.backtest

import com.algotrader.app.backtest.BacktestCostAssumptions.Field
import com.algotrader.app.backtest.BacktestCostAssumptions.Outcome
import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.PerformanceMetrics
import com.algotrader.backtest.ResearchCostModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestCostAssumptionsTest {

    private fun valid(c: String, s: String, f: String): ResearchCostModel =
        (BacktestCostAssumptions.parse(c, s, f) as Outcome.Valid).model

    private fun invalid(c: String, s: String, f: String): Outcome.Invalid =
        BacktestCostAssumptions.parse(c, s, f) as Outcome.Invalid

    @Test
    fun blankFieldsMeanZeroCost() {
        val model = valid("", "  ", "")
        assertEquals(ResearchCostModel(), model)
        assertTrue(BacktestCostAssumptions.isZero(model))
    }

    @Test
    fun validValuesBuildTheExactModel() {
        val model = valid("0.03", "2.5", "20")
        assertEquals(0.03, model.commissionRatePercent, 0.0)
        assertEquals(2.5, model.slippageBps, 0.0)
        assertEquals(20.0, model.fixedCostPerTrade, 0.0)
        assertFalse(BacktestCostAssumptions.isZero(model))
    }

    @Test
    fun valuesAtTheUpperBoundsAreAccepted() {
        val model = valid("5", "500", "100000")
        assertEquals(BacktestCostAssumptions.MAX_COMMISSION_PERCENT, model.commissionRatePercent, 0.0)
        assertEquals(BacktestCostAssumptions.MAX_SLIPPAGE_BPS, model.slippageBps, 0.0)
        assertEquals(BacktestCostAssumptions.MAX_FIXED_COST_PER_TRADE, model.fixedCostPerTrade, 0.0)
    }

    @Test
    fun valuesJustAboveTheUpperBoundsAreRejectedWithTheRightField() {
        assertEquals(Field.COMMISSION_PERCENT, invalid("5.0001", "0", "0").field)
        assertEquals(Field.SLIPPAGE_BPS, invalid("0", "500.01", "0").field)
        assertEquals(Field.FIXED_COST_PER_TRADE, invalid("0", "0", "100000.01").field)
        assertTrue(invalid("5.0001", "0", "0").message.contains("cannot exceed"))
    }

    @Test
    fun negativeValuesAreRejected() {
        assertEquals(Field.COMMISSION_PERCENT, invalid("-0.01", "0", "0").field)
        assertEquals(Field.SLIPPAGE_BPS, invalid("0", "-1", "0").field)
        assertEquals(Field.FIXED_COST_PER_TRADE, invalid("0", "0", "-5").field)
        assertTrue(invalid("-0.01", "0", "0").message.contains("negative"))
    }

    @Test
    fun nonNumericAndNonFiniteTextIsRejected() {
        listOf("abc", "1e3", "NaN", "Infinity", "-Infinity", "0x10", "1,5", "1 2", "5f", ".", "+", "-")
            .forEach { text ->
                assertEquals(
                    "'$text' must be rejected for commission",
                    Field.COMMISSION_PERCENT,
                    invalid(text, "0", "0").field
                )
            }
    }

    @Test
    fun anOverflowingNumberIsRejectedNotAccepted() {
        // 400 digits parses to Infinity; it must not slip through as a value.
        val huge = "9".repeat(400)
        assertEquals(Field.FIXED_COST_PER_TRADE, invalid("0", "0", huge).field)
    }

    @Test
    fun firstProblemFieldIsReportedInDeclarationOrder() {
        assertEquals(Field.COMMISSION_PERCENT, invalid("-1", "-1", "-1").field)
        assertEquals(Field.SLIPPAGE_BPS, invalid("1", "-1", "-1").field)
    }

    @Test
    fun createRejectsNonFiniteDoubles() {
        assertTrue(BacktestCostAssumptions.create(Double.NaN, 0.0, 0.0) is Outcome.Invalid)
        assertTrue(BacktestCostAssumptions.create(0.0, Double.POSITIVE_INFINITY, 0.0) is Outcome.Invalid)
        assertTrue(BacktestCostAssumptions.create(0.0, 0.0, Double.NEGATIVE_INFINITY) is Outcome.Invalid)
    }

    @Test
    fun negativeZeroIsNormalisedSoEqualAssumptionsAreEqualModels() {
        val model = (BacktestCostAssumptions.create(-0.0, -0.0, -0.0) as Outcome.Valid).model
        assertEquals(ResearchCostModel(), model)
        assertTrue(BacktestCostAssumptions.isZero(model))
    }

    @Test
    fun validationErrorChecksAnAlreadyBuiltModel() {
        assertNull(BacktestCostAssumptions.validationError(ResearchCostModel()))
        assertNull(BacktestCostAssumptions.validationError(ResearchCostModel(0.03, 2.5, 20.0)))
        // ResearchCostModel itself accepts these; the app-level bounds do not.
        assertTrue(BacktestCostAssumptions.validationError(ResearchCostModel(commissionRatePercent = 50.0)) != null)
        assertTrue(
            BacktestCostAssumptions.validationError(
                ResearchCostModel(fixedCostPerTrade = Double.POSITIVE_INFINITY)
            ) != null
        )
    }

    @Test
    fun plainTextIsDeterministic() {
        assertEquals("0", BacktestCostAssumptions.plain(0.0))
        assertEquals("5", BacktestCostAssumptions.plain(5.0))
        assertEquals("0.03", BacktestCostAssumptions.plain(0.03))
        assertEquals("100000", BacktestCostAssumptions.plain(100_000.0))
        assertEquals("0.0001", BacktestCostAssumptions.plain(1.0E-4))
    }

    // ---- Checkpoint consistency (the rule the worker enforces on resume) ----

    private fun resultWith(model: ResearchCostModel) = BacktestResult(
        strategyName = "t",
        config = BacktestConfig(researchCostModel = model),
        finalEquity = 100_000.0,
        trades = emptyList(),
        equityCurve = emptyList(),
        metrics = PerformanceMetrics(
            totalTrades = 0, winningTrades = 0, losingTrades = 0, winRate = 0.0,
            grossProfit = 0.0, grossLoss = 0.0, netProfit = 0.0, totalReturnPercent = 0.0,
            maxDrawdown = 0.0, maxDrawdownPercent = 0.0, averageTradePnl = 0.0,
            profitFactor = null, averageWinningTrade = null, averageLosingTrade = null
        )
    )

    @Test
    fun checkpointIsConsistentOnlyWhenEveryResultUsedTheJobsModel() {
        val job = ResearchCostModel(0.03, 2.5, 20.0)
        assertTrue(BacktestCostAssumptions.checkpointConsistent(job, emptyList()))
        assertTrue(BacktestCostAssumptions.checkpointConsistent(job, listOf(resultWith(job), resultWith(job))))
        assertFalse(
            BacktestCostAssumptions.checkpointConsistent(job, listOf(resultWith(job), resultWith(ResearchCostModel())))
        )
        assertFalse(
            BacktestCostAssumptions.checkpointConsistent(
                job,
                listOf(resultWith(ResearchCostModel(0.03, 2.5, 21.0)))
            )
        )
    }

    @Test
    fun legacyZeroCostCheckpointResumesUnderZeroCostJob() {
        assertTrue(
            BacktestCostAssumptions.checkpointConsistent(
                ResearchCostModel(),
                listOf(resultWith(ResearchCostModel()))
            )
        )
    }
}
