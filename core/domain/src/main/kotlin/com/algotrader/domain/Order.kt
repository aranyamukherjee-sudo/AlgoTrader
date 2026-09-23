package com.algotrader.domain

import java.time.Instant

enum class OrderSide {
    BUY,
    SELL
}

enum class OrderType {
    MARKET,
    LIMIT,
    STOP
}

enum class OrderStatus {
    PENDING,
    FILLED,
    PARTIALLY_FILLED,
    CANCELLED,
    REJECTED
}

data class Order(
    val id: String,
    val instrument: Instrument,
    val side: OrderSide,
    val type: OrderType,
    val quantity: Double,
    val price: Double? = null,
    val timestamp: Instant = Instant.now(),
    val status: OrderStatus = OrderStatus.PENDING
)
