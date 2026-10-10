package com.algotrader.domain.fno

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for the FNO-1 contract (docs/phase3/FNO_CAPITAL_AND_COSTS_DESIGN.md §8).
 *
 * Figures such as 22,520 x 65 and the margin amount are used here ONLY as
 * arithmetic / pass-through data, never as expected broker behaviour. The charge
 * schedule used below is SYNTHETIC: its rates are made up for the tests and are
 * not any broker's or regulator's.
 */
class FuturesCalculatorTest {

    private fun bd(s: String) = BigDecimal(s)
    private fun known(s: String) = Input.Known(bd(s))
    private val day = LocalDate.of(2026, 10, 1)

    private fun identity(base: FuturesInputs = FuturesInputs()) = base.copy(
        broker = Input.Known("TESTBROKER"),
        exchange = Input.Known("NSE"),
        segment = Input.Known("FUTIDX"),
        underlying = Input.Known("NIFTY"),
        contractMonth = Input.Known("OCT"),
        contractId = Input.Known("TEST-CONTRACT-ID"),
    )

    private fun sized(lots: String = "1", lotSize: Long = 65) =
        FuturesInputs(lots = known(lots), lotSize = Input.Known(lotSize))

    private fun assertNotModelled(r: FnoResult<*>, vararg missing: InputId) {
        assertNull(r.value, "value must be null")
        assertEquals(ResultKind.NOT_MODELLED, r.kind)
        assertEquals(missing.toSet(), r.missingInputs)
    }

    private fun assertInvalid(r: FnoResult<*>, vararg invalid: InputId) {
        assertNull(r.value, "value must be null")
        assertEquals(ResultKind.INVALID_INPUT, r.kind)
        assertEquals(invalid.toSet(), r.invalidInputs)
    }

    private fun assertValue(expected: String, r: FnoResult<BigDecimal>) {
        val v = r.value ?: fail("expected value $expected but result is ${r.kind} (missing=${r.missingInputs})")
        assertEquals(0, bd(expected).compareTo(v), "expected $expected but was $v")
    }

    // ---- synthetic charge schedule (rates are invented for these tests) --------
    private val syntheticSource = ScheduleSource(
        ScheduleSourceKind.BROKER_PUBLISHED_SCHEDULE,
        "SYNTHETIC TEST SCHEDULE - not a real broker schedule",
    )

    private fun comp(
        name: String,
        sides: Set<Side>,
        basis: ChargeBasis,
        rounding: Rounding = Rounding.None,
        min: Bound = Bound.None,
        cap: Bound = Bound.None,
    ) = ChargeComponentSpec(name, sides, basis, rounding, min, cap)

    private val both = setOf(Side.BUY, Side.SELL)

    private fun syntheticDefinition(
        components: List<ChargeComponentSpec>? = null,
        effectiveTo: LocalDate? = null,
    ) = ChargeScheduleDefinition(
        broker = "TestBroker",
        exchange = "NSE",
        segment = "FUTIDX",
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = effectiveTo,
        source = syntheticSource,
        components = components ?: listOf(
            comp("flat", both, ChargeBasis.FlatPerLeg(bd("20"))),
            comp("pct", both, ChargeBasis.RateOfLegValue(bd("0.0001"))),
            comp("onFlat", both, ChargeBasis.RateOfComponent("flat", bd("0.18"))),
            comp("sellOnly", setOf(Side.SELL), ChargeBasis.RateOfLegValue(bd("0.0002"))),
        ),
        keyedByOrderType = false,
        keyedByProduct = false,
    )

    private fun syntheticSchedule(
        components: List<ChargeComponentSpec>? = null,
        effectiveTo: LocalDate? = null,
    ): ValidatedChargeSchedule =
        when (val v = ValidatedChargeSchedule.validate(syntheticDefinition(components, effectiveTo))) {
            is ScheduleValidation.Valid -> v.schedule
            is ScheduleValidation.Rejected -> fail("synthetic schedule rejected: ${v.errors}")
        }

    private fun priced(
        direction: Direction? = Direction.LONG,
        entry: String = "22520",
        exit: String? = "22600",
        schedule: ValidatedChargeSchedule? = null,
    ) = identity(sized()).copy(
        direction = direction?.let { Input.Known(it) } ?: Input.Unknown,
        entryPrice = known(entry),
        exitPrice = exit?.let { known(it) } ?: Input.Unknown,
        estimateDate = Input.Known(day),
        chargeSchedule = schedule?.let { Input.Known(it) } ?: Input.Unknown,
    )

    // ---- 1. lots x lot size ---------------------------------------------------

    @Test
    fun `1 quantity is lots times lot size`() {
        val c = FuturesCalculator.calculate(sized(lots = "3", lotSize = 50))
        assertEquals(150L, c.quantity.value)
        assertEquals(3L, c.lots.value)
        assertEquals(ResultKind.DERIVED, c.quantity.kind)
        assertEquals(setOf(InputId.LOTS, InputId.LOT_SIZE), c.quantity.dependsOn)
        assertEquals(Evidence.SUPPLIED, c.quantity.evidence)
    }

    // ---- 2. 1 x 65 = 65 -----------------------------------------------------------

    @Test
    fun `2 one lot of 65 is quantity 65`() {
        assertEquals(65L, FuturesCalculator.calculate(sized("1", 65)).quantity.value)
    }

    @Test
    fun `evidence follows the weakest input and is never silently upgraded`() {
        val verifiedLotSize = FuturesInputs(lots = known("2"), lotSize = Input.Known(65, Evidence.VERIFIED))
        assertEquals(Evidence.SUPPLIED, FuturesCalculator.calculate(verifiedLotSize).quantity.evidence)
        val allVerified = FuturesInputs(lots = Input.Known(bd("2"), Evidence.VERIFIED), lotSize = Input.Known(65, Evidence.VERIFIED))
        assertEquals(Evidence.VERIFIED, FuturesCalculator.calculate(allVerified).quantity.evidence)
    }

    // ---- 3. 22,520 x 65 = 14,63,800 as arithmetic only ----------------------------------

