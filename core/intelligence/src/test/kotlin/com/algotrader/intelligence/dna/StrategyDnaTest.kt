package com.algotrader.intelligence.dna

import com.algotrader.domain.Instrument
import com.algotrader.intelligence.TestFixtures
import com.algotrader.intelligence.TestFixtures.leaf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StrategyDnaTest {

    @Test
    fun `price action strategy without any indicator is valid`() {
        val dna = TestFixtures.priceActionDna()
        assertTrue(dna.indicators.isEmpty())
        assertEquals(StrategyComposition.PRICE_ACTION_BASED, dna.composition)
    }

    @Test
    fun `composition distinguishes indicator, hybrid and rule based`() {
        val rsi = FeatureRef(FeatureDomain.INDICATOR, "RSI", mapOf("arg0" to ParamValue.Num(14.0)))
        val base = TestFixtures.priceActionDna()
        assertEquals(StrategyComposition.HYBRID, base.copy(indicators = listOf(rsi)).composition)
        assertEquals(
            StrategyComposition.INDICATOR_BASED,
            base.copy(indicators = listOf(rsi), structures = emptyList()).composition
        )
        assertEquals(StrategyComposition.RULE_BASED, base.copy(structures = emptyList()).composition)
    }

    @Test
    fun `entry and exit are required rule trees with and-or structure`() {
        val dna = TestFixtures.priceActionDna()
        assertEquals(2, dna.entry.leaves().size)
        assertEquals(2, dna.exit.leaves().size)
        assertTrue(dna.entry is Condition.AllOf)
        assertTrue(dna.exit is Condition.AnyOf)
    }

    @Test
    fun `index may be the signal source but never the trade target`() {
        val scope = MarketScope(TestFixtures.niftyIndex)
        assertFalse(scope.hasTradeTarget)
        assertFailsWith<IllegalArgumentException> {
            MarketScope(TestFixtures.niftyIndex, tradeTarget = TestFixtures.niftyIndex)
        }
        assertTrue(MarketScope(TestFixtures.niftyIndex, TestFixtures.niftyFuture).hasTradeTarget)
    }

    @Test
    fun `derivatives require an underlying`() {
        assertFailsWith<IllegalArgumentException> {
            InstrumentRef(Instrument("X", "NSE"), InstrumentKind.FUTURES)
        }
        assertFailsWith<IllegalArgumentException> {
            InstrumentRef(Instrument("X", "NSE"), InstrumentKind.OPTION, underlying = " ")
        }
        InstrumentRef(Instrument("RELIANCE", "NSE"), InstrumentKind.EQUITY)
    }

    @Test
    fun `structural invariants are enforced`() {
        val base = TestFixtures.priceActionDna()
        assertFailsWith<IllegalArgumentException> { base.copy(sides = emptySet()) }
        assertFailsWith<IllegalArgumentException> { base.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { base.copy(version = 0) }
        assertFailsWith<IllegalArgumentException> { Condition.AllOf(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Condition.AnyOf(emptyList()) }
        // indicators / structures must carry the right domain
        assertFailsWith<IllegalArgumentException> {
            base.copy(indicators = listOf(FeatureRef(FeatureDomain.PRICE_ACTION, "x")))
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(structures = listOf(FeatureRef(FeatureDomain.INDICATOR, "RSI")))
        }
    }

    @Test
    fun `condition ids must be unique across the whole dna`() {
        val base = TestFixtures.priceActionDna()
        assertFailsWith<IllegalArgumentException> {
            base.copy(exit = leaf("neckline_break")) // same id as an entry leaf
        }
    }

    @Test
    fun `evidence refs must be unique`() {
        val base = TestFixtures.priceActionDna()
        assertFailsWith<IllegalArgumentException> {
            base.copy(evidenceRefs = listOf(EvidenceRef("e1"), EvidenceRef("e1")))
        }
        assertEquals(1, base.copy(evidenceRefs = listOf(EvidenceRef("e1"))).evidenceRefs.size)
    }

    @Test
    fun `risk parameters are positive when set and null when unspecified`() {
        assertTrue(RiskSpec().stopLossPercent == null)
        assertFailsWith<IllegalArgumentException> { RiskSpec(stopLossPercent = 0.0) }
        assertFailsWith<IllegalArgumentException> { RiskSpec(takeProfitPercent = -1.0) }
        assertFailsWith<IllegalArgumentException> { RiskSpec(trailingStopPercent = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { RiskSpec(maxHoldBars = 0) }
        assertEquals(1.5, RiskSpec(stopLossPercent = 1.5).stopLossPercent)
    }

    @Test
    fun `strategy ref pins id and version`() {
        assertFailsWith<IllegalArgumentException> { StrategyRef(StrategyId("a"), 0) }
        assertFailsWith<IllegalArgumentException> { StrategyId(" ") }
        assertNotEquals(StrategyRef(StrategyId("a"), 1), StrategyRef(StrategyId("a"), 2))
    }

    @Test
    fun `next version keeps identity, links lineage and drops old evidence refs`() {
        val v1 = TestFixtures.priceActionDna().copy(evidenceRefs = listOf(EvidenceRef("e1")))
        val v2 = v1.nextVersion()

        assertEquals(v1.id, v2.id)
        assertEquals(2, v2.version)
        assertEquals(v1.ref, v2.derivedFrom)
        assertTrue(v2.evidenceRefs.isEmpty())
        // v1 is untouched
        assertEquals(1, v1.version)
        assertEquals(listOf(EvidenceRef("e1")), v1.evidenceRefs)
        assertNotEquals(v1.ref, v2.ref)
    }

    @Test
    fun `a version cannot derive from the same or a later version of itself`() {
        val v1 = TestFixtures.priceActionDna()
        assertFailsWith<IllegalArgumentException> { v1.copy(derivedFrom = v1.ref) }
        assertFailsWith<IllegalArgumentException> {
            v1.copy(derivedFrom = StrategyRef(v1.id, 5))
        }
        // deriving from a different strategy id is allowed (a fork)
        v1.copy(derivedFrom = StrategyRef(StrategyId("other"), 3))
    }
}
