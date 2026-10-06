package com.algotrader.intelligence.opportunity

import com.algotrader.intelligence.TestFixtures
import com.algotrader.intelligence.TestFixtures.at
import com.algotrader.intelligence.TestFixtures.step
import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.common.getOrThrow
import com.algotrader.intelligence.confidence.ConfidenceTrend
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.lifecycle.StrategyLifecycle
import com.algotrader.intelligence.opportunity.OpportunityState.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpportunityTest {

    private val dna = TestFixtures.priceActionDna()

    private fun rejected(result: TransitionResult<*>): String = (result as TransitionResult.Rejected).reason

    // ---- derived entry/exit views ----

    @Test
    fun `entry and exit states are derived from the single state`() {
        data class Row(val s: OpportunityState, val entry: EntryState, val exit: ExitState)
        val rows = listOf(
            Row(OPPORTUNITY_FOUND, EntryState.NOT_STARTED, ExitState.NOT_APPLICABLE),
            Row(ALERTED, EntryState.NOT_STARTED, ExitState.NOT_APPLICABLE),
            Row(WAITING_FOR_ENTRY, EntryState.WAITING, ExitState.NOT_APPLICABLE),
            Row(ENTRY_CONDITIONS_MET, EntryState.CONDITIONS_MET, ExitState.NOT_APPLICABLE),
            Row(ENTRY_CONFIRMED, EntryState.CONFIRMED, ExitState.NONE),
            Row(MONITORING, EntryState.CONFIRMED, ExitState.NONE),
            Row(EXIT_CONDITIONS_FORMING, EntryState.CONFIRMED, ExitState.FORMING),
            Row(EXIT_ALERT, EntryState.CONFIRMED, ExitState.ALERTED),
            Row(EXIT_CONDITIONS_MET, EntryState.CONFIRMED, ExitState.CONDITIONS_MET),
            Row(EXIT_CONFIRMED, EntryState.CONFIRMED, ExitState.CONFIRMED),
            Row(CANCELLED, EntryState.VOIDED, ExitState.NOT_APPLICABLE),
            Row(EXPIRED, EntryState.VOIDED, ExitState.NOT_APPLICABLE)
        )
        assertEquals(OpportunityState.values().toSet(), rows.map { it.s }.toSet())
        rows.forEach {
            assertEquals(it.entry, it.s.entryState, "${it.s} entry")
            assertEquals(it.exit, it.s.exitState, "${it.s} exit")
        }
    }

    @Test
    fun `detection, entry conditions and confirmation are three different things`() {
        assertFalse(OPPORTUNITY_FOUND.entryState == EntryState.CONFIRMED)
        assertFalse(ENTRY_CONDITIONS_MET.entryState == EntryState.CONFIRMED)
        assertEquals(EntryState.CONFIRMED, ENTRY_CONFIRMED.entryState)
    }

    @Test
    fun `an exit warning is not an exit signal`() {
        assertFalse(EXIT_CONDITIONS_FORMING.exitState == ExitState.CONDITIONS_MET)
        assertFalse(EXIT_ALERT.exitState == ExitState.CONDITIONS_MET)
        assertFalse(EXIT_ALERT.exitState == ExitState.CONFIRMED)
        assertEquals(ExitState.CONDITIONS_MET, EXIT_CONDITIONS_MET.exitState)
    }

    // ---- state machine table ----

    @Test
    fun `terminal states have no way out`() {
        listOf(EXIT_CONFIRMED, CANCELLED, EXPIRED).forEach {
            assertTrue(it.isTerminal)
            assertTrue(OpportunityStateMachine.allowedFrom(it).isEmpty(), "$it")
        }
        assertEquals(3, OpportunityState.values().count { it.isTerminal })
    }

    @Test
    fun `entry and exit cannot be skipped`() {
        val m = OpportunityStateMachine
        assertFalse(m.canTransition(OPPORTUNITY_FOUND, ENTRY_CONFIRMED))
        assertFalse(m.canTransition(ALERTED, ENTRY_CONFIRMED))
        assertFalse(m.canTransition(WAITING_FOR_ENTRY, ENTRY_CONFIRMED))
        assertFalse(m.canTransition(ENTRY_CONFIRMED, EXIT_CONFIRMED))
        assertFalse(m.canTransition(MONITORING, EXIT_CONFIRMED))
        assertFalse(m.canTransition(EXIT_CONDITIONS_FORMING, EXIT_CONFIRMED))
        assertFalse(m.canTransition(EXIT_ALERT, EXIT_CONFIRMED))
        // once entered, there is no going back to a pre-entry state
        assertFalse(m.canTransition(MONITORING, WAITING_FOR_ENTRY))
        assertFalse(m.canTransition(ENTRY_CONFIRMED, ENTRY_CONDITIONS_MET))
    }

    @Test
    fun `every non terminal state can reach a terminal state`() {
        OpportunityState.values().filter { !it.isTerminal }.forEach { start ->
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            var reachesTerminal = false
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (next.isTerminal) reachesTerminal = true
                OpportunityStateMachine.allowedFrom(next).filter { seen.add(it) }.forEach { queue.add(it) }
            }
            assertTrue(reachesTerminal, "$start must be able to finish")
        }
    }

    // ---- lifecycle of one opportunity ----

    @Test
    fun `full path from detection to confirmed exit with changing confidence`() {
        var o = TestFixtures.opportunity(confidence = 0.68)
        o = o.step(ALERTED, 1)
        o = o.step(WAITING_FOR_ENTRY, 2, 0.76)
        o = o.step(ENTRY_CONDITIONS_MET, 3)
        o = o.step(ENTRY_CONFIRMED, 4, 0.84)
        o = o.step(MONITORING, 5)
        o = o.step(EXIT_CONDITIONS_FORMING, 6, 0.72)
        o = o.step(EXIT_ALERT, 7)
        o = o.step(EXIT_CONDITIONS_MET, 8)
        o = o.step(EXIT_CONFIRMED, 9)

        assertEquals(EXIT_CONFIRMED, o.state)
        assertEquals(10, o.transitions.size)
        assertNull(o.transitions.first().from)
        assertEquals(listOf(0.68, 0.76, 0.84, 0.72), o.confidenceHistory.readings.map { it.value })
        assertEquals(0.72, o.currentConfidence)
        assertEquals(ConfidenceTrend.FALLING, o.confidenceHistory.trend)
    }

    @Test
    fun `alerted may be skipped and a warning may fade back to monitoring`() {
        var o = TestFixtures.opportunity().step(WAITING_FOR_ENTRY, 1)
        o = o.step(ENTRY_CONDITIONS_MET, 2).step(WAITING_FOR_ENTRY, 3) // lapsed before confirmation
        o = o.step(ENTRY_CONDITIONS_MET, 4).step(ENTRY_CONFIRMED, 5).step(MONITORING, 6)
        o = o.step(EXIT_CONDITIONS_FORMING, 7).step(MONITORING, 8) // warning faded, not an exit
        assertEquals(MONITORING, o.state)
        assertEquals(ExitState.NONE, o.exitState)
    }

    @Test
    fun `an exit can be met suddenly without prior warnings`() {
        val o = TestFixtures.opportunity().step(WAITING_FOR_ENTRY, 1).step(ENTRY_CONDITIONS_MET, 2)
            .step(ENTRY_CONFIRMED, 3).step(MONITORING, 4).step(EXIT_CONDITIONS_MET, 5)
        assertEquals(ExitState.CONDITIONS_MET, o.exitState)
    }

    @Test
    fun `illegal transitions are rejected and leave the opportunity unchanged`() {
        val o = TestFixtures.opportunity()
        val result = o.advanceTo(ENTRY_CONFIRMED, at(1), "jump")
        assertTrue(rejected(result).contains("cannot move"))
        assertEquals(OPPORTUNITY_FOUND, o.state)
        assertEquals(1, o.transitions.size)
    }

    // ---- cancellation / expiry ----

    @Test
    fun `setup found, waiting, conditions weaken, opportunity cancelled`() {
        var o = TestFixtures.opportunity(confidence = 0.68).step(WAITING_FOR_ENTRY, 1)
        o = o.reassess(0.41, at(2), "structure weakening").getOrThrow()
        assertEquals(WAITING_FOR_ENTRY, o.state) // reassessing alone does not cancel
        assertEquals(ConfidenceTrend.FALLING, o.confidenceHistory.trend)

        o = o.cancel(CancellationReason.CONDITIONS_WEAKENED, "neckline held, volume faded", at(3)).getOrThrow()

        assertEquals(CANCELLED, o.state)
        assertEquals(EntryState.VOIDED, o.entryState)
        assertEquals(CancellationReason.CONDITIONS_WEAKENED, o.cancellation?.reason)
        assertEquals(WAITING_FOR_ENTRY, o.cancellation?.stateWhenCancelled)
        assertTrue(o.state.isTerminal)
    }

    @Test
    fun `cancel and expire are possible from every pre entry state`() {
        val pre = listOf(OPPORTUNITY_FOUND, ALERTED, WAITING_FOR_ENTRY, ENTRY_CONDITIONS_MET)
        val path = listOf(ALERTED, WAITING_FOR_ENTRY, ENTRY_CONDITIONS_MET)
        pre.forEachIndexed { index, state ->
            var o = TestFixtures.opportunity()
            path.take(index).forEachIndexed { i, s -> o = o.step(s, i + 1L) }
            assertEquals(state, o.state)
            assertTrue(o.cancel(CancellationReason.USER_DISMISSED, "no thanks", at(10)) is TransitionResult.Applied, "cancel $state")
            val expired = o.expire("session ended", at(10)).getOrThrow()
            assertEquals(EXPIRED, expired.state)
            assertNull(expired.cancellation)
            assertEquals(EntryState.VOIDED, expired.entryState)
        }
    }

    @Test
    fun `cancel and expire are rejected once entry is confirmed or after closing`() {
        var o = TestFixtures.opportunity().step(WAITING_FOR_ENTRY, 1).step(ENTRY_CONDITIONS_MET, 2).step(ENTRY_CONFIRMED, 3)
        assertTrue(rejected(o.cancel(CancellationReason.CONDITIONS_WEAKENED, "x", at(4))).contains("exit states"))
        assertTrue(o.expire("x", at(4)) is TransitionResult.Rejected)
        o = o.step(MONITORING, 4).step(EXIT_CONDITIONS_MET, 5).step(EXIT_CONFIRMED, 6)
        assertTrue(o.cancel(CancellationReason.USER_DISMISSED, "x", at(7)) is TransitionResult.Rejected)
    }

    @Test
    fun `cancel and expire cannot be done through advanceTo`() {
        val o = TestFixtures.opportunity()
        assertTrue(o.advanceTo(CANCELLED, at(1), "x") is TransitionResult.Rejected)
        assertTrue(o.advanceTo(EXPIRED, at(1), "x") is TransitionResult.Rejected)
    }

    @Test
    fun `cancelling an opportunity does not touch the strategy`() {
        val record = TestFixtures.record(dna)
            .addEvidence(TestFixtures.researchEvidence("e1", dna.ref), at(0)).getOrThrow()
            .transitionTo(StrategyLifecycle.VALIDATING, at(1), "go").getOrThrow()
        val cancelled = TestFixtures.opportunity(dna.ref)
            .cancel(CancellationReason.INVALIDATION_CONDITION_MET, "lower low", at(2)).getOrThrow()

        assertEquals(CANCELLED, cancelled.state)
        assertEquals(StrategyLifecycle.VALIDATING, record.lifecycle)
        assertEquals(dna.ref, cancelled.strategy) // it only refers to the strategy by ref
    }

    // ---- confidence / invariants ----

    @Test
    fun `confidence must be on the shared 0 to 1 scale`() {
        val o = TestFixtures.opportunity()
        assertTrue(o.advanceTo(ALERTED, at(1), "x", confidence = 68.0) is TransitionResult.Rejected)
        assertTrue(o.reassess(1.5, at(1), "x") is TransitionResult.Rejected)
        assertTrue(o.reassess(Double.NaN, at(1), "x") is TransitionResult.Rejected)
        assertEquals(0.68, o.currentConfidence)
    }

    @Test
    fun `an unscored opportunity has null confidence, not a made up number`() {
        assertNull(TestFixtures.opportunity(confidence = null).currentConfidence)
    }

    @Test
    fun `terminal opportunities, empty reasons and time travel are rejected`() {
        val o = TestFixtures.opportunity().step(ALERTED, 5)
        assertTrue(o.advanceTo(WAITING_FOR_ENTRY, at(6), " ") is TransitionResult.Rejected)
        assertTrue(o.advanceTo(WAITING_FOR_ENTRY, at(1), "back in time") is TransitionResult.Rejected)
        val cancelled = o.cancel(CancellationReason.SUPERSEDED, "newer setup", at(6)).getOrThrow()
        assertTrue(cancelled.reassess(0.5, at(7), "x") is TransitionResult.Rejected)
        assertTrue(cancelled.advanceTo(WAITING_FOR_ENTRY, at(7), "x") is TransitionResult.Rejected)
    }

    @Test
    fun `an opportunity needs a tradable instrument`() {
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.opportunity(instrument = TestFixtures.niftyIndex)
        }
    }

    // ---- evidence association and versioning ----

    @Test
    fun `opportunity holds live evidence for its own strategy version`() {
        val live = TestFixtures.liveEvidence("l1", dna.ref)
        val o = TestFixtures.opportunity(dna.ref)
            .advanceTo(ALERTED, at(1), "alert", newEvidence = listOf(live)).getOrThrow()
        assertEquals(listOf(live), o.evidence.items)

        assertTrue(
            TestFixtures.opportunity(dna.ref)
                .advanceTo(ALERTED, at(1), "x", newEvidence = listOf(TestFixtures.researchEvidence("r1", dna.ref))) is TransitionResult.Rejected
        )
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.opportunity(dna.ref).copy(
                evidence = EvidenceLedger(listOf(TestFixtures.researchEvidence("r1", dna.ref)))
            )
        }
    }

    @Test
    fun `an opportunity stays pinned to the strategy version that produced it`() {
        val v1 = dna.ref
        val v2 = dna.nextVersion().ref
        val o = TestFixtures.opportunity(v1)

        assertEquals(v1, o.strategy)
        // evidence about the evolved version cannot be attached to a v1 opportunity
        val v2Live = TestFixtures.liveEvidence("l2", v2)
        assertTrue(o.reassess(0.7, at(1), "x", listOf(v2Live)) is TransitionResult.Rejected)
    }

    // ---- execution is a separate layer ----

    @Test
    fun `confirmed entry is not execution`() {
        val o = TestFixtures.opportunity().step(WAITING_FOR_ENTRY, 1).step(ENTRY_CONDITIONS_MET, 2).step(ENTRY_CONFIRMED, 3)
        assertEquals(ExecutionLink.None, o.execution)
    }

    @Test
    fun `execution can only be recorded after entry is confirmed`() {
        val early = TestFixtures.opportunity().step(WAITING_FOR_ENTRY, 1).step(ENTRY_CONDITIONS_MET, 2)
        val link = ExecutionLink.UserActed(at(3), "chose to act")
        assertTrue(rejected(early.recordExecution(link, at(3))).contains("confirmed entry"))

        val entered = early.step(ENTRY_CONFIRMED, 3)
        val acted = entered.recordExecution(link, at(4)).getOrThrow()
        assertEquals(link, acted.execution)
        assertEquals(ENTRY_CONFIRMED, acted.state) // execution does not move the state
    }

    @Test
    fun `state changes never alter execution and execution cannot go backwards`() {
        var o = TestFixtures.opportunity().step(WAITING_FOR_ENTRY, 1).step(ENTRY_CONDITIONS_MET, 2).step(ENTRY_CONFIRMED, 3)
        o = o.recordExecution(ExecutionLink.OrderPlaced("order-1", at(4)), at(4)).getOrThrow()
        o = o.step(MONITORING, 5).step(EXIT_CONDITIONS_MET, 6)
        assertEquals(ExecutionLink.OrderPlaced("order-1", at(4)), o.execution)

        assertTrue(o.recordExecution(ExecutionLink.UserActed(at(7)), at(7)) is TransitionResult.Rejected)
        assertTrue(o.recordExecution(ExecutionLink.None, at(7)) is TransitionResult.Rejected)
        assertFailsWith<IllegalArgumentException> { ExecutionLink.OrderPlaced(" ", at(0)) }
    }

    @Test
    fun `execution cannot exist on an opportunity that never entered`() {
        assertFailsWith<IllegalArgumentException> {
            TestFixtures.opportunity().copy(execution = ExecutionLink.UserActed(at(1)))
        }
    }

    @Test
    fun `execution cannot be recorded after the opportunity closed`() {
        val closed = TestFixtures.opportunity().step(WAITING_FOR_ENTRY, 1).step(ENTRY_CONDITIONS_MET, 2)
            .step(ENTRY_CONFIRMED, 3).step(MONITORING, 4).step(EXIT_CONDITIONS_MET, 5).step(EXIT_CONFIRMED, 6)
        assertTrue(closed.recordExecution(ExecutionLink.UserActed(at(7)), at(7)) is TransitionResult.Rejected)
    }
}