    @Test
    fun `3 notional 22520 x 65 is 1463800 as arithmetic only`() {
        val c = FuturesCalculator.calculate(
            sized().copy(valuationPrice = Input.Known(ValuationPrice(bd("22520"), ValuationBasis.REFERENCE_PRICE))),
        )
        assertValue("1463800", c.contractNotional)
        assertEquals(ResultKind.DERIVED, c.contractNotional.kind)
        // Notional is not an entry value, exit value, margin or capital:
        assertNotModelled(c.entryValue, InputId.ENTRY_PRICE)
        assertNotModelled(c.exitValue, InputId.EXIT_PRICE)
        assertNotModelled(c.marginRequirement, InputId.MARGIN)
        // Reference-price type (I20) is unknown, so the result says so (§8.2a).
        assertTrue(FnoLabels.PRICE_TYPE_UNKNOWN in c.contractNotional.labels)
    }

    // ---- 4. missing lot size ---------------------------------------------------------

    @Test
    fun `4 missing lot size is null and never defaults to 1`() {
        val base = FuturesInputs(
            lots = known("1"),
            entryPrice = known("100"),
            exitPrice = known("110"),
            direction = Input.Known(Direction.LONG),
            valuationPrice = Input.Known(ValuationPrice(bd("100"), ValuationBasis.ENTRY_PRICE)),
        )
        val c = FuturesCalculator.calculate(base)
        assertNotModelled(c.quantity, InputId.LOT_SIZE)
        assertNotModelled(c.entryValue, InputId.LOT_SIZE)
        assertNotModelled(c.exitValue, InputId.LOT_SIZE)
        assertNotModelled(c.contractNotional, InputId.LOT_SIZE)
        assertNotModelled(c.grossPnl, InputId.LOT_SIZE)
        assertNotModelled(c.netPnl, InputId.LOT_SIZE, InputId.BROKERAGE, InputId.STATUTORY_RATES, InputId.BROKER_RULES, InputId.BROKER, InputId.EXCHANGE, InputId.SEGMENT, InputId.UNDERLYING, InputId.CONTRACT_MONTH, InputId.CONTRACT_ID, InputId.ESTIMATE_DATE)
        // The lots themselves are still fine.
        assertEquals(1L, c.lots.value)
    }

    // ---- 5. fractional lots ----------------------------------------------------------------

    @Test
    fun `5 fractional lots are INVALID_INPUT and never rounded`() {
        for (lots in listOf("1.5", "0.5", "2.0001")) {
            val c = FuturesCalculator.calculate(sized(lots))
            assertInvalid(c.lots, InputId.LOTS)
            assertInvalid(c.quantity, InputId.LOTS)
        }
        // A whole number written with a trailing fraction of zeros is still whole.
        assertEquals(130L, FuturesCalculator.calculate(sized("2.0")).quantity.value)
    }

    // ---- 6. zero lots ------------------------------------------------------------------------

    @Test
    fun `6 zero and negative lots are INVALID_INPUT`() {
        for (lots in listOf("0", "0.0", "-1")) {
            val c = FuturesCalculator.calculate(sized(lots))
            assertInvalid(c.quantity, InputId.LOTS)
            assertFalse(c.quantity.isAvailable)
        }
    }

    @Test
    fun `missing lots is null, not invalid`() {
        val c = FuturesCalculator.calculate(FuturesInputs(lotSize = Input.Known(65)))
        assertNotModelled(c.quantity, InputId.LOTS)
    }

    // ---- 7. missing valuation price ------------------------------------------------------------------

    @Test
    fun `7 missing valuation price nulls only the notional`() {
        val c = FuturesCalculator.calculate(priced(exit = null))
        assertNotModelled(c.contractNotional, InputId.VALUATION_PRICE)
        // Entry value does not need the valuation price, and is not substituted for it.
        assertValue("1463800", c.entryValue)
        assertEquals(65L, c.quantity.value)
    }

    // ---- 8. supplied margin -----------------------------------------------------------------------------

    private fun marginInputs(quote: MarginQuote?) = identity(sized()).copy(
        expiry = Input.Known(LocalDate.of(2026, 10, 27)),
        product = Input.Known(ProductType.NRML),
        direction = Input.Known(Direction.LONG),
        estimateDate = Input.Known(day),
        valuationPrice = Input.Known(ValuationPrice(bd("22520"), ValuationBasis.REFERENCE_PRICE)),
        margin = quote?.let { Input.Known(it) } ?: Input.Unknown,
    )

    @Test
    fun `8 supplied margin passes through unchanged and is not computed`() {
        val c = FuturesCalculator.calculate(marginInputs(MarginQuote(bd("165842.15"))))
        assertValue("165842.15", c.marginRequirement)
        assertEquals(ResultKind.SUPPLIED, c.marginRequirement.kind)
        assertTrue(FnoLabels.BROKER_ESTIMATE in c.marginRequirement.labels)
        assertValue("165842.15", c.capitalUsed)
        // Scenario elements that were not supplied are reported as UNKNOWN.
        assertTrue(c.marginRequirement.warnings.any { "scenario product is UNKNOWN" in it })
        // Margin is independent of notional: a different notional leaves it untouched.
        val other = FuturesCalculator.calculate(
            marginInputs(MarginQuote(bd("165842.15"))).copy(
                valuationPrice = Input.Known(ValuationPrice(bd("30000"), ValuationBasis.REFERENCE_PRICE)),
            ),
        )
        assertValue("165842.15", other.marginRequirement)
    }

    @Test
    fun `non-positive margin is INVALID_INPUT`() {
        assertInvalid(FuturesCalculator.calculate(marginInputs(MarginQuote(BigDecimal.ZERO))).marginRequirement, InputId.MARGIN)
    }

    // ---- 9. missing margin ----------------------------------------------------------------------------------

    @Test
    fun `9 missing margin nulls margin, capital used and leverage`() {
        val c = FuturesCalculator.calculate(marginInputs(null))
        assertNotModelled(c.marginRequirement, InputId.MARGIN)
        assertNotModelled(c.capitalUsed, InputId.MARGIN)
        assertNotModelled(c.effectiveLeverage, InputId.MARGIN)
        assertValue("1463800", c.contractNotional) // notional is unaffected
    }

    // ---- 10. missing charge schedule --------------------------------------------------------------------------

    @Test
    fun `10 without a schedule charges, net P&L and break-even are unavailable`() {
        val c = FuturesCalculator.calculate(priced(schedule = null))
        val schedule = arrayOf(InputId.BROKERAGE, InputId.STATUTORY_RATES, InputId.BROKER_RULES)
        assertNotModelled(c.roundTripCharges, *schedule)
        assertNotModelled(c.netPnl, *schedule)
        assertNotModelled(c.breakEvenLong, *schedule)
        assertNotModelled(c.breakEvenShort, *schedule)
        assertNull(c.openingLegCharges.value)
        assertNull(c.closingLegCharges.value)
        // Gross P&L does not need charges and is still exact.
        assertValue("5200", c.grossPnl)
    }

