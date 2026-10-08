package com.algotrader.intelligence.setup

import com.algotrader.intelligence.TestFixtures
import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidenceSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SetupEvidenceModelTest {

    @Test
    fun `assessment accepts live evidence and existing confidence scale`() {
        val ref = TestFixtures.priceActionDna().ref
        val evidence = EvidenceLedger()
            .add(TestFixtures.liveEvidence("live-1", ref))

        val assessment = SetupEvidenceAssessment(
            breakoutId = BreakoutId("UP:100.0:b1"),
            asOfIndex = 12,
            asOf = TestFixtures.at(12),
            evidence = evidence,
            confidence = ConfidenceReading(
                value = 0.75,
                at = TestFixtures.at(12),
                reason = "deterministic setup assessment"
            )
        )

        assertEquals(1, assessment.evidenceCount)
        assertEquals(1, assessment.supportingEvidenceCount)
        assertEquals(0, assessment.contradictingEvidenceCount)
        assertEquals(0.75, assessment.confidence.value)
    }

    @Test
    fun `assessment rejects research evidence`() {
        val ref = TestFixtures.priceActionDna().ref
        val research = TestFixtures.researchEvidence("research-1", ref)

        assertFailsWith<IllegalArgumentException> {
            SetupEvidenceAssessment(
                breakoutId = BreakoutId("UP:100.0:b1"),
                asOfIndex = 12,
                asOf = TestFixtures.at(12),
                evidence = EvidenceLedger().add(research),
                confidence = ConfidenceReading(
                    value = 0.5,
                    at = TestFixtures.at(12)
                )
            )
        }
    }

    @Test
    fun `assessment rejects negative index`() {
        assertFailsWith<IllegalArgumentException> {
            SetupEvidenceAssessment(
                breakoutId = BreakoutId("UP:100.0:b1"),
                asOfIndex = -1,
                asOf = TestFixtures.at(0),
                evidence = EvidenceLedger(),
                confidence = ConfidenceReading(
                    value = 0.5,
                    at = TestFixtures.at(0)
                )
            )
        }
    }

    @Test
    fun `assessment counts supporting and contradicting evidence`() {
        val ref = TestFixtures.priceActionDna().ref
        val evidence = EvidenceLedger()
            .add(TestFixtures.liveEvidence("live-1", ref))
            .add(
                TestFixtures.liveEvidence(
                    "live-2",
                    ref,
                    kind = com.algotrader.intelligence.evidence.EvidenceKind.TREND_ALIGNMENT
                ).copy(
                    polarity = com.algotrader.intelligence.evidence.EvidencePolarity.CONTRADICTING
                )
            )

        val assessment = SetupEvidenceAssessment(
            breakoutId = BreakoutId("UP:100.0:b1"),
            asOfIndex = 12,
            asOf = TestFixtures.at(12),
            evidence = evidence,
            confidence = ConfidenceReading(0.5, TestFixtures.at(12))
        )

        assertEquals(2, assessment.evidenceCount)
        assertEquals(1, assessment.supportingEvidenceCount)
        assertEquals(1, assessment.contradictingEvidenceCount)
    }

    @Test
    fun `assessment preserves exact evidence and confidence objects`() {
        val ref = TestFixtures.priceActionDna().ref
        val live = TestFixtures.liveEvidence("live-1", ref)
        val ledger = EvidenceLedger().add(live)
        val confidence = ConfidenceReading(
            0.68,
            TestFixtures.at(12),
            "setup forming"
        )

        val assessment = SetupEvidenceAssessment(
            BreakoutId("UP:100.0:b1"),
            12,
            TestFixtures.at(12),
            ledger,
            confidence
        )

        assertEquals(ledger, assessment.evidence)
        assertEquals(confidence, assessment.confidence)
        assertEquals(EvidenceSample.LIVE, assessment.evidence.items.single().sample)
    }
}
