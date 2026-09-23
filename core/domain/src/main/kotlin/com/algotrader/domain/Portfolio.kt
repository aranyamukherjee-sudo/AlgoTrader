package com.algotrader.domain

data class Portfolio(
    val cash: Double,
    val positions: List<Position> = emptyList()
) {
    val investedValue: Double
        get() = positions.sumOf { it.marketValue }

    val totalValue: Double
        get() = cash + investedValue
}