    // ---- 11. invalid ZERO sell-screenshot data -------------------------------------------------------------------

    @Test
    fun `11 an INVALID capture of zero is never used as an input`() {
        val invalidZero = CapturedValue(BigDecimal.ZERO, CaptureStatus.INVALID, "sell estimate, 'Poor Internet connection!'")
        val asExit = invalidZero.toInput()
        assertTrue(asExit is Input.Invalid)
        val c = FuturesCalculator.calculate(priced().copy(exitPrice = asExit))
        assertInvalid(c.exitValue, InputId.EXIT_PRICE)
        assertInvalid(c.grossPnl, InputId.EXIT_PRICE)
        assertNotEquals(ResultKind.DERIVED, c.grossPnl.kind) // not computed as if the exit were 0
        // An invalid exit is not replaced by a mark price either.
        val withMark = FuturesCalculator.calculate(priced().copy(exitPrice = asExit, markPrice = known("22600")))
        assertInvalid(withMark.grossPnl, InputId.EXIT_PRICE)
        // A VALID capture keeps its value; "zero" is not "missing".
        val validZero = CapturedValue(BigDecimal.ZERO, CaptureStatus.VALID).toInput()
        assertEquals(Input.Known(BigDecimal.ZERO, Evidence.SUPPLIED), validZero)
    }

    @Test
    fun `11b an INVALID capture cannot be an expected turnover`() {
        val t = CapturedValue(DisplayedTurnover(BigDecimal.ZERO, Input.Known(TurnoverLeg.SELL_LEG)), CaptureStatus.INVALID)
        val c = FuturesCalculator.calculate(priced().copy(displayedTurnover = t.toInput()))
        assertEquals(ComparisonState.NOT_COMPARABLE, c.turnoverComparison.state)
    }

    // ---- 12. turnover is not automatically assigned to a leg ---------------------------------------------------------

    @Test
    fun `12 a displayed turnover is not assigned to a leg`() {
        val turnover = DisplayedTurnover(bd("1463800")) // leg association UNKNOWN
        val c = FuturesCalculator.calculate(priced(exit = null).copy(displayedTurnover = Input.Known(turnover)))
        // Numerically equal to the entry value, but still not comparable: no inference from equality.
        assertValue("1463800", c.entryValue)
        assertEquals(ComparisonState.NOT_COMPARABLE, c.turnoverComparison.state)
        assertNull(c.turnoverComparison.comparedLeg)
        assertNull(c.turnoverComparison.computedLegValue)
        assertTrue(c.turnoverComparison.reasons.any { "UNKNOWN" in it })
    }

    @Test
    fun `12b once the leg is established the comparison is made against that leg only`() {
        fun cmp(direction: Direction, leg: TurnoverLeg, exit: String?, tolerance: BigDecimal = BigDecimal.ZERO) =
            FuturesCalculator.calculate(
                priced(direction = direction, exit = exit).copy(
                    displayedTurnover = Input.Known(DisplayedTurnover(bd("1463800"), Input.Known(leg))),
                ),
                tolerance,
            ).turnoverComparison

        // LONG opens with BUY, so the BUY leg is the entry leg: 22520 x 65 = 1463800 -> PASS.
        assertEquals(ComparisonState.PASS, cmp(Direction.LONG, TurnoverLeg.BUY_LEG, "22600").state)
        assertEquals(ComparisonState.PASS, cmp(Direction.LONG, TurnoverLeg.OPENING_LEG, null).state)
        // The SELL leg of a LONG is the exit leg (22600 x 65 = 1469000) -> FAIL, not PASS.
        assertEquals(ComparisonState.FAIL, cmp(Direction.LONG, TurnoverLeg.SELL_LEG, "22600").state)
        // ...and with no exit price it cannot be compared at all.
        assertEquals(ComparisonState.NOT_COMPARABLE, cmp(Direction.LONG, TurnoverLeg.SELL_LEG, null).state)
        // Sides swap for SHORT: the SELL leg is now the entry leg.
        assertEquals(ComparisonState.PASS, cmp(Direction.SHORT, TurnoverLeg.SELL_LEG, "22600").state)
        assertEquals(ComparisonState.FAIL, cmp(Direction.SHORT, TurnoverLeg.BUY_LEG, "22600").state)
        // Unknown direction: BUY/SELL cannot be mapped to opening/closing.
        val noDirection = FuturesCalculator.calculate(
            priced(direction = null).copy(displayedTurnover = Input.Known(DisplayedTurnover(bd("1463800"), Input.Known(TurnoverLeg.BUY_LEG)))),
        )
        assertEquals(ComparisonState.NOT_COMPARABLE, noDirection.turnoverComparison.state)
        // Explicit tolerance is honoured; default is exact equality.
        assertEquals(ComparisonState.FAIL, cmp(Direction.LONG, TurnoverLeg.SELL_LEG, "22600", bd("5000")).state)
        assertEquals(ComparisonState.PASS, cmp(Direction.LONG, TurnoverLeg.SELL_LEG, "22600", bd("5200")).state)
    }

    // ---- 13. unknown expiry / date ---------------------------------------------------------------------------------------

    @Test
    fun `13 unknown expiry still preserves the supplied margin quote`() {
        val inputs = marginInputs(MarginQuote(bd("100000"))).copy(expiry = Input.Unknown)
        val c = FuturesCalculator.calculate(inputs)
        // A supplied margin quote is not a lookup: it remains available, unchanged,
        // even when contract identity or expiry is incomplete (§8.2a, I15).
        assertValue("100000", c.marginRequirement)
        assertEquals(ResultKind.SUPPLIED, c.marginRequirement.kind)
        assertValue("100000", c.capitalUsed)
        // C2-C6 are unaffected by expiry (§8.2a).
        assertEquals(65L, c.quantity.value)
        assertValue("1463800", c.contractNotional)
        // Whether an unknown expiry blocks leverage is proved by test 14c, which
        // uses a fully matching margin scenario. This quote has an empty scenario.
    }

