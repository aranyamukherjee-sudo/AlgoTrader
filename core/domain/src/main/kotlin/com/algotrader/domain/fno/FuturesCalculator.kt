package com.algotrader.domain.fno

import java.math.BigDecimal
import java.math.MathContext

/** Labels attached to results (§8.2a, §8.4). */
object FnoLabels {
    /** Closing price is a supplied mark price, not a realised exit (§8.2 I11). */
    const val UNREALIZED = "unrealized"

    /** The result uses I10/I22 while the reference-price type I20 is unknown (§8.2a). */
    const val PRICE_TYPE_UNKNOWN = "price type unknown"

    /** Margin requirement as supplied by the broker (§8.4). */
    const val BROKER_ESTIMATE = "broker estimate"
}

enum class ComparisonState { PASS, FAIL, NOT_COMPARABLE, INVALID_INPUT }

/**
 * Comparison of a displayed exchange turnover with a computed leg value (§8.7).
 * NOT_COMPARABLE is not a failure: it means an input is unknown/invalid or the
 * turnover-leg association is not established.
 */
data class TurnoverComparison(
    val state: ComparisonState,
    val displayedTurnover: BigDecimal?,
    val comparedLeg: TurnoverLeg?,
    val computedLegValue: BigDecimal?,
    val tolerance: BigDecimal,
    val reasons: List<String>,
)

/** All FNO-1 outputs (§8.4). Each carries kind, evidence, dependencies and warnings. */
data class FuturesCalculation(
    val openingSide: FnoResult<Side>,
    val closingSide: FnoResult<Side>,
    val lots: FnoResult<Long>,
    /** C2: lots x lot size. */
    val quantity: FnoResult<Long>,
    /** C3: entry price x quantity. */
    val entryValue: FnoResult<BigDecimal>,
    /** C4: exit (or mark) price x quantity. */
    val exitValue: FnoResult<BigDecimal>,
    /** C5: valuation price x quantity. Exposure only; not margin, capital or turnover. */
    val contractNotional: FnoResult<BigDecimal>,
    /** C6: before costs. Labelled "unrealized" when a mark price replaced the exit price. */
    val grossPnl: FnoResult<BigDecimal>,
    /** I15 as supplied (kind SUPPLIED), labelled "broker estimate". Never computed. */
    val marginRequirement: FnoResult<BigDecimal>,
    /** Capital blocked: the supplied margin requirement, unchanged (§2/§8.4). */
    val capitalUsed: FnoResult<BigDecimal>,
    /** C9: only with a fully supplied, matching margin scenario; otherwise null. */
    val effectiveLeverage: FnoResult<BigDecimal>,
    val openingLegCharges: FnoResult<LegCharges>,
    val closingLegCharges: FnoResult<LegCharges>,
    /** C8. */
    val roundTripCharges: FnoResult<BigDecimal>,
    /** C7: gross P&L minus round-trip charges. Null unless a validated schedule applies. */
    val netPnl: FnoResult<BigDecimal>,
    /** C10 for a LONG position opened at the entry price (above entry). */
    val breakEvenLong: FnoResult<BigDecimal>,
    /** C10 for a SHORT position opened at the entry price (below entry). */
    val breakEvenShort: FnoResult<BigDecimal>,
    /** §8.7 comparison of the displayed turnover with a leg value; NOT_COMPARABLE unless the leg is known. */
    val turnoverComparison: TurnoverComparison,
)

/**
 * Pure FNO-1 calculator (§8.7 rule 1): the same inputs always give the same
 * outputs and nothing is defaulted. Prices and values are unrounded
 * [BigDecimal]s. The only inexact operations are the two divisions (leverage
 * and break-even), performed at [DIVISION] precision (34 significant digits);
 * display rounding is a presentation concern.
 */
object FuturesCalculator {
    private val DIVISION = MathContext.DECIMAL128

    private val IDENTITY = listOf(
        InputId.BROKER, InputId.EXCHANGE, InputId.SEGMENT,
        InputId.UNDERLYING, InputId.CONTRACT_MONTH, InputId.CONTRACT_ID,
    )

