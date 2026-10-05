package com.algotrader.backtest

/**
 * Phase 3 Patch 6 — explicit futures P&L foundation.
 *
 * Keeps four things separate, which index backtests never needed to:
 *
 * - price points: signed favourable price movement per unit (exit - entry for
 *   LONG, entry - exit for SHORT). Pure index/price movement, not rupees.
 * - contract quantity: units per lot (the contract's lot size).
 * - lots: whole number of lots traded.
 * - rupee P&L: `price points x contract quantity x lots`.
 *
 * The contract quantity is nullable on purpose. `null` means "not known from an
 * authoritative source". It is never replaced by 1, by an index lot size, or
 * by a remembered constant: an unknown quantity yields [Outcome.Unavailable],
 * never a number. No costs, margin or slippage are included (Patch 7).
 */
object FuturesPnl {

    /** The decomposed result of one futures round trip, before any costs. */
    data class Decomposition(
        val direction: TradeDirection,
        /** Signed favourable move per unit, in price points. */
        val pricePoints: Double,
        /** Units per lot. */
        val contractQuantity: Int,
        /** Whole lots traded. */
        val lots: Int,
        /** pricePoints x contractQuantity x lots, in rupees, before costs. */
        val rupeePnl: Double
    ) {
        /** Total units traded (contractQuantity x lots). */
        val totalQuantity: Long get() = contractQuantity.toLong() * lots.toLong()
    }

    sealed interface Outcome {
        data class Computed(val value: Decomposition) : Outcome
        data class Unavailable(val reason: String) : Outcome
    }

    /** Signed favourable price movement per unit. Zero movement is exactly 0.0. */
    fun pricePoints(direction: TradeDirection, entryPrice: Double, exitPrice: Double): Double =
        when (direction) {
            TradeDirection.LONG -> exitPrice - entryPrice
            TradeDirection.SHORT -> entryPrice - exitPrice
        }

    /**
     * @param contractQuantity units per lot, or null when unknown (-> Unavailable).
     * @param lots whole number of lots, must be >= 1.
     */
    fun compute(
        direction: TradeDirection,
        entryPrice: Double,
        exitPrice: Double,
        contractQuantity: Int?,
        lots: Int
    ): Outcome {
        if (contractQuantity == null) {
            return Outcome.Unavailable(
                "Contract lot size is unknown; rupee P&L cannot be computed."
            )
        }
        if (contractQuantity < 1) {
            return Outcome.Unavailable("Contract lot size must be at least 1, got $contractQuantity.")
        }
        if (lots < 1) {
            return Outcome.Unavailable("Lots must be at least 1, got $lots.")
        }
        if (!entryPrice.isFinite() || !exitPrice.isFinite() || entryPrice <= 0.0 || exitPrice <= 0.0) {
            return Outcome.Unavailable("Entry and exit prices must be finite and greater than zero.")
        }

        val points = pricePoints(direction, entryPrice, exitPrice)
        val rupees = points * contractQuantity.toDouble() * lots.toDouble()
        return Outcome.Computed(Decomposition(direction, points, contractQuantity, lots, rupees))
    }

    /**
     * Decomposes an engine [BacktestTrade]. The trade carries total units, so
     * lots are derived as `quantity / contractQuantity` and must be a whole
     * number; otherwise the trade was not sized with this lot size and the
     * decomposition is Unavailable rather than rounded.
     */
    fun compute(trade: BacktestTrade, contractQuantity: Int?): Outcome {
        if (contractQuantity == null) return compute(
            trade.direction, trade.entryPrice, trade.exitPrice, null, 1
        )
        if (contractQuantity < 1) return compute(
            trade.direction, trade.entryPrice, trade.exitPrice, contractQuantity, 1
        )
        val rawLots = trade.quantity / contractQuantity.toDouble()
        val lots = Math.round(rawLots)
        if (kotlin.math.abs(rawLots - lots.toDouble()) > 1e-9 || lots < 1L || lots > Int.MAX_VALUE.toLong()) {
            return Outcome.Unavailable(
                "Trade quantity ${trade.quantity} is not a whole number of lots of $contractQuantity."
            )
        }
        return compute(
            trade.direction, trade.entryPrice, trade.exitPrice, contractQuantity, lots.toInt()
        )
    }
}