    @Test
    fun `13b unknown estimate date nulls date-keyed charges only`() {
        val c = FuturesCalculator.calculate(priced(schedule = syntheticSchedule()).copy(estimateDate = Input.Unknown))
        assertNotModelled(c.roundTripCharges, InputId.ESTIMATE_DATE)
        assertNotModelled(c.netPnl, InputId.ESTIMATE_DATE)
        assertValue("5200", c.grossPnl)
    }

    @Test
    fun `13c estimate date before the schedule is effective is not modelled`() {
        val c = FuturesCalculator.calculate(priced(schedule = syntheticSchedule()).copy(estimateDate = Input.Known(LocalDate.of(2025, 12, 31))))
        assertEquals(ResultKind.NOT_MODELLED, c.roundTripCharges.kind)
        assertTrue(c.roundTripCharges.warnings.any { "before the schedule's effective-from" in it })
    }

    @Test
    fun `13c1 schedule remains valid through its inclusive effective-to date`() {
        val end = LocalDate.of(2026, 1, 31)
        val schedule = syntheticSchedule(effectiveTo = end)

        val valid = FuturesCalculator.calculate(
            priced(schedule = schedule).copy(estimateDate = Input.Known(end)),
        )
        assertTrue(valid.roundTripCharges.isAvailable)

        val expired = FuturesCalculator.calculate(
            priced(schedule = schedule).copy(
                estimateDate = Input.Known(end.plusDays(1)),
            ),
        )
        assertEquals(ResultKind.NOT_MODELLED, expired.roundTripCharges.kind)
        assertEquals(ResultKind.NOT_MODELLED, expired.netPnl.kind)
        assertTrue(
            expired.roundTripCharges.warnings.any {
                "after the schedule's effective-to $end" in it
            },
        )
        assertValue("5200", expired.grossPnl)
    }

    @Test
    fun `13c2 schedule without effective-to remains open-ended`() {
        val c = FuturesCalculator.calculate(
            priced(schedule = syntheticSchedule()).copy(
                estimateDate = Input.Known(LocalDate.of(2030, 1, 1)),
            ),
        )
        assertTrue(c.roundTripCharges.isAvailable)
    }

    @Test
    fun `13d identity gaps null the schedule lookup but not arithmetic`() {
        val c = FuturesCalculator.calculate(priced(schedule = syntheticSchedule()).copy(contractId = Input.Unknown))
        assertNotModelled(c.netPnl, InputId.CONTRACT_ID)
        assertValue("5200", c.grossPnl)
    }

    // ---- 14. 8.83x is not promoted into calculated leverage -----------------------------------------------------------------------

    @Test
    fun `14 leverage is null when the margin scenario is unknown and 8_83 is never produced`() {
        val c = FuturesCalculator.calculate(marginInputs(MarginQuote(bd("165842.15"))))
        assertNull(c.effectiveLeverage.value)
        assertEquals(ResultKind.NOT_MODELLED, c.effectiveLeverage.kind)
        assertTrue(c.effectiveLeverage.warnings.any { "UNKNOWN" in it })
        // Even though notional / margin is numerically ~8.83, no output carries it.
        val ratio = c.contractNotional.value!!.divide(c.marginRequirement.value!!, 2, RoundingMode.HALF_UP)
        assertEquals(0, bd("8.83").compareTo(ratio), "(arithmetic observation only)")
        val outputs = listOf(
            c.entryValue, c.exitValue, c.contractNotional, c.grossPnl, c.marginRequirement,
            c.capitalUsed, c.effectiveLeverage, c.roundTripCharges, c.netPnl, c.breakEvenLong, c.breakEvenShort,
        )
        assertTrue(outputs.none { it.value != null && it.value!!.setScale(2, RoundingMode.HALF_UP).compareTo(bd("8.83")) == 0 })
    }

    // A margin scenario that matches marginInputs(): 65 units, BUY (LONG opens with BUY), NRML, `day`, REFERENCE_PRICE.
    private fun scenario(
        qty: Long = 65,
        side: Side = Side.BUY,
        product: ProductType = ProductType.NRML,
        date: LocalDate = day,
        basis: ValuationBasis = ValuationBasis.REFERENCE_PRICE,
    ) = MarginScenario(Input.Known(qty), Input.Known(side), Input.Known(product), Input.Known(date), Input.Known(basis))

    @Test
    fun `14b leverage needs a fully supplied matching scenario`() {
        // Synthetic numbers: notional 1,463,800 / margin 146,380 = 10.
        val ok = FuturesCalculator.calculate(marginInputs(MarginQuote(bd("146380"), scenario())))
        assertValue("10", ok.effectiveLeverage)
        assertEquals(ResultKind.DERIVED, ok.effectiveLeverage.kind)

        for ((label, sc) in listOf(
            "quantity" to scenario(qty = 130),
            "side" to scenario(side = Side.SELL),
            "product" to scenario(product = ProductType.MIS),
            "date" to scenario(date = day.plusDays(1)),
            "price basis" to scenario(basis = ValuationBasis.ENTRY_PRICE),
        )) {
            val c = FuturesCalculator.calculate(marginInputs(MarginQuote(bd("146380"), sc)))
            assertNull(c.effectiveLeverage.value, "mismatched $label must not give leverage")
            assertEquals(ResultKind.NOT_MODELLED, c.effectiveLeverage.kind)
            assertTrue(c.effectiveLeverage.warnings.any { "does not match" in it && label in it }, "warning for $label")
            // The supplied margin itself is still passed through.
            assertValue("146380", c.marginRequirement)
        }
        // A partially supplied scenario is not enough.
        val partial = MarginScenario(quantity = Input.Known(65), side = Input.Known(Side.BUY))
        assertNull(FuturesCalculator.calculate(marginInputs(MarginQuote(bd("146380"), partial))).effectiveLeverage.value)
    }

    // ---- C9 precondition: contract identity (I1-I5, I7) and expiry (I6), §8.2a / §8.3 ------------------------------
    // 14b above is the fully valid regression: identity and expiry known, scenario matching -> DERIVED leverage 10.

    /** Identity, expiry and a fully matching margin scenario are all supplied; synthetic margin 146,380 gives 10x. */
    private fun matchingLeverageInputs() = marginInputs(MarginQuote(bd("146380"), scenario())).copy(
        entryPrice = known("22520"),
        exitPrice = known("22600"),
    )