    fun calculate(inputs: FuturesInputs, turnoverTolerance: BigDecimal = BigDecimal.ZERO): FuturesCalculation {
        val i = inputs
        require(turnoverTolerance.signum() >= 0) { "tolerance must not be negative" }

        // ---- input results -------------------------------------------------
        val price = { p: BigDecimal -> if (p.signum() <= 0) "price must be greater than zero" else null }
        val dirR = i.direction.asResult(InputId.DIRECTION)
        val entryR = i.entryPrice.asResult(InputId.ENTRY_PRICE, price)
        val valR = i.valuationPrice.asResult(InputId.VALUATION_PRICE) { price(it.price) }
        val dateR = i.estimateDate.asResult(InputId.ESTIMATE_DATE)
        val productR = i.product.asResult(InputId.PRODUCT)
        val orderTypeR = i.orderType.asResult(InputId.ORDER_TYPE)
        val priceTypeKnown = i.referencePriceType is Input.Known

        // I11: a supplied mark price takes the place of an unknown exit price; P&L is then unrealized.
        val closingR: FnoResult<BigDecimal> = if (i.exitPrice == Input.Unknown) {
            val mark = i.markPrice.asResult(InputId.EXIT_PRICE, price)
            if (mark.isAvailable) mark.copy(labels = mark.labels + FnoLabels.UNREALIZED) else mark
        } else {
            i.exitPrice.asResult(InputId.EXIT_PRICE, price)
        }

        // ---- C1, C2 ----------------------------------------------------------
        val openingSide = derive(listOf(dirR)) { dirR.requireValue().openingSide }
        val closingSide = derive(listOf(dirR)) { dirR.requireValue().closingSide }

        val lotsR = i.lots.asResult(InputId.LOTS) { l ->
            if (l.signum() <= 0 || l.stripTrailingZeros().scale() > 0) "lots must be a whole number >= 1 (never rounded), got $l" else null
        }
        val lotSizeR = i.lotSize.asResult(InputId.LOT_SIZE) { if (it < 1) "lot size must be >= 1, got $it" else null }
        val lots = derive(listOf(lotsR)) { lotsR.requireValue().longValueExact() }
        val quantity = derive(listOf(lots, lotSizeR)) { Math.multiplyExact(lots.requireValue(), lotSizeR.requireValue()) }

        // ---- C3, C4, C5, C6 ----------------------------------------------
        fun qty() = BigDecimal(quantity.requireValue())
        val entryValue = derive(listOf(entryR, quantity)) { entryR.requireValue().multiply(qty()) }
        val exitValue = derive(listOf(closingR, quantity)) { closingR.requireValue().multiply(qty()) }
        val notional = derive(listOf(valR, quantity)) { valR.requireValue().price.multiply(qty()) }
        val gross = derive(listOf(entryR, closingR, dirR, quantity)) {
            val e = entryR.requireValue()
            val x = closingR.requireValue()
            val move = if (dirR.requireValue() == Direction.LONG) x.subtract(e) else e.subtract(x)
            move.multiply(qty())
        }

        // ---- gates -----------------------------------------------------------
        val identity = gate(IDENTITY.map { it to identityInput(i, it) })
        val contract = gate(IDENTITY.map { it to identityInput(i, it) } + (InputId.EXPIRY to i.expiry))

        // ---- charges (C8) ------------------------------------------------------
        val scheduleSel = selectSchedule(i, identity, dateR, productR, orderTypeR)
        val opening = derive(listOf(scheduleSel, openingSide, entryValue)) {
            scheduleSel.requireValue().legCharges(openingSide.requireValue(), entryValue.requireValue())
        }
        val closing = derive(listOf(scheduleSel, closingSide, exitValue)) {
            scheduleSel.requireValue().legCharges(closingSide.requireValue(), exitValue.requireValue())
        }
        val roundTrip = derive(listOf(opening, closing)) { opening.requireValue().total.add(closing.requireValue().total) }
        val net = derive(listOf(gross, roundTrip)) { gross.requireValue().subtract(roundTrip.requireValue()) }

        // ---- margin (I15) and leverage (C9) ---------------------------------
        val margin = suppliedMargin(i, contract, productR)
        val leverage = leverage(i, notional, margin, quantity, openingSide, productR, dateR, valR)

        return FuturesCalculation(
            openingSide = openingSide,
            closingSide = closingSide,
            lots = lots,
            quantity = quantity,
            entryValue = entryValue.priceTypeLabelled(priceTypeKnown),
            exitValue = exitValue,
            contractNotional = notional.priceTypeLabelled(priceTypeKnown),
            grossPnl = gross.priceTypeLabelled(priceTypeKnown),
            marginRequirement = margin,
            capitalUsed = margin,
            effectiveLeverage = leverage.priceTypeLabelled(priceTypeKnown),
            openingLegCharges = opening,
            closingLegCharges = closing,
            roundTripCharges = roundTrip,
            netPnl = net.priceTypeLabelled(priceTypeKnown),
            breakEvenLong = breakEven(Direction.LONG, entryR, quantity, scheduleSel).priceTypeLabelled(priceTypeKnown),
            breakEvenShort = breakEven(Direction.SHORT, entryR, quantity, scheduleSel).priceTypeLabelled(priceTypeKnown),
            turnoverComparison = compareTurnover(i.displayedTurnover, openingSide, entryValue, exitValue, turnoverTolerance),
        )
    }

