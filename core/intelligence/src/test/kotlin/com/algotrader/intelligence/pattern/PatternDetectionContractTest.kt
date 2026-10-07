package com.algotrader.intelligence.pattern

import com.algotrader.domain.Candle
import com.algotrader.intelligence.pattern.PatternFixtures.cfg
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.PriceActionStructure
import com.algotrader.intelligence.structure.StructureConfig
import com.algotrader.intelligence.structure.StructureFixtures
import com.algotrader.intelligence.structure.StructureFixtures.at
import com.algotrader.intelligence.structure.StructureFixtures.candle
import com.algotrader.intelligence.structure.StructureFixtures.fromMids
import com.algotrader.intelligence.structure.StructureFixtures.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatternDetectionContractTest {
    private val walkCfg = PatternConfig(
        structure = StructureConfig(left = 2, right = 2),
        levelTolerance = 0.02, minDepth = 0.001, poleMinMove = 0.005
    )

    private fun candlesOf(mids: DoubleArray) = fromMids(mids.toList())

    // ---- invalid / insufficient input ----

    @Test
    fun `invalid input is reported with the structure message and no patterns`() {
        val c = candlesOf(PatternFixtures.range).toMutableList()
        c[3] = c[3].copy(timestamp = c[2].timestamp)
        val r = PatternDetector.detect(c, cfg)
        assertEquals(AnalysisStatus.INVALID_INPUT, r.status)
        assertTrue(r.patterns.isEmpty() && r.message.contains("duplicate"))
        val back = candlesOf(PatternFixtures.range).toMutableList()
        back[4] = back[4].copy(timestamp = at(0))
        assertEquals(AnalysisStatus.INVALID_INPUT, PatternDetector.detect(back, cfg).status)
        val nan = candlesOf(PatternFixtures.range).toMutableList()
        nan[2] = nan[2].copy(high = Double.NaN)
        assertEquals(AnalysisStatus.INVALID_INPUT, PatternDetector.detect(nan, cfg).status)
    }

    @Test
    fun `too few candles is insufficient data, not an empty success`() {
        assertEquals(AnalysisStatus.INSUFFICIENT_DATA, PatternDetector.detect(emptyList<Candle>(), cfg).status)
        val r = PatternDetector.detect(candlesOf(PatternFixtures.doubleTop).take(2), cfg)
        assertEquals(AnalysisStatus.INSUFFICIENT_DATA, r.status)
        assertEquals(2, r.candleCount)
        assertTrue(r.patterns.isEmpty())
    }

    @Test
    fun `enough candles but no pattern is OK and empty`() {
        val r = PatternDetector.detect(fromMids(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), cfg)
        assertEquals(AnalysisStatus.OK, r.status)
        assertTrue(r.patterns.isEmpty())
    }

    @Test
    fun `a non-OK structure analysis is passed through unchanged`() {
        val s = PriceActionStructure.analyze(candlesOf(PatternFixtures.doubleTop).take(2), cfg.structure)
        val r = PatternDetector.detect(s, cfg)
        assertEquals(s.status, r.status)
        assertEquals(s.message, r.message)
    }

    @Test
    fun `detect from candles equals detect from the same structure analysis`() {
        val candles = walk(150)
        val viaCandles = PatternDetector.detect(candles, walkCfg)
        val viaStructure = PatternDetector.detect(PriceActionStructure.analyze(candles, walkCfg.structure), walkCfg)
        assertEquals(viaCandles, viaStructure)
    }

    @Test
    fun `configuration errors throw`() {
        assertFailsWith<IllegalArgumentException> { PatternConfig(levelTolerance = -0.1) }
        assertFailsWith<IllegalArgumentException> { PatternConfig(levelTolerance = 1.0) }
        assertFailsWith<IllegalArgumentException> { PatternConfig(minDepth = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { PatternConfig(maxSecondExtremeRatio = 0.0) }
        assertFailsWith<IllegalArgumentException> { PatternConfig(maxSecondExtremeRatio = 1.5) }
        assertFailsWith<IllegalArgumentException> { PatternConfig(poleMinMove = 0.0) }
        assertFailsWith<IllegalArgumentException> { PatternConfig(maxFlagRetrace = 1.01) }
    }

    // ---- confirmation / no look-ahead / immutability ----

    @Test
    fun `confirmation index is the last member pivot's confirmation and never precedes any member`() {
        val r = PatternDetector.detect(walk(300), walkCfg)
        assertTrue(r.patterns.isNotEmpty(), "walk fixture should produce patterns")
        for (p in r.patterns) {
            assertEquals(p.pivots.maxOf { it.confirmedIndex }, p.confirmedIndex)
            assertTrue(p.confirmedIndex >= p.endIndex + walkCfg.structure.right)
            assertTrue(p.pivots.all { it.confirmedIndex <= p.confirmedIndex })
            assertEquals(p.pivots.last().confirmedAt, p.confirmedAt)
        }
    }

    @Test
    fun `prefix consistency - every prefix equals the full result filtered by confirmation index`() {
        val candles = walk(160)
        val full = PatternDetector.detect(candles, walkCfg).patterns
        assertTrue(full.isNotEmpty())
        for (k in walkCfg.structure.minCandles - 1 until candles.size) {
            val prefix = PatternDetector.detect(candles.subList(0, k + 1), walkCfg).patterns
            assertTrue(prefix.all { it.confirmedIndex <= k }, "prefix $k holds an unconfirmed pattern")
            assertEquals(full.filter { it.confirmedIndex <= k }, prefix, "prefix $k differs")
        }
    }

    @Test
    fun `prefix consistency on every exact fixture`() {
        for (mids in PatternFixtures.all) {
            val candles = candlesOf(mids)
            val full = PatternDetector.detect(candles, cfg).patterns
            assertTrue(full.isNotEmpty())
            for (k in cfg.structure.minCandles - 1 until candles.size) {
                assertEquals(
                    full.filter { it.confirmedIndex <= k },
                    PatternDetector.detect(candles.subList(0, k + 1), cfg).patterns
                )
            }
        }
    }

    @Test
    fun `later candles cannot retroactively alter or remove a confirmed pattern`() {
        for (mids in PatternFixtures.all) {
            val candles = candlesOf(mids)
            val before = PatternDetector.detect(candles, cfg).patterns
            val lastIndex = candles.size - 1
            // violent follow-on candles of every kind
            val extension = listOf(500.0, 5.0, 900.0, 1.0, 250.0, 260.0, 255.0).mapIndexed { k, m ->
                candle(lastIndex + 1 + k, m + 1.0, m - 1.0)
            }
            val after = PatternDetector.detect(candles + extension, cfg).patterns
            assertEquals(before, after.filter { it.confirmedIndex <= lastIndex })
        }
    }

    @Test
    fun `rewriting candles after a pattern's confirmation does not change it`() {
        val candles = walk(200)
        val full = PatternDetector.detect(candles, walkCfg).patterns
        val target = full.first { it.confirmedIndex in 40..120 }
        val cut = target.confirmedIndex
        val tampered = candles.take(cut + 1) + candles.drop(cut + 1).map {
            it.copy(high = it.high + 700.0, low = it.low + 600.0, close = it.close + 650.0, open = it.open + 650.0)
        }
        val after = PatternDetector.detect(tampered, walkCfg).patterns
        assertEquals(full.filter { it.confirmedIndex <= cut }, after.filter { it.confirmedIndex <= cut })
    }

    // ---- determinism / ordering ----

    @Test
    fun `results are ordered by confirmation, start, then type, and repeatable`() {
        val candles = walk(300)
        val first = PatternDetector.detect(candles, walkCfg)
        val snapshot = candles.toList()
        repeat(20) { assertEquals(first, PatternDetector.detect(candles, walkCfg)) }
        assertEquals(snapshot, candles)
        val ps = first.patterns
        for (i in 1 until ps.size) {
            val a = ps[i - 1]
            val b = ps[i]
            val ka = Triple(a.confirmedIndex, a.startIndex, a.type.ordinal)
            val kb = Triple(b.confirmedIndex, b.startIndex, b.type.ordinal)
            assertTrue(
                ka.first < kb.first || (ka.first == kb.first && (ka.second < kb.second ||
                    (ka.second == kb.second && ka.third <= kb.third))),
                "out of order at $i"
            )
        }
    }

    @Test
    fun `fresh equal candle objects give an equal result`() {
        assertEquals(PatternDetector.detect(walk(120, 7L), walkCfg), PatternDetector.detect(walk(120, 7L), walkCfg))
    }

    @Test
    fun `each exact fixture yields only its own family among the primary shapes`() {
        fun primary(mids: DoubleArray) = PatternDetector.detect(candlesOf(mids), cfg).patterns.map { it.type }.toSet()
        assertEquals(setOf(PatternType.DOUBLE_TOP), primary(PatternFixtures.doubleTop))
        assertEquals(setOf(PatternType.DOUBLE_BOTTOM), primary(PatternFixtures.doubleBottom))
        assertEquals(setOf(PatternType.M_TOP), primary(PatternFixtures.mTop))
        assertEquals(setOf(PatternType.W_BOTTOM), primary(PatternFixtures.wBottom))
        assertTrue(PatternType.RECTANGLE in primary(PatternFixtures.range))
        assertTrue(PatternType.ASCENDING_TRIANGLE in primary(PatternFixtures.ascending))
        assertTrue(PatternType.DESCENDING_TRIANGLE in primary(PatternFixtures.descending))
        assertTrue(PatternType.SYMMETRICAL_TRIANGLE in primary(PatternFixtures.symmetrical))
        assertTrue(PatternType.BULL_FLAG in primary(PatternFixtures.bullFlag))
        assertTrue(PatternType.BEAR_FLAG in primary(PatternFixtures.bearFlag))
    }

    @Test
    fun `a pattern record rejects inconsistent construction`() {
        val p = PatternDetector.detect(candlesOf(PatternFixtures.doubleTop), cfg).patterns.single()
        assertFailsWith<IllegalArgumentException> { p.copy(pivots = p.pivots.take(2)) }
        assertFailsWith<IllegalArgumentException> { p.copy(pivots = p.pivots.reversed()) }
        assertFailsWith<IllegalArgumentException> { p.copy(confirmedIndex = p.endIndex - 1) }
    }
}