    /** What an unknown or blank identity/expiry must NOT change: supplied margin, capital used, quantity, values and P&L. */
    private fun assertMarginAndArithmeticUnchanged(control: FuturesCalculation, c: FuturesCalculation) {
        assertValue("146380", c.marginRequirement)
        assertEquals(ResultKind.SUPPLIED, c.marginRequirement.kind)
        assertEquals(control.marginRequirement, c.marginRequirement)
        assertEquals(control.capitalUsed, c.capitalUsed)
        assertEquals(control.quantity, c.quantity)
        assertEquals(control.entryValue, c.entryValue)
        assertEquals(control.contractNotional, c.contractNotional)
        assertEquals(control.grossPnl, c.grossPnl)
        assertValue("5200", c.grossPnl) // (22600 - 22520) x 65, still exact
    }

    @Test
    fun `14c unknown expiry prevents leverage while preserving the supplied margin`() {
        val control = FuturesCalculator.calculate(matchingLeverageInputs())
        assertValue("10", control.effectiveLeverage) // expiry known: leverage is produced

        val c = FuturesCalculator.calculate(matchingLeverageInputs().copy(expiry = Input.Unknown))
        assertNotModelled(c.effectiveLeverage, InputId.EXPIRY)
        assertTrue(InputId.MARGIN in c.effectiveLeverage.dependsOn)
        assertMarginAndArithmeticUnchanged(control, c)
    }

    @Test
    fun `14d each unknown identity field individually prevents leverage and is the only missing input`() {
        val base = matchingLeverageInputs()
        val control = FuturesCalculator.calculate(base)
        assertValue("10", control.effectiveLeverage)

        val cases = listOf(
            InputId.BROKER to base.copy(broker = Input.Unknown),
            InputId.EXCHANGE to base.copy(exchange = Input.Unknown),
            InputId.SEGMENT to base.copy(segment = Input.Unknown),
            InputId.UNDERLYING to base.copy(underlying = Input.Unknown),
            InputId.CONTRACT_MONTH to base.copy(contractMonth = Input.Unknown),
            InputId.EXPIRY to base.copy(expiry = Input.Unknown),
            InputId.CONTRACT_ID to base.copy(contractId = Input.Unknown),
        )
        for ((id, inputs) in cases) {
            val c = FuturesCalculator.calculate(inputs)
            assertNull(c.effectiveLeverage.value, "unknown $id must prevent leverage")
            assertEquals(ResultKind.NOT_MODELLED, c.effectiveLeverage.kind, "kind for unknown $id")
            assertEquals(setOf(id), c.effectiveLeverage.missingInputs, "missing inputs for unknown $id")
            assertMarginAndArithmeticUnchanged(control, c)
        }
    }

    @Test
    fun `14e several unknown identity fields report every missing input`() {
        val base = matchingLeverageInputs()
        val control = FuturesCalculator.calculate(base)

        val some = FuturesCalculator.calculate(
            base.copy(broker = Input.Unknown, underlying = Input.Unknown, contractId = Input.Unknown, expiry = Input.Unknown),
        )
        assertNotModelled(some.effectiveLeverage, InputId.BROKER, InputId.UNDERLYING, InputId.CONTRACT_ID, InputId.EXPIRY)
        assertMarginAndArithmeticUnchanged(control, some)

        val all = FuturesCalculator.calculate(
            base.copy(
                broker = Input.Unknown, exchange = Input.Unknown, segment = Input.Unknown, underlying = Input.Unknown,
                contractMonth = Input.Unknown, expiry = Input.Unknown, contractId = Input.Unknown,
            ),
        )
        assertNotModelled(
            all.effectiveLeverage,
            InputId.BROKER, InputId.EXCHANGE, InputId.SEGMENT, InputId.UNDERLYING,
            InputId.CONTRACT_MONTH, InputId.EXPIRY, InputId.CONTRACT_ID,
        )
        assertMarginAndArithmeticUnchanged(control, all)
    }

    @Test
    fun `14f a blank identity field is INVALID_INPUT for leverage and leaves the margin untouched`() {
        val base = matchingLeverageInputs()
        val control = FuturesCalculator.calculate(base)
        assertValue("10", control.effectiveLeverage)

        val cases = listOf(
            InputId.BROKER to base.copy(broker = Input.Known("")),
            InputId.EXCHANGE to base.copy(exchange = Input.Known("   ")),
            InputId.SEGMENT to base.copy(segment = Input.Known(" ")),
            InputId.UNDERLYING to base.copy(underlying = Input.Known("")),
            InputId.CONTRACT_MONTH to base.copy(contractMonth = Input.Known("\t")),
            InputId.CONTRACT_ID to base.copy(contractId = Input.Known("")),
        )
        for ((id, inputs) in cases) {
            val c = FuturesCalculator.calculate(inputs)
            assertNull(c.effectiveLeverage.value, "blank $id must not give leverage")
            assertEquals(ResultKind.INVALID_INPUT, c.effectiveLeverage.kind, "kind for blank $id")
            assertEquals(setOf(id), c.effectiveLeverage.invalidInputs, "invalid inputs for blank $id")
            assertMarginAndArithmeticUnchanged(control, c)
        }
    }

    // ---- 15. valid gross P&L ---------------------------------------------------------------------------------------------------

    @Test
    fun `15 gross P&L for LONG and SHORT`() {
        val long = FuturesCalculator.calculate(priced(Direction.LONG))
        assertValue("5200", long.grossPnl) // (22600 - 22520) x 65
        assertEquals(ResultKind.DERIVED, long.grossPnl.kind)
        assertValue("1463800", long.entryValue)
        assertValue("1469000", long.exitValue)
        assertEquals(Side.BUY, long.openingSide.value)
        assertEquals(Side.SELL, long.closingSide.value)

        val short = FuturesCalculator.calculate(priced(Direction.SHORT))
        assertValue("-5200", short.grossPnl) // (22520 - 22600) x 65
        assertEquals(Side.SELL, short.openingSide.value)
        assertEquals(Side.BUY, short.closingSide.value)

        val shortWin = FuturesCalculator.calculate(priced(Direction.SHORT, exit = "22400"))
        assertValue("7800", shortWin.grossPnl)
    }

    @Test
    fun `15b gross P&L needs a direction and a closing price`() {
        assertNotModelled(FuturesCalculator.calculate(priced(direction = null)).grossPnl, InputId.DIRECTION)
        assertNotModelled(FuturesCalculator.calculate(priced(exit = null)).grossPnl, InputId.EXIT_PRICE)
    }

