package com.algotrader.backtest

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionSizingTest {

    @Test
    fun `fixed quantity ignores equity and price`() {
        val sizing = PositionSizing.FixedQuantity(3.0)
        assertEquals(3.0, sizing.quantityFor(equity = 500.0, price = 17.0))
        assertEquals(3.0, sizing.quantityFor(equity = 1_000_000.0, price = 1.0))
    }

    @Test
    fun `percent of equity divides the allotted cash by price`() {
        val sizing = PositionSizing.PercentOfEquity(10.0)
        assertEquals(20.0, sizing.quantityFor(equity = 10_000.0, price = 50.0), 1e-9)
    }

    @Test
    fun `percent of equity is zero at a non-positive price`() {
        val sizing = PositionSizing.PercentOfEquity(10.0)
        assertEquals(0.0, sizing.quantityFor(equity = 10_000.0, price = 0.0))
    }
}
