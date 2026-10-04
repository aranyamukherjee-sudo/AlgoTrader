package com.algotrader.domain.fno

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FNO-2D fixture integration tests.
 *
 * These tests connect the verified fixture evidence to the existing
 * FuturesCalculator without promoting fixture observations into broker rules.
 *
 * No broker charge/rate/formula is inferred here.
 */
class FnoFixtureIntegrationTest {

    private fun bd(value: String) = BigDecimal(value)

    private fun oneLotNiftyFixture(): FuturesInputs =
        FuturesInputs(
            broker = Input.Known("DHAN", Evidence.SUPPLIED),
            exchange = Input.Known("NSE", Evidence.VERIFIED),
            segment = Input.Known("FUTIDX", Evidence.VERIFIED),
            underlying = Input.Known("NIFTY", Evidence.VERIFIED),
            contractMonth = Input.Unknown,
            contractId = Input.Unknown,
            expiry = Input.Unknown,
            lotSize = Input.Known(65L, Evidence.VERIFIED),
            lots = Input.Known(bd("1"), Evidence.VERIFIED),
            entryPrice = Input.Unknown,
            exitPrice = Input.Unknown,
            markPrice = Input.Unknown,
            direction = Input.Unknown,
            orderType = Input.Known(OrderType.MARKET, Evidence.VERIFIED),
            product = Input.Unknown,
            margin = Input.Unknown,
            chargeSchedule = Input.Unknown,
            estimateDate = Input.Unknown,
            referencePriceType = Input.Unknown,
            valuationPrice = Input.Known(
                ValuationPrice(
                    price = bd("22520"),
                    basis = ValuationBasis.REFERENCE_PRICE,
                ),
                Evidence.VERIFIED,
            ),
            displayedTurnover = Input.Known(
                DisplayedTurnover(
                    amount = bd("1463800"),
                    leg = Input.Unknown,
                ),
                Evidence.VERIFIED,
            ),
        )

    @Test
    fun `fixture arithmetic derives quantity 65 from one lot`() {
        val calculation = FuturesCalculator.calculate(oneLotNiftyFixture())

        assertEquals(65L, calculation.quantity.value)
        assertEquals(ResultKind.DERIVED, calculation.quantity.kind)
        assertEquals(
            setOf(InputId.LOTS, InputId.LOT_SIZE),
            calculation.quantity.dependsOn,
        )
    }

    @Test
    fun `verified displayed price produces arithmetic notional 1463800`() {
        val calculation = FuturesCalculator.calculate(oneLotNiftyFixture())

        assertEquals(
            0,
            bd("1463800").compareTo(calculation.contractNotional.value),
        )
        assertEquals(ResultKind.DERIVED, calculation.contractNotional.kind)
        assertTrue(
            FnoLabels.PRICE_TYPE_UNKNOWN in calculation.contractNotional.labels,
        )
    }

    @Test
    fun `displayed turnover can pass arithmetic comparison without establishing its leg`() {
        val calculation = FuturesCalculator.calculate(oneLotNiftyFixture())

        val record = FnoFixtureComparison.compare(
            computedOutput = "contract_notional",
            actual = calculation.contractNotional,
            expected = FixtureField(
                name = "displayed_exchange_turnover",
                captured = FixtureValue.verified(bd("1463800")),
            ),
            tolerance = BigDecimal.ZERO,
            inputIds = setOf(
                InputId.LOT_SIZE,
                InputId.LOTS,
                InputId.VALUATION_PRICE,
            ),
        )

        assertEquals(ComparisonState.PASS, record.state)
        assertEquals(bd("1463800"), record.expectedValue)
        assertEquals(bd("1463800"), record.computedValue)
        assertEquals("displayed_exchange_turnover", record.fixtureField)

        // This PASS is arithmetic only. The calculator must still refuse
        // to identify the displayed turnover as a particular trading leg.
        assertEquals(
            ComparisonState.NOT_COMPARABLE,
            calculation.turnoverComparison.state,
        )
        assertNull(calculation.turnoverComparison.comparedLeg)
    }

    @Test
    fun `unknown turnover leg remains not comparable even when amount matches`() {
        val calculation = FuturesCalculator.calculate(oneLotNiftyFixture())

        assertEquals(
            ComparisonState.NOT_COMPARABLE,
            calculation.turnoverComparison.state,
        )
        assertEquals(
            bd("1463800"),
            calculation.turnoverComparison.displayedTurnover,
        )
        assertNull(calculation.turnoverComparison.comparedLeg)
        assertNull(calculation.turnoverComparison.computedLegValue)
    }

    @Test
    fun `supplied earlier leverage fixture remains supplied observation`() {
        val fixture = FixtureValue.suppliedEarlier(
            bd("8.83"),
            note = "effective leverage displayed in earlier fixture",
        )

        val input = fixture.toInput()

        assertEquals(FixtureStatus.SUPPLIED_EARLIER, fixture.status)
        assertEquals(Evidence.SUPPLIED, (input as Input.Known).evidence)
        assertEquals(bd("8.83"), (input as Input.Known).value)
    }

    @Test
    fun `missing margin does not reverse engineer leverage from fixture arithmetic`() {
        val calculation = FuturesCalculator.calculate(oneLotNiftyFixture())

        assertEquals(ResultKind.NOT_MODELLED, calculation.marginRequirement.kind)
        assertNull(calculation.marginRequirement.value)

        assertEquals(ResultKind.NOT_MODELLED, calculation.effectiveLeverage.kind)
        assertNull(calculation.effectiveLeverage.value)
    }

    @Test
    fun `unknown contract fields remain unavailable rather than being inferred`() {
        val calculation = FuturesCalculator.calculate(oneLotNiftyFixture())

        assertEquals(ResultKind.NOT_MODELLED, calculation.exitValue.kind)
        assertNull(calculation.exitValue.value)

        assertEquals(ResultKind.NOT_MODELLED, calculation.grossPnl.kind)
        assertNull(calculation.grossPnl.value)
    }
}