    @Test
    fun `15c a mark price replaces a missing exit price and the P&L is unrealized`() {
        val c = FuturesCalculator.calculate(priced(exit = null).copy(markPrice = known("22600")))
        assertValue("5200", c.grossPnl)
        assertTrue(FnoLabels.UNREALIZED in c.grossPnl.labels)
        // A realised exit is not labelled unrealized.
        assertFalse(FnoLabels.UNREALIZED in FuturesCalculator.calculate(priced()).grossPnl.labels)
    }

    @Test
    fun `non-positive prices are INVALID_INPUT`() {
        assertInvalid(FuturesCalculator.calculate(priced(entry = "0")).entryValue, InputId.ENTRY_PRICE)
        assertInvalid(FuturesCalculator.calculate(priced(exit = "-5")).exitValue, InputId.EXIT_PRICE)
    }

    // ---- charges / net P&L / break-even with a SYNTHETIC validated schedule ------------------------------------------------------------

    @Test
    fun `net P&L is gross minus round-trip charges, per leg and per side`() {
        val schedule = syntheticSchedule()
        val long = FuturesCalculator.calculate(priced(Direction.LONG, schedule = schedule))
        // BUY leg on 1,463,800: 20 + 146.38 + 3.6 ; SELL leg on 1,469,000: 20 + 146.9 + 3.6 + 293.8
        assertValue("169.98", FnoResult.derived(long.openingLegCharges.value!!.total, Evidence.SUPPLIED, emptySet()))
        assertValue("464.3", FnoResult.derived(long.closingLegCharges.value!!.total, Evidence.SUPPLIED, emptySet()))
        assertValue("634.28", long.roundTripCharges)
        assertValue("4565.72", long.netPnl)
        assertEquals(listOf("flat", "pct", "onFlat"), long.openingLegCharges.value!!.lines.map { it.component })
        assertEquals(listOf("flat", "pct", "onFlat", "sellOnly"), long.closingLegCharges.value!!.lines.map { it.component })

        // SHORT: sides swap. SELL opens on 1,463,800 (20 + 146.38 + 3.6 + 292.76), BUY closes on 1,469,000 (20 + 146.9 + 3.6).
        val short = FuturesCalculator.calculate(priced(Direction.SHORT, schedule = schedule))
        assertValue("462.74", FnoResult.derived(short.openingLegCharges.value!!.total, Evidence.SUPPLIED, emptySet()))
        assertValue("170.5", FnoResult.derived(short.closingLegCharges.value!!.total, Evidence.SUPPLIED, emptySet()))
        assertValue("633.24", short.roundTripCharges)
        assertValue("-5833.24", short.netPnl)
        assertNotEquals(long.roundTripCharges.value, short.roundTripCharges.value)
    }

    @Test
    fun `net P&L is null when the exit price is unknown even with a schedule`() {
        val c = FuturesCalculator.calculate(priced(exit = null, schedule = syntheticSchedule()))
        assertNotModelled(c.netPnl, InputId.EXIT_PRICE)
        // The opening leg's charges do not need the exit price.
        assertTrue(c.openingLegCharges.isAvailable)
    }

    @Test
    fun `a schedule for a different broker is not used`() {
        val c = FuturesCalculator.calculate(priced(schedule = syntheticSchedule()).copy(broker = Input.Known("OtherBroker")))
        assertEquals(ResultKind.NOT_MODELLED, c.netPnl.kind)
        assertTrue(c.netPnl.warnings.any { "charge schedule is for broker" in it })
    }

    @Test
    fun `a schedule keyed by product needs the product`() {
        val def = syntheticDefinition().copy(
            keyedByProduct = true,
            productTypeKey = ProductType.NRML,
        )
        val schedule = (ValidatedChargeSchedule.validate(def) as ScheduleValidation.Valid).schedule
        val without = FuturesCalculator.calculate(priced(schedule = schedule))
        assertNotModelled(without.netPnl, InputId.PRODUCT)
        val with = FuturesCalculator.calculate(priced(schedule = schedule).copy(product = Input.Known(ProductType.NRML)))
        assertValue("4565.72", with.netPnl)

        val mismatched = FuturesCalculator.calculate(
            priced(schedule = schedule).copy(product = Input.Known(ProductType.MIS)),
        )
        assertEquals(ResultKind.NOT_MODELLED, mismatched.netPnl.kind)
        assertTrue(mismatched.netPnl.warnings.any { "keyed to product" in it })
        assertValue("5200", mismatched.grossPnl)

        // Order type does not matter unless the schedule keys on it (§8.2a I13).
        assertValue("4565.72", FuturesCalculator.calculate(priced(schedule = syntheticSchedule())).netPnl)
    }

    @Test
    fun `a schedule keyed by order type requires and matches its explicit key`() {
        val missingKey = ValidatedChargeSchedule.validate(
            syntheticDefinition().copy(keyedByOrderType = true),
        )
        assertTrue(missingKey is ScheduleValidation.Rejected)

        val schedule = when (
            val validation = ValidatedChargeSchedule.validate(
                syntheticDefinition().copy(
                    keyedByOrderType = true,
                    orderTypeKey = OrderType.LIMIT,
                ),
            )
        ) {
            is ScheduleValidation.Valid -> validation.schedule
            is ScheduleValidation.Rejected -> fail("schedule rejected: ${validation.errors}")
        }

        val missingInput = FuturesCalculator.calculate(priced(schedule = schedule))
        assertNotModelled(missingInput.netPnl, InputId.ORDER_TYPE)

        val matching = FuturesCalculator.calculate(
            priced(schedule = schedule).copy(orderType = Input.Known(OrderType.LIMIT)),
        )
        assertValue("4565.72", matching.netPnl)

        val mismatched = FuturesCalculator.calculate(
            priced(schedule = schedule).copy(orderType = Input.Known(OrderType.MARKET)),
        )
        assertEquals(ResultKind.NOT_MODELLED, mismatched.netPnl.kind)
        assertTrue(mismatched.netPnl.warnings.any { "keyed to order type" in it })
        assertValue("5200", mismatched.grossPnl)
    }

    @Test
    fun `a schedule rejects a key when its corresponding key flag is false`() {
        val product = ValidatedChargeSchedule.validate(
            syntheticDefinition().copy(productTypeKey = ProductType.NRML),
        )
        assertTrue(product is ScheduleValidation.Rejected)

        val orderType = ValidatedChargeSchedule.validate(
            syntheticDefinition().copy(orderTypeKey = OrderType.LIMIT),
        )
        assertTrue(orderType is ScheduleValidation.Rejected)
    }

