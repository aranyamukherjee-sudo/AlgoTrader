package com.algotrader.domain.fno

import java.math.BigDecimal
import java.time.LocalDate

/** Trade side of a leg. */
enum class Side { BUY, SELL }

/** Position direction (I12). LONG opens with BUY, SHORT opens with SELL (C1). */
enum class Direction {
    LONG,
    SHORT;

    val openingSide: Side get() = if (this == LONG) Side.BUY else Side.SELL
    val closingSide: Side get() = if (this == LONG) Side.SELL else Side.BUY
}

/** Order type (I13). Affects no arithmetic; relevant only if a validated schedule keys on it. */
enum class OrderType { MARKET, LIMIT, STOP_LOSS, STOP_LOSS_MARKET, OTHER }

/**
 * Product / margin mode (I14). The "Margin" label shown by an estimator is
 * NOT one of these: it does not establish NRML vs MIS, so it must be passed
 * as [Input.Unknown].
 */
enum class ProductType { NRML, MIS, OTHER }

/** Reference-price type (I20). */
enum class ReferencePriceType { LTP, SETTLEMENT, CLOSE, OTHER }

/**
 * Which price a valuation price (I22) is stated as. The caller states this
 * explicitly; it is never inferred. Used to match a margin scenario (C9).
 */
enum class ValuationBasis { ENTRY_PRICE, EXIT_PRICE, MARK_PRICE, REFERENCE_PRICE }

/** I22: the price the caller states the contract notional is valued at. */
data class ValuationPrice(val price: BigDecimal, val basis: ValuationBasis)

/**
 * The scenario a margin quote was produced for (I15). Every element that was
 * not supplied stays [Input.Unknown]; nothing is defaulted.
 */
data class MarginScenario(
    val quantity: Input<Long> = Input.Unknown,
    val side: Input<Side> = Input.Unknown,
    val product: Input<ProductType> = Input.Unknown,
    val date: Input<LocalDate> = Input.Unknown,
    val priceBasis: Input<ValuationBasis> = Input.Unknown,
)

/**
 * I15: a margin requirement amount (rupees) together with its scenario. Margin
 * is always supplied; ALTRIXA never computes or reverse-engineers it.
 */
data class MarginQuote(val amount: BigDecimal, val scenario: MarginScenario = MarginScenario())

/**
 * Which leg a displayed exchange turnover belongs to. The association is
 * UNKNOWN unless evidence establishes it, and is never inferred from a price.
 */
enum class TurnoverLeg { BUY_LEG, SELL_LEG, OPENING_LEG, CLOSING_LEG }

/**
 * A turnover value displayed by an estimator (supplied, comparison only). It
 * is neither notional (C5) nor a leg value (C3/C4) by definition (§8.3).
 */
data class DisplayedTurnover(
    val amount: BigDecimal,
    val leg: Input<TurnoverLeg> = Input.Unknown,
)

/**
 * All inputs of the FNO-1 contract (§8.2). Every field defaults to
 * [Input.Unknown]: there are no hidden defaults (§8.7 rule 1). The charge
 * schedule covers I16–I18 and is only ever a [ValidatedChargeSchedule].
 */
data class FuturesInputs(
    val broker: Input<String> = Input.Unknown,
    val exchange: Input<String> = Input.Unknown,
    val segment: Input<String> = Input.Unknown,
    val underlying: Input<String> = Input.Unknown,
    val contractMonth: Input<String> = Input.Unknown,
    val expiry: Input<LocalDate> = Input.Unknown,
    val contractId: Input<String> = Input.Unknown,
    /** I8: units per lot, effective on the position's opening date. */
    val lotSize: Input<Long> = Input.Unknown,
    /** I9: number of lots. A BigDecimal so a non-whole value can be represented and rejected, never rounded. */
    val lots: Input<BigDecimal> = Input.Unknown,
    val entryPrice: Input<BigDecimal> = Input.Unknown,
    val exitPrice: Input<BigDecimal> = Input.Unknown,
    /** For an open position: takes the place of the exit price; P&L is then labelled unrealized (I11). */
    val markPrice: Input<BigDecimal> = Input.Unknown,
    val direction: Input<Direction> = Input.Unknown,
    val orderType: Input<OrderType> = Input.Unknown,
    val product: Input<ProductType> = Input.Unknown,
    val margin: Input<MarginQuote> = Input.Unknown,
    val chargeSchedule: Input<ValidatedChargeSchedule> = Input.Unknown,
    val estimateDate: Input<LocalDate> = Input.Unknown,
    val referencePriceType: Input<ReferencePriceType> = Input.Unknown,
    val valuationPrice: Input<ValuationPrice> = Input.Unknown,
    val displayedTurnover: Input<DisplayedTurnover> = Input.Unknown,
)
