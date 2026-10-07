package com.algotrader.intelligence.structure

import com.algotrader.intelligence.structure.StructureFixtures.fromMids
import com.algotrader.intelligence.structure.StructureFixtures.tight
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketStructureTest {

    private fun labels(mids: DoubleArray, type: PivotType, config: StructureConfig = tight) =
        PriceActionStructure.analyze(fromMids(mids.toList()), config).pivots
            .filter { it.pivot.type == type }.map { it.label }

    private fun state(vararg mids: Double) =
        PriceActionStructure.analyze(fromMids(*mids), tight).state

    @Test
    fun `higher highs and higher lows form an uptrend`() {
        val mids = doubleArrayOf(10.0, 20.0, 12.0, 25.0, 15.0, 30.0, 18.0)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.HH, StructureLabel.HH), labels(mids, PivotType.HIGH))
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.HL), labels(mids, PivotType.LOW))
        assertEquals(MarketStructureState.UPTREND, state(*mids))
    }

    @Test
    fun `lower highs and lower lows form a downtrend`() {
        val mids = doubleArrayOf(30.0, 20.0, 28.0, 15.0, 24.0, 10.0, 20.0)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.LL, StructureLabel.LL), labels(mids, PivotType.LOW))
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.LH), labels(mids, PivotType.HIGH))
        assertEquals(MarketStructureState.DOWNTREND, state(*mids))
    }

    @Test
    fun `expansion - higher highs with lower lows - is a range not a trend`() {
        val mids = doubleArrayOf(15.0, 20.0, 12.0, 25.0, 5.0, 30.0, 10.0)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.HH, StructureLabel.HH), labels(mids, PivotType.HIGH))
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.LL), labels(mids, PivotType.LOW))
        assertEquals(MarketStructureState.RANGE, state(*mids))
    }

    @Test
    fun `contraction - lower highs with higher lows - is a range`() {
        val mids = doubleArrayOf(10.0, 30.0, 5.0, 25.0, 10.0, 20.0, 15.0, 18.0, 16.0)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.LH, StructureLabel.LH, StructureLabel.LH), labels(mids, PivotType.HIGH))
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.HL, StructureLabel.HL), labels(mids, PivotType.LOW))
        assertEquals(MarketStructureState.RANGE, state(*mids))
    }

    @Test
    fun `exactly equal prices are labelled EQH and EQL`() {
        val mids = doubleArrayOf(10.0, 20.0, 12.0, 20.0, 12.0, 20.0, 13.0)
        assertEquals(StructureLabel.EQH, labels(mids, PivotType.HIGH)[1])
        assertEquals(StructureLabel.EQL, labels(mids, PivotType.LOW)[1])
        assertEquals(MarketStructureState.RANGE, state(*mids))
    }

    @Test
    fun `equality tolerance turns a tiny rise into EQH only when configured`() {
        val mids = doubleArrayOf(90.0, 100.0, 92.0, 100.1, 92.0)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.HH), labels(mids, PivotType.HIGH))
        val tolerant = tight.copy(equalityTolerance = 0.01)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.EQH), labels(mids, PivotType.HIGH, tolerant))
    }

    @Test
    fun `the first pivot of each type has no comparison and is FIRST`() {
        val r = PriceActionStructure.analyze(fromMids(10.0, 20.0, 12.0, 15.0), tight)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.FIRST), r.pivots.map { it.label })
        assertEquals(MarketStructureState.UNDEFINED, r.state)
    }

    @Test
    fun `state stays undefined until both a labelled high and a labelled low exist`() {
        // second high is HH but the only low is still FIRST
        val r = PriceActionStructure.analyze(fromMids(10.0, 20.0, 15.0, 25.0, 18.0), tight)
        assertEquals(listOf(StructureLabel.FIRST, StructureLabel.FIRST, StructureLabel.HH), r.pivots.map { it.label })
        assertEquals(MarketStructureState.UNDEFINED, r.state)
    }

    @Test
    fun `state follows the most recent label of each type`() {
        // uptrend, then a lower high and lower low arrive
        val up = doubleArrayOf(10.0, 20.0, 12.0, 25.0, 15.0, 30.0, 18.0)
        assertEquals(MarketStructureState.UPTREND, state(*up))
        val turned = up + doubleArrayOf(26.0, 12.0, 20.0)
        val r = PriceActionStructure.analyze(fromMids(*turned), tight)
        assertEquals(StructureLabel.LH, r.pivots.last { it.pivot.type == PivotType.HIGH }.label)
        assertEquals(StructureLabel.LL, r.pivots.last { it.pivot.type == PivotType.LOW }.label)
        assertEquals(MarketStructureState.DOWNTREND, r.state)
    }

    @Test
    fun `labels never use later pivots - prefix labels equal full labels`() {
        val candles = StructureFixtures.walk(150)
        val full = PriceActionStructure.analyze(candles, StructureConfig()).pivots
        for (k in 5 until candles.size step 7) {
            val prefix = PriceActionStructure.analyze(candles.subList(0, k + 1), StructureConfig()).pivots
            assertEquals(full.take(prefix.size), prefix)
        }
    }
}