    @Test
    fun `break-even is the exact price at which net P&L is zero`() {
        val schedule = syntheticSchedule()
        val entry = bd("22520")
        for (direction in Direction.values()) {
            val c = FuturesCalculator.calculate(priced(direction, schedule = schedule, exit = null))
            val be = (if (direction == Direction.LONG) c.breakEvenLong else c.breakEvenShort).value ?: fail("no break-even for $direction")
            if (direction == Direction.LONG) assertTrue(be > entry, "LONG break-even above entry") else assertTrue(be < entry, "SHORT break-even below entry")
            // Re-evaluate net P&L at the break-even price with the same schedule: residual ~ 0.
            val q = bd("65")
            val open = schedule.legCharges(direction.openingSide, entry.multiply(q)).total
            val close = schedule.legCharges(direction.closingSide, be.multiply(q)).total
            val gross = (if (direction == Direction.LONG) be.subtract(entry) else entry.subtract(be)).multiply(q)
            val residual = gross.subtract(open).subtract(close).abs()
            assertTrue(residual < bd("1E-20"), "residual $residual for $direction")
        }
    }

    @Test
    fun `break-even is unavailable for rounded or capped schedules and without a schedule`() {
        val rounded = syntheticSchedule(listOf(comp("pct", both, ChargeBasis.RateOfLegValue(bd("0.0001")), rounding = Rounding.To(2, RoundingMode.HALF_UP))))
        val c = FuturesCalculator.calculate(priced(schedule = rounded))
        assertEquals(ResultKind.NOT_MODELLED, c.breakEvenLong.kind)
        assertTrue(c.breakEvenLong.warnings.any { "no closed form" in it })
        // The charges themselves are still computed, using the schedule's own rounding.
        assertTrue(c.roundTripCharges.isAvailable)
        assertNotModelled(FuturesCalculator.calculate(priced(schedule = null)).breakEvenShort, InputId.BROKERAGE, InputId.STATUTORY_RATES, InputId.BROKER_RULES)
    }

    @Test
    fun `schedule rounding is applied exactly as declared`() {
        val s = syntheticSchedule(listOf(comp("pct", both, ChargeBasis.RateOfLegValue(bd("0.00011")), rounding = Rounding.To(0, RoundingMode.DOWN))))
        val leg = s.legCharges(Side.BUY, bd("1463800")) // 161.0180 -> 161
        assertEquals(0, bd("161").compareTo(leg.total))
        val capped = syntheticSchedule(listOf(comp("pct", both, ChargeBasis.RateOfLegValue(bd("0.0001")), min = Bound.Of(bd("200")), cap = Bound.Of(bd("300")))))
        assertEquals(0, bd("200").compareTo(capped.legCharges(Side.BUY, bd("1463800")).total)) // 146.38 -> minimum
        assertEquals(0, bd("300").compareTo(capped.legCharges(Side.BUY, bd("9000000")).total)) // 900 -> cap
    }

