package com.algotrader.backtest

/**
 * How many units to trade when opening a new position. Deliberately simple
 * for Phase 1 — no leverage, margin, or contract-specific sizing.
 */
sealed interface PositionSizing {

    /** Quantity to trade, given the flat equity available and the fill price. */
    fun quantityFor(equity: Double, price: Double): Double

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
}
