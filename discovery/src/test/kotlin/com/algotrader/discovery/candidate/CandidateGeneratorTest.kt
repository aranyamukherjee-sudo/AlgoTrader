package com.algotrader.discovery.candidate

import com.algotrader.discovery.features.FeatureRegistry
import com.algotrader.discovery.features.FeatureTypes
import com.algotrader.discovery.fixtures.DiscoveryFixtures
import com.algotrader.discovery.reproducibility.DnaCanonical
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.RiskSpec
import com.algotrader.intelligence.dna.StrategyComposition
import com.algotrader.intelligence.dna.StrategyOrigin
import com.algotrader.intelligence.dna.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CandidateGeneratorTest {

    private val generator = BreakoutCandidateGenerator()
    private val market = DiscoveryFixtures.futuresMarket
    private val dnas = generator.generate(market, Timeframe.MINUTE_15)

    @Test
    fun `the default grammar generates 48 distinct candidates`() {
        // 3 breakout lookbacks x 2 exit lookbacks x contraction x trend x expansion (each on/off)
        assertEquals(48, dnas.size)
        assertEquals(48, dnas.map { it.id }.toSet().size)
        assertEquals(48, dnas.map { DnaCanonical.key(it) }.toSet().size)
    }

    @Test
    fun `candidates are ordinary strategy dna`() {
        for (dna in dnas) {
            assertEquals(StrategyOrigin.DISCOVERED, dna.origin)
            assertEquals(setOf(TradeSide.LONG), dna.sides)
            assertEquals(market, dna.market)
            assertEquals(Timeframe.MINUTE_15, dna.timeframe)
            assertEquals(1, dna.version)
            assertTrue(dna.indicators.isEmpty()) // price-action based, not indicator based
            assertEquals(StrategyComposition.PRICE_ACTION_BASED, dna.composition)
            assertTrue(dna.entry is Condition.Leaf)
            assertTrue(dna.exit is Condition.Leaf)
            assertTrue(dna.risk == RiskSpec()) // the engine cannot simulate stops yet, so none are claimed
            assertTrue(dna.evidenceRefs.isEmpty()) // generation attaches no evidence
            assertTrue(Regex("disc\\.[0-9a-f]{12}").matches(dna.id.value), dna.id.value)
        }
    }

    @Test
    fun `every feature a candidate uses is evaluable by the standard registry`() {
        val registry = FeatureRegistry.standard()
        assertTrue(dnas.all { registry.unknownFeatures(it).isEmpty() })
        val types = dnas.flatMap { listOfNotNull(it.requiredContext, it.entry, it.confirmation, it.exit) }
            .flatMap { it.leaves() }.map { it.feature.type }.toSet()
        assertEquals(
            setOf(
                FeatureTypes.RANGE_BREAKOUT, FeatureTypes.MA_RELATION,
                FeatureTypes.RANGE_CONTRACTION, FeatureTypes.RANGE_EXPANSION
            ),
            types
        )
    }

    @Test
    fun `optional components appear exactly when the grammar says`() {
        // no trend and no contraction -> no required context: 3 x 2 x 2 (expansion on/off) = 12
        assertEquals(12, dnas.count { it.requiredContext == null })
        assertEquals(24, dnas.count { it.confirmation == null })
        val full = dnas.first { it.name == "Breakout 20 / exit 10 (trend+contraction+expansion)" }
        assertEquals(2, full.requiredContext!!.leaves().size)
        assertTrue(full.confirmation != null)
    }

    @Test
    fun `generation is deterministic`() {
        val again = BreakoutCandidateGenerator().generate(market, Timeframe.MINUTE_15)
        assertEquals(dnas, again)
        assertEquals(dnas.map { it.id }, again.map { it.id })
    }

    @Test
    fun `identical rules on a different market or timeframe are a different candidate`() {
        val other = generator.generate(DiscoveryFixtures.indexOnlyMarket, Timeframe.MINUTE_15)
        assertTrue(dnas.map { it.id }.intersect(other.map { it.id }.toSet()).isEmpty())
        val otherTf = generator.generate(market, Timeframe.HOUR_1)
        assertTrue(dnas.map { it.id }.intersect(otherTf.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `canonical key describes behaviour, not labels`() {
        val dna = dnas.first()
        assertEquals(DnaCanonical.key(dna), DnaCanonical.key(dna.copy(name = "renamed", description = "other")))
        assertNotEquals(DnaCanonical.key(dna), DnaCanonical.key(dna.copy(risk = RiskSpec(stopLossPercent = 1.0))))
    }

    @Test
    fun `any generator that produces dna can plug in`() {
        val custom = CandidateGenerator { _, _ -> dnas.take(2) }
        assertEquals(2, custom.generate(market, Timeframe.MINUTE_15).size)
    }

    @Test
    fun `grammar parameters are validated`() {
        assertFailsWith<IllegalArgumentException> { BreakoutCandidateGenerator(breakoutLookbacks = emptyList()) }
        assertFailsWith<IllegalArgumentException> { BreakoutCandidateGenerator(exitLookbacks = listOf(0)) }
        assertFailsWith<IllegalArgumentException> { BreakoutCandidateGenerator(breakoutLookbacks = listOf(10, 10)) }
        assertEquals(1 * 1 * 8, BreakoutCandidateGenerator(listOf(10), listOf(5)).generate(market, Timeframe.MINUTE_15).size)
    }
}
