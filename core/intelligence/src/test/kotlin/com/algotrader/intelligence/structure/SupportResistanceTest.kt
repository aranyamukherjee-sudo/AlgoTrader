package com.algotrader.intelligence.structure

import com.algotrader.intelligence.structure.StructureFixtures.fromMids
import com.algotrader.intelligence.structure.StructureFixtures.pivot
import com.algotrader.intelligence.structure.StructureFixtures.tight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupportResistanceTest {
    private val cfg = StructureConfig(left = 1, right = 1, maxStrength = 5, zoneTolerance = 0.01)

    @Test
    fun `repeated pivot highs form a resistance zone and repeated lows a support zone`() {
        val r = PriceActionStructure.analyze(fromMids(10.0, 20.0, 12.0, 20.1, 12.0, 25.0, 14.0), cfg)
        assertEquals(2, r.zones.size)
        val support = r.zones[0]
        val resistance = r.zones[1]
        assertEquals(ZoneKind.SUPPORT, support.kind)
        assertEquals(11.5, support.low, 1e-9)
        assertEquals(11.5, support.high, 1e-9)
        assertEquals(2, support.touches)
        assertEquals(ZoneKind.RESISTANCE, resistance.kind)
        assertEquals(20.5, resistance.low, 1e-9)
        assertEquals(20.6, resistance.high, 1e-9)
        assertEquals(2, resistance.touches)
        assertEquals(1, resistance.firstIndex)
        assertEquals(3, resistance.lastIndex)
        assertEquals(4, resistance.lastConfirmedIndex)
    }

    @Test
    fun `a lone pivot is not a zone and minZoneTouches is honoured`() {
        val candles = fromMids(10.0, 20.0, 12.0, 20.1, 12.0, 25.0, 14.0)
        assertTrue(PriceActionStructure.analyze(candles, cfg.copy(zoneTolerance = 0.0001)).zones
            .none { it.kind == ZoneKind.RESISTANCE })
        assertTrue(PriceActionStructure.analyze(candles, cfg.copy(minZoneTouches = 3)).zones.isEmpty())
    }

    @Test
    fun `zero tolerance only joins exactly equal prices`() {
        val exact = PriceActionStructure.analyze(
            fromMids(10.0, 20.0, 12.0, 20.0, 12.0, 25.0, 14.0), cfg.copy(zoneTolerance = 0.0)
        )
        assertEquals(listOf(ZoneKind.SUPPORT, ZoneKind.RESISTANCE), exact.zones.map { it.kind })
        val near = PriceActionStructure.analyze(
            fromMids(10.0, 20.0, 12.0, 20.1, 12.0, 25.0, 14.0), cfg.copy(zoneTolerance = 0.0)
        )
        assertTrue(near.zones.none { it.kind == ZoneKind.RESISTANCE })
    }

    @Test
    fun `clusters are anchored on the lowest price and do not chain`() {
        // anchor 100, tolerance 0.2% = 0.2: 100.15 joins, 100.3 would only join by chaining through 100.15
        val pivots = listOf(
            pivot(PivotType.HIGH, 1, 100.0), pivot(PivotType.HIGH, 3, 100.15), pivot(PivotType.HIGH, 5, 100.3)
        )
        val zones = SupportResistance.zones(pivots, StructureConfig(zoneTolerance = 0.002))
        assertEquals(1, zones.size)
        assertEquals(100.0, zones[0].low, 1e-9)
        assertEquals(100.15, zones[0].high, 1e-9)
        assertEquals(2, zones[0].touches)
    }

    @Test
    fun `zone output does not depend on the order pivots are supplied`() {
        val pivots = listOf(
            pivot(PivotType.HIGH, 1, 50.0), pivot(PivotType.LOW, 2, 40.0), pivot(PivotType.HIGH, 3, 50.1),
            pivot(PivotType.LOW, 4, 40.0), pivot(PivotType.HIGH, 5, 70.0), pivot(PivotType.HIGH, 7, 70.2)
        )
        val config = StructureConfig(zoneTolerance = 0.01)
        val expected = SupportResistance.zones(pivots, config)
        assertEquals(3, expected.size)
        assertEquals(expected.sortedWith(compareBy({ it.low }, { it.high }, { it.kind })), expected)
        assertEquals(expected, SupportResistance.zones(pivots.reversed(), config))
        assertEquals(expected, SupportResistance.zones(pivots.shuffled(java.util.Random(7)), config))
    }

    @Test
    fun `a support zone and a resistance zone may overlap and are both kept`() {
        val pivots = listOf(
            pivot(PivotType.HIGH, 1, 100.0), pivot(PivotType.HIGH, 3, 100.0),
            pivot(PivotType.LOW, 2, 100.0), pivot(PivotType.LOW, 4, 100.0)
        )
        val zones = SupportResistance.zones(pivots, StructureConfig())
        assertEquals(listOf(ZoneKind.SUPPORT, ZoneKind.RESISTANCE), zones.map { it.kind })
    }

    @Test
    fun `no zones without pivots`() {
        assertTrue(SupportResistance.zones(emptyList(), tight).isEmpty())
    }
}