    // ---------------------------------------------------------------------------

    private fun identityInput(i: FuturesInputs, id: InputId): Input<*> = when (id) {
        InputId.BROKER -> i.broker
        InputId.EXCHANGE -> i.exchange
        InputId.SEGMENT -> i.segment
        InputId.UNDERLYING -> i.underlying
        InputId.CONTRACT_MONTH -> i.contractMonth
        InputId.CONTRACT_ID -> i.contractId
        else -> error("not an identity input: $id")
    }

    /** All listed inputs must be known (and non-blank when text); otherwise NOT_MODELLED / INVALID_INPUT. */
    private fun gate(entries: List<Pair<InputId, Input<*>>>): FnoResult<Unit> {
        val results = entries.map { (id, input) ->
            @Suppress("UNCHECKED_CAST")
            (input as Input<Any?>).asResult(id) { v -> if (v is String && v.isBlank()) "must not be blank" else null }
        }
        return derive(results) { }
    }

    /** §8.2a: schedule lookups need identity (I1-I5, I7), the estimate date (I19) and any key the schedule declares. */
    private fun selectSchedule(
        i: FuturesInputs,
        identity: FnoResult<Unit>,
        dateR: FnoResult<java.time.LocalDate>,
        productR: FnoResult<ProductType>,
        orderTypeR: FnoResult<OrderType>,
    ): FnoResult<ValidatedChargeSchedule> {
        val schemaIds = setOf(InputId.BROKERAGE, InputId.STATUTORY_RATES, InputId.BROKER_RULES)
        val scheduleR: FnoResult<ValidatedChargeSchedule> = when (val s = i.chargeSchedule) {
            is Input.Known -> FnoResult.supplied(s.value, s.evidence, schemaIds)
            Input.Unknown -> FnoResult.notModelled(
                schemaIds, schemaIds,
                listOf("no validated charge schedule supplied; charges are not estimated"),
            )
            is Input.Invalid -> FnoResult.invalid(schemaIds, schemaIds, warnings = listOf("charge schedule: ${s.reason}"))
        }
        val known = (i.chargeSchedule as? Input.Known)?.value
        val keyed = buildList<FnoResult<*>> {
            if (known?.keyedByOrderType == true) add(orderTypeR)
            if (known?.keyedByProduct == true) add(productR)
        }
        val candidate = derive(listOf(identity, scheduleR, dateR) + keyed) { scheduleR.requireValue() }
        if (!candidate.isAvailable || known == null) return candidate

        val problems = ArrayList<String>()
        fun differs(field: String, supplied: Input<String>, scheduleValue: String) {
            val v = (supplied as? Input.Known)?.value
            if (v == null || !v.trim().equals(scheduleValue, ignoreCase = true)) {
                problems += "charge schedule is for $field '$scheduleValue', not '$v'"
            }
        }
        differs("broker", i.broker, known.broker)
        differs("exchange", i.exchange, known.exchange)
        differs("segment", i.segment, known.segment)
        val date = dateR.requireValue()
        if (date < known.effectiveFrom) problems += "estimate date $date is before the schedule's effective-from ${known.effectiveFrom}"
        return if (problems.isEmpty()) candidate
        else FnoResult.notModelled(candidate.dependsOn, emptySet(), candidate.warnings + problems)
    }

