package com.algotrader.intelligence.lifecycle

import com.algotrader.intelligence.TestFixtures
import com.algotrader.intelligence.TestFixtures.at
import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.common.appliedOrNull
import com.algotrader.intelligence.common.getOrThrow
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidencePolarity
import com.algotrader.intelligence.evidence.EvidenceSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyLifecycleTest {

    private val dna = TestFixtures.priceActionDna()
    private val supporting = TestFixtures.researchEvidence("e1", dna.ref)

    private fun StrategyRecord.go(target: StrategyLifecycle, minutes: Long): StrategyRecord =
        transitionTo(target, at(minutes), "to $target").getOrThrow()

    private fun withEvidence(): StrategyRecord = TestFixtures.record(dna).addEvidence(supporting, at(0)).getOrThrow()

    private fun rejected(result: TransitionResult<*>): String =
        (result as TransitionResult.Rejected).reason

    // ---- lifecycle ----

    @Test
    fun `full valid lifecycle path with history`() {
        var r = withEvidence()
        r = r.go(StrategyLifecycle.VALIDATING, 1)
        r = r.go(StrategyLifecycle.PROMISING, 2)
        r = r.go(StrategyLifecycle.MONITORING, 3)
        r = r.go(StrategyLifecycle.ACTIVE, 4)
        r = r.go(StrategyLifecycle.DEGRADING, 5)
        r = r.go(StrategyLifecycle.VALIDATING, 6) // re-validate, not straight back to live
        r = r.go(StrategyLifecycle.RETIRED, 7)

        assertEquals(StrategyLifecycle.RETIRED, r.lifecycle)
        assertEquals(7, r.history.size)
        assertEquals(StrategyLifecycle.DEGRADING, r.history[4].to)
    }

    @Test
    fun `illegal jumps are rejected`() {
        val r = withEvidence()
        assertTrue(rejected(r.transitionTo(StrategyLifecycle.ACTIVE, at(1), "x")).contains("cannot move"))
        assertTrue(r.transitionTo(StrategyLifecycle.PROMISING, at(1), "x") is TransitionResult.Rejected)
        assertTrue(r.transitionTo(StrategyLifecycle.DEGRADING, at(1), "x") is TransitionResult.Rejected)
        assertTrue(r.transitionTo(StrategyLifecycle.DISCOVERED, at(1), "x") is TransitionResult.Rejected)
    }

    @Test
    fun `transition table properties`() {
        assertTrue(StrategyLifecycle.RETIRED.allowedNext().isEmpty())
        assertTrue(StrategyLifecycle.RETIRED.isTerminal)
        StrategyLifecycle.values().filter { it != StrategyLifecycle.RETIRED }.forEach {
            assertTrue(it.canTransitionTo(StrategyLifecycle.RETIRED), "$it can retire")
            assertFalse(it.canTransitionTo(it), "$it self transition")
        }
        // degrading never returns straight to live use
        assertFalse(StrategyLifecycle.DEGRADING.canTransitionTo(StrategyLifecycle.ACTIVE))
        assertFalse(StrategyLifecycle.DEGRADING.canTransitionTo(StrategyLifecycle.MONITORING))
        assertFalse(StrategyLifecycle.DEGRADING.canTransitionTo(StrategyLifecycle.PROMISING))
    }

    @Test
    fun `retired is terminal`() {
        val retired = TestFixtures.record(dna).go(StrategyLifecycle.RETIRED, 1)
        StrategyLifecycle.values().forEach {
            assertTrue(retired.transitionTo(it, at(2), "x") is TransitionResult.Rejected, "RETIRED -> $it")
        }
    }

    @Test
    fun `only monitoring and active may detect live opportunities`() {
        val allowed = StrategyLifecycle.values().filter { it.canDetectOpportunities }
        assertEquals(listOf(StrategyLifecycle.MONITORING, StrategyLifecycle.ACTIVE), allowed)
    }

    @Test
    fun `promising needs supporting research evidence`() {
        val validating = TestFixtures.record(dna).go(StrategyLifecycle.VALIDATING, 1)
        assertTrue(rejected(validating.transitionTo(StrategyLifecycle.PROMISING, at(2), "x")).contains("evidence"))

        // contradicting evidence alone does not count
        val contradicting = TestFixtures.researchEvidence(
            "c1", dna.ref, EvidenceKind.COST_SENSITIVITY, EvidenceSample.FULL_PERIOD, EvidencePolarity.CONTRADICTING
        )
        val onlyBad = validating.addEvidence(contradicting, at(2)).getOrThrow()
        assertTrue(onlyBad.transitionTo(StrategyLifecycle.PROMISING, at(3), "x") is TransitionResult.Rejected)
    }

    @Test
    fun `live monitoring needs a tradable target, an index alone is not enough`() {
        val indexOnly = TestFixtures.priceActionDna(tradeTarget = null)
        var r = TestFixtures.record(indexOnly)
            .addEvidence(TestFixtures.researchEvidence("e1", indexOnly.ref), at(0)).getOrThrow()
        r = r.go(StrategyLifecycle.VALIDATING, 1).go(StrategyLifecycle.PROMISING, 2) // research is fine
        assertTrue(rejected(r.transitionTo(StrategyLifecycle.MONITORING, at(3), "x")).contains("trade target"))
    }

    @Test
    fun `a change needs a reason and cannot go back in time`() {
        val r = withEvidence()
        assertTrue(r.transitionTo(StrategyLifecycle.VALIDATING, at(1), " ") is TransitionResult.Rejected)
        val later = r.go(StrategyLifecycle.VALIDATING, 10)
        assertTrue(later.transitionTo(StrategyLifecycle.PROMISING, at(5), "x") is TransitionResult.Rejected)
        assertTrue(r.transitionTo(StrategyLifecycle.VALIDATING, at(0), "same instant ok") is TransitionResult.Applied)
    }

    // ---- evidence association / versioning ----

    @Test
    fun `evidence is bound to the exact strategy version`() {
        val v2 = dna.nextVersion()
        val v1Evidence = TestFixtures.researchEvidence("e1", dna.ref)
        val v2Record = TestFixtures.record(v2)

        assertTrue(rejected(v2Record.addEvidence(v1Evidence, at(1))).contains("belongs to"))
        assertFailsWith<IllegalArgumentException> {
            com.algotrader.intelligence.lifecycle.StrategyRecord(
                dna = v2, createdAt = at(0), evidence = com.algotrader.intelligence.evidence.EvidenceLedger(listOf(v1Evidence))
            )
        }
        // v1 keeps its own evidence after v2 exists
        val v1Record = TestFixtures.record(dna).addEvidence(v1Evidence, at(1)).getOrThrow()
        assertEquals(1, v1Record.evidence.items.size)
        assertEquals(0, v2Record.evidence.items.size)
    }

    @Test
    fun `strategy record holds research evidence only and rejects duplicates`() {
        val r = TestFixtures.record(dna)
        assertTrue(r.addEvidence(TestFixtures.liveEvidence("l1", dna.ref), at(1)) is TransitionResult.Rejected)
        val once = r.addEvidence(supporting, at(1)).getOrThrow()
        assertTrue(once.addEvidence(supporting, at(2)) is TransitionResult.Rejected)
    }

    // ---- health ----

    @Test
    fun `health moves along allowed transitions`() {
        var r = TestFixtures.record(dna)
        r = r.assessHealth(StrategyHealth(StrategyHealthStatus.HEALTHY, at(1), 0.9), at(1)).getOrThrow()
        r = r.assessHealth(StrategyHealth(StrategyHealthStatus.WEAKENING, at(2), 0.6, listOf("out-of-sample edge shrinking")), at(2)).getOrThrow()
        r = r.assessHealth(StrategyHealth(StrategyHealthStatus.DEGRADED, at(3), 0.3, listOf("edge gone")), at(3)).getOrThrow()
        // degraded cannot jump straight back to healthy
        val jump = r.assessHealth(StrategyHealth(StrategyHealthStatus.HEALTHY, at(4), 0.9), at(4))
        assertTrue(rejected(jump).contains("cannot move"))
        r = r.assessHealth(StrategyHealth(StrategyHealthStatus.WEAKENING, at(4), 0.5, listOf("partial recovery")), at(4)).getOrThrow()
        assertEquals(StrategyHealthStatus.WEAKENING, r.health?.status)
    }

    @Test
    fun `non healthy status must state a reason and score stays in range`() {
        assertFailsWith<IllegalArgumentException> { StrategyHealth(StrategyHealthStatus.WEAKENING, at(0)) }
        assertFailsWith<IllegalArgumentException> { StrategyHealth(StrategyHealthStatus.HEALTHY, at(0), 1.5) }
        assertFailsWith<IllegalArgumentException> { StrategyHealth(StrategyHealthStatus.HEALTHY, at(0), Double.NaN) }
        assertNull(TestFixtures.record(dna).health) // unassessed is null, never a made-up default
    }

    @Test
    fun `health is separate from lifecycle`() {
        val live = withEvidence().go(StrategyLifecycle.VALIDATING, 1).go(StrategyLifecycle.PROMISING, 2)
        val degradedHealth = StrategyHealth(StrategyHealthStatus.DEGRADED, at(3), 0.2, listOf("edge gone"))
        val after = live.assessHealth(degradedHealth, at(3)).getOrThrow()
        assertEquals(StrategyLifecycle.PROMISING, after.lifecycle) // assessing health does not move lifecycle
    }

    @Test
    fun `retired health and retired lifecycle go together`() {
        val record = TestFixtures.record(dna)
        val retiredHealth = StrategyHealth(StrategyHealthStatus.RETIRED, at(2), reasons = listOf("retired"))
        assertTrue(record.assessHealth(retiredHealth, at(2)) is TransitionResult.Rejected)

        val retired = record.go(StrategyLifecycle.RETIRED, 1)
        assertTrue(retired.assessHealth(retiredHealth, at(2)) is TransitionResult.Applied)
        val healthy = StrategyHealth(StrategyHealthStatus.HEALTHY, at(2))
        assertTrue(retired.assessHealth(healthy, at(2)) is TransitionResult.Rejected)
    }

    @Test
    fun `rejected changes leave the original record untouched`() {
        val r = withEvidence()
        assertNull(r.transitionTo(StrategyLifecycle.ACTIVE, at(1), "x").appliedOrNull())
        assertEquals(StrategyLifecycle.DISCOVERED, r.lifecycle)
        assertTrue(r.history.isEmpty())
    }
}
