package com.algotrader.intelligence.structure

import com.algotrader.intelligence.structure.StructureFixtures.at
import com.algotrader.intelligence.structure.StructureFixtures.fromMids
import com.algotrader.intelligence.structure.StructureFixtures.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingPivotTest {
    private val cfg = StructureConfig(left = 2, right = 2)

    private fun pivots(candles: List<com.algotrader.domain.Candle>, config: StructureConfig = cfg) =
        PriceActionStructure.analyze(candles, config).pivots.map { it.pivot }

    @Test
    fun `obvious swing high and swing low are found with exact prices`() {
        val p = pivots(fromMids(10.0, 11.0, 15.0, 12.0, 11.0, 9.0, 6.0, 8.0, 10.0))
        assertEquals(2, p.size)
        assertEquals(PivotType.HIGH, p[0].type)
        assertEquals(2, p[0].index)
        assertEquals(15.5, p[0].price, 1e-9)
        assertEquals(PivotType.LOW, p[1].type)
        assertEquals(6, p[1].index)
        assertEquals(5.5, p[1].price, 1e-9)
    }

    @Test
    fun `a pivot is confirmed exactly right bars later and not before`() {
        val candles = fromMids(10.0, 11.0, 15.0, 12.0, 11.0, 9.0, 6.0, 8.0, 10.0)
        val high = pivots(candles).first()
        assertEquals(4, high.confirmedIndex)
        assertEquals(at(4), high.confirmedAt)
        assertEquals(at(2), high.timestamp)
        // 4 candles = indices 0..3: the pivot at 2 is NOT confirmable yet (needs index 4)
        assertTrue(pivots(candles.subList(0, 4)).isEmpty())
        // 5 candles = indices 0..4: now it is
        assertEquals(listOf(2), pivots(candles.subList(0, 5)).map { it.index })
    }

    @Test
    fun `a later higher bar after confirmation never removes or alters the confirmed pivot`() {
        val base = fromMids(10.0, 11.0, 15.0, 12.0, 11.0)
        val before = pivots(base).single()
        val extended = base + fromMids(10.0, 11.0, 15.0, 12.0, 11.0, 40.0, 50.0, 60.0).drop(5).mapIndexed { k, c ->
            c.copy(timestamp = at(5 + k))
        }
        val after = pivots(extended).first { it.index == 2 }
        assertEquals(before, after)
    }

    @Test
    fun `no look-ahead - every prefix equals the full result filtered by confirmation index`() {
        val candles = walk(120)
        val full = PriceActionStructure.analyze(candles, cfg).pivots
        assertTrue(full.isNotEmpty())
        for (k in cfg.minCandles - 1 until candles.size) {
            val prefix = PriceActionStructure.analyze(candles.subList(0, k + 1), cfg)
            assertTrue(prefix.pivots.all { it.pivot.confirmedIndex <= k }, "prefix $k holds an unconfirmed pivot")
            assertEquals(full.filter { it.pivot.confirmedIndex <= k }, prefix.pivots, "prefix $k differs")
        }
    }

    @Test
    fun `insufficient data is reported not guessed`() {
        assertEquals(AnalysisStatus.INSUFFICIENT_DATA, PriceActionStructure.analyze(emptyList(), cfg).status)
        val four = fromMids(10.0, 11.0, 15.0, 12.0)
        val r = PriceActionStructure.analyze(four, cfg)
        assertEquals(AnalysisStatus.INSUFFICIENT_DATA, r.status)
        assertTrue(r.pivots.isEmpty() && r.zones.isEmpty())
        assertEquals(MarketStructureState.UNDEFINED, r.state)
        assertEquals(4, r.candleCount)
        // exactly left + right + 1 candles is enough to evaluate (and here find nothing but be OK)
        assertEquals(AnalysisStatus.OK, PriceActionStructure.analyze(fromMids(10.0, 11.0, 15.0, 12.0, 11.0), cfg).status)
    }

    @Test
    fun `equal highs plateau yields exactly one pivot - the earliest of the run`() {
        val p = pivots(fromMids(10.0, 12.0, 15.0, 15.0, 12.0, 10.0, 9.0))
        val highs = p.filter { it.type == PivotType.HIGH }
        assertEquals(1, highs.size)
        assertEquals(2, highs.single().index)
    }

    @Test
    fun `equal lows plateau yields exactly one pivot - the earliest of the run`() {
        val p = pivots(fromMids(20.0, 18.0, 10.0, 10.0, 18.0, 20.0, 21.0))
        val lows = p.filter { it.type == PivotType.LOW }
        assertEquals(1, lows.size)
        assertEquals(2, lows.single().index)
    }

    @Test
    fun `a bar equal to a left neighbour is not a pivot`() {
        // idx2 high 15.5 ties the idx0 high 15.5 inside its left window
        val p = pivots(fromMids(15.0, 12.0, 15.0, 11.0, 10.0))
        assertTrue(p.none { it.type == PivotType.HIGH })
    }

    @Test
    fun `a right neighbour equal to the pivot does not cancel it`() {
        val p = pivots(fromMids(10.0, 12.0, 15.0, 15.0, 14.0, 10.0))
        assertEquals(2, p.single { it.type == PivotType.HIGH }.index)
    }

    @Test
    fun `monotonic series has no pivots but is a valid analysis`() {
        val r = PriceActionStructure.analyze(fromMids(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), cfg)
        assertEquals(AnalysisStatus.OK, r.status)
        assertTrue(r.pivots.isEmpty())
        assertEquals(MarketStructureState.UNDEFINED, r.state)
    }

    @Test
    fun `first left and last right bars can never be pivots`() {
        val p = pivots(walk(200))
        assertTrue(p.all { it.index >= cfg.left && it.index <= 199 - cfg.right })
    }

    @Test
    fun `an outside bar can be both a pivot high and a pivot low - high listed first`() {
        val candles = listOf(
            StructureFixtures.candle(0, 11.0, 9.0), StructureFixtures.candle(1, 12.0, 8.0),
            StructureFixtures.candle(2, 20.0, 0.0),
            StructureFixtures.candle(3, 12.0, 8.0), StructureFixtures.candle(4, 11.0, 9.0)
        )
        val p = pivots(candles)
        assertEquals(listOf(PivotType.HIGH, PivotType.LOW), p.map { it.type })
        assertEquals(listOf(2, 2), p.map { it.index })
    }

    @Test
    fun `strength is the strictly dominated left run and respects the cap`() {
        val candles = fromMids(1.0, 2.0, 3.0, 4.0, 5.0, 9.0, 4.0, 3.0)
        val high = pivots(candles).single { it.type == PivotType.HIGH }
        assertEquals(5, high.strength)
        val capped = pivots(candles, StructureConfig(left = 2, right = 2, maxStrength = 3))
            .single { it.type == PivotType.HIGH }
        assertEquals(3, capped.strength)
        assertTrue(high.strength >= cfg.left)
    }

    @Test
    fun `prominence is the swing depth inside the confirmation window`() {
        val high = pivots(fromMids(1.0, 2.0, 3.0, 4.0, 5.0, 9.0, 4.0, 3.0)).single { it.type == PivotType.HIGH }
        // high 9.5; left-window min low 3.5, right-window min low 2.5 -> 9.5 - max(3.5, 2.5)
        assertEquals(6.0, high.prominence, 1e-9)
        assertTrue(pivots(walk(150)).all { it.prominence >= 0.0 })
    }

    @Test
    fun `pivots are chronological and deterministic in order`() {
        val p = pivots(walk(300))
        assertTrue(p.isNotEmpty())
        for (i in 1 until p.size) {
            val a = p[i - 1]
            val b = p[i]
            assertTrue(a.index < b.index || (a.index == b.index && a.type == PivotType.HIGH && b.type == PivotType.LOW))
            assertTrue(a.confirmedIndex <= b.confirmedIndex)
        }
    }

    @Test
    fun `wider windows only remove pivots`() {
        val candles = walk(200)
        val narrow = pivots(candles, StructureConfig(left = 2, right = 2)).map { it.index to it.type }.toSet()
        val wide = pivots(candles, StructureConfig(left = 4, right = 4)).map { it.index to it.type }.toSet()
        assertTrue(narrow.containsAll(wide))
    }
}