    /**
     * I15 as supplied. Needs contract identity including expiry (§8.2a: contract-specific
     * lookups), the position's product (I14) and the quote. Never computed from notional.
     */
    private fun suppliedMargin(
        i: FuturesInputs,
        contract: FnoResult<Unit>,
        productR: FnoResult<ProductType>,
    ): FnoResult<BigDecimal> {
        // I15 is a supplied broker quote. Identity/expiry/product completeness
        // is required for leverage scenario matching, but must not prevent a
        // supplied margin quote from being preserved unchanged.
        val quoteR = i.margin.asResult(InputId.MARGIN) {
            if (it.amount.signum() <= 0) "margin amount must be greater than zero" else null
        }
        if (!quoteR.isAvailable) return quoteR.unavailable()

        val sc = quoteR.requireValue().scenario
        val unknown = buildList {
            if (sc.quantity !is Input.Known) add("quantity")
            if (sc.side !is Input.Known) add("side")
            if (sc.product !is Input.Known) add("product")
            if (sc.date !is Input.Known) add("date")
            if (sc.priceBasis !is Input.Known) add("price basis")
        }

        return FnoResult.supplied(
            quoteR.requireValue().amount,
            quoteR.evidence!!,
            quoteR.dependsOn,
            quoteR.warnings + unknown.map { "margin scenario $it is UNKNOWN" },
            quoteR.labels + FnoLabels.BROKER_ESTIMATE,
        )
    }

    /** C9: notional / margin, only when the margin scenario is fully supplied and matches the notional's. */
    private fun leverage(
        i: FuturesInputs,
        notional: FnoResult<BigDecimal>,
        margin: FnoResult<BigDecimal>,
        quantity: FnoResult<Long>,
        openingSide: FnoResult<Side>,
        productR: FnoResult<ProductType>,
        dateR: FnoResult<java.time.LocalDate>,
        valR: FnoResult<ValuationPrice>,
    ): FnoResult<BigDecimal> {
        val candidate = derive(listOf(notional, margin, quantity, openingSide, productR, dateR, valR)) { }
        if (!candidate.isAvailable) return candidate.unavailable()
        val sc = (i.margin as Input.Known).value.scenario
        val problems = ArrayList<String>()
        fun <T> check(name: String, scenario: Input<T>, position: T) {
            when (scenario) {
                is Input.Known -> if (scenario.value != position) problems += "margin scenario $name (${scenario.value}) does not match the position ($position)"
                Input.Unknown -> problems += "margin scenario $name is UNKNOWN"
                is Input.Invalid -> problems += "margin scenario $name is invalid: ${scenario.reason}"
            }
        }
        check("quantity", sc.quantity, quantity.requireValue())
        check("side", sc.side, openingSide.requireValue())
        check("product", sc.product, productR.requireValue())
        check("date", sc.date, dateR.requireValue())
        check("price basis", sc.priceBasis, valR.requireValue().basis)
        if (problems.isNotEmpty()) {
            return FnoResult.notModelled(candidate.dependsOn + InputId.MARGIN, setOf(InputId.MARGIN), candidate.warnings + problems)
        }
        return derive(listOf(candidate, notional, margin), setOf(InputId.MARGIN)) {
            notional.requireValue().divide(margin.requireValue(), DIVISION)
        }
    }

