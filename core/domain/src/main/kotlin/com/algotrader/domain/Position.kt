package com.algotrader.domain

data class Position(
    val instrument: Instrument,
    val quantity: Double,
    val averagePrice: Double
) {
    val marketValue: Double
        get() = quantity * averagePrice
}
