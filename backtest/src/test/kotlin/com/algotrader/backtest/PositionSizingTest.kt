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

    @Test
    fun `lot size of one leaves sizing untouched`() {
        assertEquals(0.04, PositionSizing.PercentOfEquity(1.0).quantityFor(equity = 1_000.0, price = 250.0, lotSize = 1), 1e-9)
        assertEquals(3.0, PositionSizing.FixedQuantity(3.0).quantityFor(equity = 1_000.0, price = 250.0, lotSize = 1))
    }

    @Test
    fun `fixed lots multiplies lots by the instrument lot size`() {
        val sizing = PositionSizing.FixedLots(1)
        assertEquals(65.0, sizing.quantityFor(equity = 100_000.0, price = 22_000.0, lotSize = 65))
        assertEquals(130.0, PositionSizing.FixedLots(2).quantityFor(equity = 100_000.0, price = 22_000.0, lotSize = 65))
        // Lot size is instrument-specific, not hard-coded.
        assertEquals(30.0, sizing.quantityFor(equity = 100_000.0, price = 50_000.0, lotSize = 30))
    }

    @Test
    fun `percent of equity never produces a fractional lot`() {
        // 10,000,000 x 100% / 22,000 = 454.5 units -> 6 whole lots of 65 = 390.
        val quantity = PositionSizing.PercentOfEquity(100.0)
            .quantityFor(equity = 10_000_000.0, price = 22_000.0, lotSize = 65)
        assertEquals(390.0, quantity)
        assertEquals(0.0, quantity % 65.0)
    }

    @Test
    fun `less than one lot is zero not a fraction`() {
        // 1% of 100,000 at 22,000 would be ~0.045 units: far below one 65-unit lot.
        assertEquals(0.0, PositionSizing.PercentOfEquity(1.0).quantityFor(100_000.0, 22_000.0, lotSize = 65))
        assertEquals(0.0, PositionSizing.FixedQuantity(1.0).quantityFor(100_000.0, 22_000.0, lotSize = 65))
    }

    @Test
    fun `fixed quantity is rounded down to whole lots`() {
        assertEquals(130.0, PositionSizing.FixedQuantity(150.0).quantityFor(0.0, 22_000.0, lotSize = 65))
    }
}
