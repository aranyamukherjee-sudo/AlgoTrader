package com.algotrader.intelligence.evidence

import com.algotrader.intelligence.TestFixtures
import com.algotrader.intelligence.dna.EvidenceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceTest {

    private val ref = TestFixtures.priceActionDna().ref

    @Test
    fun `evidence must name a real source`() {
        assertFailsWith<IllegalArgumentException> { EvidenceSource(EvidenceSourceType.BACKTEST_RUN, " ") }
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.researchEvidence("e1", ref).copy(summary = "")
        }
    }

    @Test
    fun `research and live evidence use separate samples`() {
        // live kind cannot claim a historical sample
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.liveEvidence("l1", ref).copy(sample = EvidenceSample.FULL_PERIOD)
        }
        // research kind cannot claim to be live
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.researchEvidence("r1", ref).copy(sample = EvidenceSample.LIVE)
        }
        assertEquals(EvidenceScope.LIVE, TestFixtures.liveEvidence("l1", ref).scope)
        assertEquals(EvidenceScope.RESEARCH, TestFixtures.researchEvidence("r1", ref).scope)
    }

    @Test
    fun `every kind maps to a scope matching the spec examples`() {
        val research = listOf(
            EvidenceKind.HISTORICAL_EDGE, EvidenceKind.OCCURRENCE_COUNT, EvidenceKind.PROFIT_FACTOR,
            EvidenceKind.DRAWDOWN, EvidenceKind.CONSISTENCY, EvidenceKind.OUT_OF_SAMPLE_PERFORMANCE,
            EvidenceKind.CROSS_CONTRACT_PERFORMANCE, EvidenceKind.COST_SENSITIVITY
        )
        val live = listOf(
            EvidenceKind.CURRENT_PATTERN_MATCH, EvidenceKind.TREND_ALIGNMENT,
            EvidenceKind.VOLATILITY_CONDITION, EvidenceKind.BREAKOUT_RETEST_CONFIRMATION
        )
        assertTrue(research.all { it.scope == EvidenceScope.RESEARCH })
        assertTrue(live.all { it.scope == EvidenceScope.LIVE })
    }

    @Test
    fun `numeric values must be finite and counts non negative`() {
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.researchEvidence("e1", ref).copy(value = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.researchEvidence("e1", ref).copy(observations = -1)
        }
        val ok = TestFixtures.researchEvidence("e1", ref).copy(
            value = 1.8, unit = EvidenceUnit.RATIO, observations = 42
        )
        assertEquals(1.8, ok.value)
    }

    @Test
    fun `ledger is append only, unique and queryable`() {
        val supporting = TestFixtures.researchEvidence("e1", ref)
        val contradicting = TestFixtures.researchEvidence(
            "e2", ref, EvidenceKind.COST_SENSITIVITY, EvidenceSample.FULL_PERIOD, EvidencePolarity.CONTRADICTING
        )
        val ledger = EvidenceLedger().add(supporting).add(contradicting)

        assertFalse(ledger.isEmpty)
        assertEquals(listOf(supporting), ledger.supporting())
        assertEquals(listOf(contradicting), ledger.contradicting())
        assertTrue(ledger.hasSample(EvidenceSample.OUT_OF_SAMPLE))
        assertFalse(ledger.hasSample(EvidenceSample.IN_SAMPLE))
        assertEquals(listOf(EvidenceRef("e1"), EvidenceRef("e2")), ledger.refs())
        assertFailsWith<IllegalArgumentException> { ledger.add(supporting) }
    }
}
