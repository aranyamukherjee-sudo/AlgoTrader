package com.algotrader.backtest

import kotlin.math.floor
import kotlin.math.max

/**
 * How many units to trade when opening a new position. Deliberately simple —
 * no leverage or margin modelling.
 *
 * Instruments with a contract size (F&O) are handled through the lot-aware
 * [quantityFor] overload: whenever `lotSize > 1` the returned quantity is
 * always a whole multiple of `lotSize` (possibly zero if less than one lot
 * is affordable/requested), never a fraction of a lot.
 */
sealed interface PositionSizing {

    /** Quantity to trade, given the flat equity available and the fill price. */
    fun quantityFor(equity: Double, price: Double): Double

    /**
     * Lot-aware quantity. With `lotSize <= 1` this is exactly the plain
     * overload. Otherwise the plain quantity is rounded down to whole lots.
     */
    fun quantityFor(equity: Double, price: Double, lotSize: Int): Double =
        roundDownToWholeLots(quantityFor(equity, price), lotSize)

    /** Always trade the same fixed number of units, regardless of price or equity. */
    data class FixedQuantity(val quantity: Double) : PositionSizing {
        init {
            require(quantity > 0.0) { "quantity must be greater than zero" }
        }

        override fun quantityFor(equity: Double, price: Double): Double = quantity
    }

    /** Trade whatever quantity [percent] of current (flat) equity buys at [price]. */
    data class PercentOfEquity(val percent: Double) : PositionSizing {
        init {
            require(percent > 0.0 && percent <= 100.0) { "percent must be between 0 and 100" }
        }

        override fun quantityFor(equity: Double, price: Double): Double {
            if (price <= 0.0) return 0.0
            return (equity * percent / 100.0) / price
        }
    }

    /**
     * Always trade a fixed whole number of lots. Quantity is `lots x lotSize`
     * (so 1 lot of NIFTY futures at lot size 65 is 65 units). For a
     * single-unit instrument (`lotSize == 1`) a lot is one unit.
     */
    data class FixedLots(val lots: Int) : PositionSizing {
        init {
            require(lots >= 1) { "lots must be at least 1" }
        }

        override fun quantityFor(equity: Double, price: Double): Double = lots.toDouble()

        override fun quantityFor(equity: Double, price: Double, lotSize: Int): Double =
            lots.toDouble() * max(lotSize, 1)
    }
}

/**
 * Rounds [units] down to a whole multiple of [lotSize]. A non-positive
 * [units] gives 0. `lotSize <= 1` leaves [units] untouched (no lots involved).
 */
internal fun roundDownToWholeLots(units: Double, lotSize: Int): Double {
    if (units <= 0.0) return 0.0
    if (lotSize <= 1) return units
    // Small epsilon so e.g. 130.00000000000003 / 65 does not floor to 1 lot.
    val lots = floor(units / lotSize + 1e-9)
    return lots * lotSize
}
