package com.algotrader.intelligence.setup

import com.algotrader.intelligence.TestFixtures.T0
import com.algotrader.intelligence.TestFixtures.liveEvidence
import com.algotrader.intelligence.confidence.ConfidenceScale
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidencePolarity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupConfidenceCalculatorTest {

    private val strategy = StrategyRef(StrategyId("test-strategy"), 1)

    private fun evidence(
        id: String,
        kind: EvidenceKind,
        polarity: EvidencePolarity
    ): EvidenceItem =
        liveEvidence(id, strategy, kind).copy(polarity = polarity)

    @Test
    fun `all four supporting categories produce maximum confidence`() {
        val ledger = EvidenceLedger(
            listOf(
                evidence("pattern", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.SUPPORTING),
                evidence("trend", EvidenceKind.TREND_ALIGNMENT, EvidencePolarity.SUPPORTING),
                evidence("volatility", EvidenceKind.VOLATILITY_CONDITION, EvidencePolarity.SUPPORTING),
                evidence("retest", EvidenceKind.BREAKOUT_RETEST_CONFIRMATION, EvidencePolarity.SUPPORTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(ledger, T0)

        assertEquals(1.0, reading?.value)
        assertEquals(T0, reading?.at)
        assertTrue(ConfidenceScale.isValid(reading!!.value))
    }

    @Test
    fun `all four contradicting categories produce zero confidence`() {
        val ledger = EvidenceLedger(
            listOf(
                evidence("pattern", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.CONTRADICTING),
                evidence("trend", EvidenceKind.TREND_ALIGNMENT, EvidencePolarity.CONTRADICTING),
                evidence("volatility", EvidenceKind.VOLATILITY_CONDITION, EvidencePolarity.CONTRADICTING),
                evidence("retest", EvidenceKind.BREAKOUT_RETEST_CONFIRMATION, EvidencePolarity.CONTRADICTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(ledger, T0)

        assertEquals(0.0, reading?.value)
    }

    @Test
    fun `balanced support and contradiction produces midpoint`() {
        val ledger = EvidenceLedger(
            listOf(
                evidence("pattern", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.SUPPORTING),
                evidence("trend", EvidenceKind.TREND_ALIGNMENT, EvidencePolarity.CONTRADICTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(ledger, T0)

        assertEquals(0.5, reading?.value)
    }

    @Test
    fun `missing categories are excluded rather than treated as contradictions`() {
        val ledger = EvidenceLedger(
            listOf(
                evidence("trend", EvidenceKind.TREND_ALIGNMENT, EvidencePolarity.SUPPORTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(ledger, T0)

        assertEquals(1.0, reading?.value)
    }

    @Test
    fun `multiple items in one category are averaged before category weighting`() {
        val ledger = EvidenceLedger(
            listOf(
                evidence("pattern-1", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.SUPPORTING),
                evidence("pattern-2", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.SUPPORTING),
                evidence("pattern-3", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.CONTRADICTING),
                evidence("trend", EvidenceKind.TREND_ALIGNMENT, EvidencePolarity.CONTRADICTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(ledger, T0)

        // Pattern category = (1 + 1 - 1) / 3 = 1/3.
        // Trend category = -1.
        // Normalized net = ((1/3) + (-1)) / 2 = -1/3.
        // Confidence = 0.5 + 0.5 * (-1/3) = 1/3.
        assertEquals(1.0 / 3.0, reading?.value ?: Double.NaN, 1e-9)
    }

    @Test
    fun `unsupported live evidence kinds do not affect confidence`() {
        val ledger = EvidenceLedger(
            listOf(
                evidence("pattern", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.SUPPORTING),
                evidence("custom", EvidenceKind.CUSTOM_LIVE, EvidencePolarity.CONTRADICTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(ledger, T0)

        assertEquals(1.0, reading?.value)
    }

    @Test
    fun `empty evidence produces no confidence reading`() {
        val reading = SetupConfidenceCalculator.calculate(
            EvidenceLedger(),
            T0
        )

        assertNull(reading)
    }

    @Test
    fun `custom policy weights categories explicitly`() {
        val policy = SetupConfidencePolicy(
            weights = linkedMapOf(
                EvidenceKind.CURRENT_PATTERN_MATCH to 3.0,
                EvidenceKind.TREND_ALIGNMENT to 1.0
            )
        )

        val ledger = EvidenceLedger(
            listOf(
                evidence("pattern", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.SUPPORTING),
                evidence("trend", EvidenceKind.TREND_ALIGNMENT, EvidencePolarity.CONTRADICTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(
            evidence = ledger,
            asOf = T0,
            policy = policy
        )

        // (3 * +1 + 1 * -1) / 4 = +0.5
        // 0.5 + 0.5 * 0.5 = 0.75
        assertEquals(0.75, reading?.value)
    }

    @Test
    fun `result remains on the shared zero to one confidence scale`() {
        val ledger = EvidenceLedger(
            listOf(
                evidence("pattern", EvidenceKind.CURRENT_PATTERN_MATCH, EvidencePolarity.SUPPORTING),
                evidence("trend", EvidenceKind.TREND_ALIGNMENT, EvidencePolarity.CONTRADICTING),
                evidence("volatility", EvidenceKind.VOLATILITY_CONDITION, EvidencePolarity.SUPPORTING),
                evidence("retest", EvidenceKind.BREAKOUT_RETEST_CONFIRMATION, EvidencePolarity.CONTRADICTING)
            )
        )

        val reading = SetupConfidenceCalculator.calculate(ledger, T0)

        assertTrue(reading != null)
        assertTrue(ConfidenceScale.isValid(reading.value))
        assertEquals(0.5, reading.value)
    }
}