    /**
     * C10: the exit price at which net P&L is zero, solved exactly in unrounded
     * arithmetic. Only possible when both legs' charges are affine in leg value
     * (no rounding, minimum or cap declared by the schedule); otherwise, or when
     * no unique positive solution exists, NOT_MODELLED.
     */
    private fun breakEven(
        direction: Direction,
        entryR: FnoResult<BigDecimal>,
        quantity: FnoResult<Long>,
        schedule: FnoResult<ValidatedChargeSchedule>,
    ): FnoResult<BigDecimal> {
        val base = derive(listOf(entryR, quantity, schedule)) { }
        if (!base.isAvailable) return base.unavailable()
        val s = schedule.requireValue()
        val open = s.affineForm(direction.openingSide)
        val close = s.affineForm(direction.closingSide)
        if (open == null || close == null) {
            return FnoResult.notModelled(
                base.dependsOn, emptySet(),
                base.warnings + "break-even has no closed form: the schedule declares rounding, a minimum or a cap",
            )
        }
        val e = entryR.requireValue()
        val q = BigDecimal(quantity.requireValue())
        val eq = e.multiply(q)
        val one = BigDecimal.ONE
        // LONG : (x-e)q - (ao + bo*e*q) - (ac + bc*x*q) = 0
        // SHORT: (e-x)q - (ao + bo*e*q) - (ac + bc*x*q) = 0
        val numerator: BigDecimal
        val denominator: BigDecimal
        if (direction == Direction.LONG) {
            numerator = eq.multiply(one.add(open.beta)).add(open.alpha).add(close.alpha)
            denominator = q.multiply(one.subtract(close.beta))
        } else {
            numerator = eq.multiply(one.subtract(open.beta)).subtract(open.alpha).subtract(close.alpha)
            denominator = q.multiply(one.add(close.beta))
        }
        if (denominator.signum() <= 0) {
            return FnoResult.notModelled(base.dependsOn, emptySet(), base.warnings + "break-even has no unique solution under this schedule")
        }
        val x = numerator.divide(denominator, DIVISION)
        if (x.signum() <= 0) {
            return FnoResult.notModelled(base.dependsOn, emptySet(), base.warnings + "break-even has no positive solution under this schedule")
        }
        return derive(listOf(base, entryR, quantity, schedule)) { x }
    }

    /** §8.7: a displayed turnover is compared with a leg value only when its leg is established. */
    private fun compareTurnover(
        turnover: Input<DisplayedTurnover>,
        openingSide: FnoResult<Side>,
        entryValue: FnoResult<BigDecimal>,
        exitValue: FnoResult<BigDecimal>,
        tolerance: BigDecimal,
    ): TurnoverComparison {
        fun notComparable(vararg reasons: String, displayed: BigDecimal? = null, leg: TurnoverLeg? = null) =
            TurnoverComparison(ComparisonState.NOT_COMPARABLE, displayed, leg, null, tolerance, reasons.toList())

        val t = when (turnover) {
            Input.Unknown -> return notComparable("no displayed turnover supplied")
            is Input.Invalid -> return notComparable("displayed turnover is invalid: ${turnover.reason}")
            is Input.Known -> turnover.value
        }

        if (t.amount.signum() <= 0) {
            return TurnoverComparison(
                ComparisonState.INVALID_INPUT,
                t.amount,
                null,
                null,
                tolerance,
                listOf("displayed turnover amount must be greater than zero"),
            )
        }

        val leg = when (val l = t.leg) {
            Input.Unknown -> return notComparable("turnover-leg association is UNKNOWN; it is not inferred", displayed = t.amount)
            is Input.Invalid -> return notComparable("turnover-leg association is invalid: ${l.reason}", displayed = t.amount)
            is Input.Known -> l.value
        }
        val opening = when (leg) {
            TurnoverLeg.OPENING_LEG -> true
            TurnoverLeg.CLOSING_LEG -> false
            TurnoverLeg.BUY_LEG, TurnoverLeg.SELL_LEG -> {
                if (!openingSide.isAvailable) {
                    return notComparable("direction is unknown, so a $leg cannot be mapped to the opening or closing leg", displayed = t.amount, leg = leg)
                }
                (openingSide.requireValue() == Side.BUY) == (leg == TurnoverLeg.BUY_LEG)
            }
        }
        val computed = if (opening) entryValue else exitValue
        if (!computed.isAvailable) {
            return notComparable(
                "computed ${if (opening) "entry" else "exit"} value is unavailable (${computed.kind}, missing ${computed.missingInputs.map { it.code }})",
                displayed = t.amount, leg = leg,
            )
        }
        val value = computed.requireValue()
        val state = if (value.subtract(t.amount).abs() <= tolerance) ComparisonState.PASS else ComparisonState.FAIL
        return TurnoverComparison(state, t.amount, leg, value, tolerance, emptyList())
    }

    private fun <T> FnoResult<T>.priceTypeLabelled(priceTypeKnown: Boolean): FnoResult<T> =
        if (!priceTypeKnown && isAvailable && (InputId.ENTRY_PRICE in dependsOn || InputId.VALUATION_PRICE in dependsOn)) {
            copy(labels = labels + FnoLabels.PRICE_TYPE_UNKNOWN)
        } else {
            this
        }
}