    @Test
    fun `validated schedule is isolated from caller mutations`() {
        val mutableSides = mutableSetOf(Side.BUY, Side.SELL)
        val mutableComponents = mutableListOf(
            comp("base", mutableSides, ChargeBasis.FlatPerLeg(bd("10"))),
            comp(
                "dependent",
                both,
                ChargeBasis.RateOfComponent("base", bd("0.1")),
            ),
        )
        val definition = syntheticDefinition(components = mutableComponents)

        val schedule = when (
            val result = ValidatedChargeSchedule.validate(definition)
        ) {
            is ScheduleValidation.Valid -> result.schedule
            is ScheduleValidation.Rejected -> fail(
                "schedule rejected: ${result.errors}",
            )
        }

        // Changes to the original input must not alter validated behavior.
        mutableSides.clear()
        mutableSides.add(Side.SELL)
        mutableComponents.clear()

        assertEquals(
            0,
            bd("11").compareTo(schedule.legCharges(Side.BUY, bd("100")).total),
        )
        assertEquals(
            0,
            bd("11").compareTo(schedule.legCharges(Side.SELL, bd("100")).total),
        )

        // The collections exposed by the validated schedule must reject mutation.
        assertFailsWith<UnsupportedOperationException> {
            (schedule.components as MutableList<ChargeComponentSpec>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (schedule.components.first().appliesTo as MutableSet<Side>).clear()
        }
    }

    // ---- schedule validation (§8.5) --------------------------------------------------------------------------------------------------------

    @Test
    fun `an incomplete or unevidenced schedule is rejected, never repaired`() {
        fun rejected(def: ChargeScheduleDefinition): List<String> =
            (ValidatedChargeSchedule.validate(def) as? ScheduleValidation.Rejected)?.errors ?: fail("expected rejection")

        val base = syntheticDefinition()
        assertTrue(rejected(base.copy(effectiveFrom = null)).any { "effective-from" in it })
        assertTrue(
            rejected(
                base.copy(
                    effectiveFrom = LocalDate.of(2026, 1, 2),
                    effectiveTo = LocalDate.of(2026, 1, 1),
                ),
            ).any { "effective-to date must not be before" in it },
        )
        assertTrue(rejected(base.copy(source = null)).any { "source" in it })
        assertTrue(rejected(base.copy(source = syntheticSource.copy(citation = " "))).any { "citation" in it })
        assertTrue(rejected(base.copy(components = emptyList())).any { "at least one component" in it })
        assertTrue(rejected(base.copy(broker = "")).any { "broker" in it })
        assertTrue(rejected(base.copy(components = listOf(comp("x", emptySet(), ChargeBasis.FlatPerLeg(bd("1")))))).any { "side" in it })
        assertTrue(rejected(base.copy(components = listOf(comp("x", both, ChargeBasis.RateOfLegValue(bd("-0.1")))))).any { "negative" in it })
        assertTrue(rejected(base.copy(components = listOf(comp("x", both, ChargeBasis.RateOfComponent("later", bd("0.1"))), comp("later", both, ChargeBasis.FlatPerLeg(bd("1")))))).any { "declared earlier" in it })
        assertTrue(rejected(base.copy(components = listOf(comp("a", both, ChargeBasis.FlatPerLeg(bd("1"))), comp("a", both, ChargeBasis.FlatPerLeg(bd("1")))))).any { "duplicate" in it })
        assertTrue(rejected(base.copy(components = listOf(comp("a", both, ChargeBasis.FlatPerLeg(bd("1")), min = Bound.Of(bd("5")), cap = Bound.Of(bd("2")))))).any { "minimum exceeds cap" in it })
    }

    @Test
    fun `combinations whose meaning section 8 does not define are rejected`() {
        fun errors(components: List<ChargeComponentSpec>) =
            (ValidatedChargeSchedule.validate(syntheticDefinition(components)) as? ScheduleValidation.Rejected)?.errors ?: fail("expected rejection")

        val roundedAndCapped = comp("a", both, ChargeBasis.RateOfLegValue(bd("0.1")), rounding = Rounding.To(2, RoundingMode.HALF_UP), cap = Bound.Of(bd("9")))
        assertTrue(errors(listOf(roundedAndCapped)).any { "undefined order" in it })

        val roundedBase = comp("a", both, ChargeBasis.RateOfLegValue(bd("0.1")), rounding = Rounding.To(2, RoundingMode.HALF_UP))
        val dependent = comp("b", both, ChargeBasis.RateOfComponent("a", bd("0.18")))
        assertTrue(errors(listOf(roundedBase, dependent)).any { "rounded value is undefined" in it })

        val sellOnlyBase = comp("a", setOf(Side.SELL), ChargeBasis.FlatPerLeg(bd("1")))
        assertTrue(errors(listOf(sellOnlyBase, comp("b", both, ChargeBasis.RateOfComponent("a", bd("0.18"))))).any { "must apply on every side" in it })
    }

    // ---- generic comparison record (§8.7) ----------------------------------------------------------------------------------------------------

    @Test
    fun `comparison record preserves provenance`() {
        val record = ComparisonRecord(
            computedOutput = "contract_leg_value",
            expectedValue = BigDecimal("1463800"),
            fixtureField = "displayed_turnover",
            tolerance = BigDecimal.ZERO,
            inputIds = listOf(
                InputId.ENTRY_PRICE,
                InputId.LOT_SIZE,
                InputId.LOTS,
            ),
            state = ComparisonState.PASS,
            computedValue = BigDecimal("1463800"),
        )

        assertEquals("contract_leg_value", record.computedOutput)
        assertEquals(BigDecimal("1463800"), record.expectedValue)
        assertEquals("displayed_turnover", record.fixtureField)
        assertEquals(BigDecimal.ZERO, record.tolerance)
        assertEquals(ComparisonState.PASS, record.state)
        assertEquals(BigDecimal("1463800"), record.computedValue)
        assertEquals(
            listOf(InputId.ENTRY_PRICE, InputId.LOT_SIZE, InputId.LOTS),
            record.inputIds,
        )
    }

    @Test
    fun `turnover comparison exposes generic comparison record`() {
        val inputs = priced(schedule = null).copy(
            displayedTurnover = Input.Known(
                DisplayedTurnover(
                    amount = bd("1463800"),
                    leg = Input.Known(TurnoverLeg.BUY_LEG, Evidence.VERIFIED),
                ),
                Evidence.VERIFIED,
            ),
        )

        val result = FuturesCalculator.calculate(inputs)
        val comparison = result.turnoverComparison.asComparisonRecord()

        assertEquals(ComparisonState.PASS, comparison.state)
        assertEquals(bd("1463800"), comparison.expectedValue)
        assertEquals(bd("1463800"), comparison.computedValue)
        assertEquals("displayed_turnover", comparison.fixtureField)
        assertEquals("contract_leg_value", comparison.computedOutput)
    }

    @Test
    fun `not comparable comparison record remains distinct from failure`() {
        val result = FuturesCalculator.calculate(
            FuturesInputs(
                lotSize = Input.Known(65L, Evidence.VERIFIED),
                lots = Input.Known(bd("1"), Evidence.VERIFIED),
                entryPrice = Input.Known(bd("22520"), Evidence.VERIFIED),
                direction = Input.Known(Direction.LONG, Evidence.VERIFIED),
            )
        )

        val comparison = result.turnoverComparison.asComparisonRecord()

        assertEquals(ComparisonState.NOT_COMPARABLE, comparison.state)
        assertNull(comparison.expectedValue)
        assertNull(comparison.computedValue)
        assertEquals("displayed_turnover", comparison.fixtureField)
    }

    @Test
    fun `invalid turnover comparison remains invalid input`() {
        val result = FuturesCalculator.calculate(
            FuturesInputs(
                lotSize = Input.Known(65L, Evidence.VERIFIED),
                lots = Input.Known(bd("1"), Evidence.VERIFIED),
                entryPrice = Input.Known(bd("22520"), Evidence.VERIFIED),
                direction = Input.Known(Direction.LONG, Evidence.VERIFIED),
                displayedTurnover = Input.Known(
                    DisplayedTurnover(
                        amount = BigDecimal.ZERO,
                        leg = Input.Known(TurnoverLeg.BUY_LEG, Evidence.VERIFIED),
                    ),
                    Evidence.VERIFIED,
                ),
            )
        )

        val comparison = result.turnoverComparison.asComparisonRecord()

        assertEquals(ComparisonState.INVALID_INPUT, comparison.state)
        assertEquals(BigDecimal.ZERO, comparison.expectedValue)
        assertNull(comparison.computedValue)
    }

    // ---- purity / determinism -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the calculation is pure`() {
        val inputs = priced(schedule = syntheticSchedule()).copy(
            valuationPrice = Input.Known(ValuationPrice(bd("22520"), ValuationBasis.REFERENCE_PRICE)),
        )
        assertEquals(FuturesCalculator.calculate(inputs), FuturesCalculator.calculate(inputs))
    }

    @Test
    fun `nothing is defaulted when every input is unknown`() {
        val c = FuturesCalculator.calculate(FuturesInputs())
        val all = listOf(
            c.openingSide, c.closingSide, c.lots, c.quantity, c.entryValue, c.exitValue, c.contractNotional,
            c.grossPnl, c.marginRequirement, c.capitalUsed, c.effectiveLeverage, c.openingLegCharges,
            c.closingLegCharges, c.roundTripCharges, c.netPnl, c.breakEvenLong, c.breakEvenShort,
        )
        assertTrue(all.all { it.value == null && it.kind == ResultKind.NOT_MODELLED && it.evidence == null })
        assertEquals(ComparisonState.NOT_COMPARABLE, c.turnoverComparison.state)
    }
}
