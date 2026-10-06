package com.algotrader.intelligence.opportunity

import java.time.Instant

/**
 * The EXECUTION layer, kept apart from opportunity/entry state. Confirmed
 * entry conditions never place an order; this link only records that a person
 * chose to act, or that an order exists (by id). No order placement happens in
 * ASI-1.
 */
sealed interface ExecutionLink {
    val rank: Int

    data object None : ExecutionLink {
        override val rank: Int get() = 0
    }

    data class UserActed(val at: Instant, val note: String = "") : ExecutionLink {
        override val rank: Int get() = 1
    }

    data class OrderPlaced(val orderId: String, val at: Instant) : ExecutionLink {
        init {
            require(orderId.isNotBlank()) { "orderId must not be blank" }
        }

        override val rank: Int get() = 2
    }
}
