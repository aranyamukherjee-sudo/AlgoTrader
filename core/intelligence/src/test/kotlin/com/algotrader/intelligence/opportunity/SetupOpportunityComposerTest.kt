package com.algotrader.intelligence.opportunity

import com.algotrader.intelligence.common.getOrThrow
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.dna.InstrumentKind
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.dna.TradeSide
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidencePolarity
import com.algotrader.intelligence.evidence.EvidenceSample
import com.algotrader.intelligence.evidence.EvidenceSource
import com.algotrader.intelligence.evidence.EvidenceSourceType
import com.algotrader.intelligence.evidence.EvidenceScope
import com.algotrader.intelligence.dna.EvidenceRef
import com.algotrader.intelligence.setup.Breakout
import com.algotrader.intelligence.setup.BreakoutDirection
import com.algotrader.intelligence.setup.BreakoutId
import com.algotrader.intelligence.setup.BreakoutLevel
import com.algotrader.intelligence.setup.BreakoutQualification
import com.algotrader.intelligence.setup.BreakoutSetup
import com.algotrader.intelligence.setup.BrokenEvent
import com.algotrader.intelligence.setup.CheckStatus
import com.algotrader.intelligence.setup.LifecycleDeadlines
import com.algotrader.intelligence.setup.LevelOrigin
import com.algotrader.intelligence.setup.QualificationCheck
import com.algotrader.intelligence.setup.QualificationCriterion
import com.algotrader.intelligence.setup.QualificationStatus
import com.algotrader.intelligence.setup.SetupConfidenceAssessment
import com.algotrader.intelligence.setup.SetupConfidenceChange
import com.algotrader.intelligence.setup.SetupConfidenceCondition
import com.algotrader.intelligence.setup.SetupEvidenceAssessment
import com.algotrader.intelligence.setup.SetupEvent
import com.algotrader.intelligence.setup.SetupLiveAssessment
import com.algotrader.intelligence.setup.SetupStage
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessment
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessmentEngine
import com.algotrader.intelligence.setup.context.SetupContext
import com.algotrader.intelligence.setup.context.SetupContextFactor
import com.algotrader.intelligence.setup.context.SetupContextFactorKind
import com.algotrader.intelligence.setup.context.SetupContextPolarity
import com.algotrader.intelligence.setup.context.SetupQuality
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetupOpportunityComposerTest {

    private val t0 = Instant.parse("2026-01-05T10:00:00Z")
    private val strategy = StrategyRef(StrategyId("test-strategy"), 3)

    private val instrument = InstrumentRef(
        instrument = Instrument("NIFTY26JANFUT", "NSE"),
        kind = InstrumentKind.FUTURES,
        underlying = "NIFTY",
        contractId = "NIFTY26JANFUT"
    )

    @Test
    fun `composes existing live assessment into opportunity without advancing state`() {
        val setup = setup(BreakoutDirection.UP)
        val context = context(setup)
        val confluence = SetupConfluenceAssessmentEngine.assess(context)
        val assessment = liveAssessment(setup)

        val opportunity = SetupOpportunityComposer.compose(
            setup = setup,
            strategy = strategy,
            instrument = instrument,
            timeframe = Timeframe.MINUTE_15,
            assessment = assessment,
            context = context,
            confluence = confluence,
            at = t0
        )

        assertEquals(OpportunityState.OPPORTUNITY_FOUND, opportunity.state)
        assertEquals(TradeSide.LONG, opportunity.side)
        assertEquals(strategy, opportunity.strategy)
        assertEquals(instrument, opportunity.instrument)
        assertEquals(Timeframe.MINUTE_15, opportunity.timeframe)
        assertEquals(0.75, opportunity.currentConfidence)
        assertEquals(assessment.evidence.items, opportunity.evidence.items)
        assertEquals(1, opportunity.transitions.size)
    }

    @Test
    fun `down breakout composes as short opportunity`() {
        val setup = setup(BreakoutDirection.DOWN)
        val context = context(setup)
        val confluence = SetupConfluenceAssessmentEngine.assess(context)
        val assessment = liveAssessment(setup)

        val opportunity = SetupOpportunityComposer.compose(
            setup = setup,
            strategy = strategy,
            instrument = instrument,
            timeframe = Timeframe.MINUTE_15,
            assessment = assessment,
            context = context,
            confluence = confluence,
            at = t0
        )

        assertEquals(TradeSide.SHORT, opportunity.side)
        assertEquals(
            "test-strategy@3#${setup.breakout.id.value}",
            opportunity.id.value
        )
    }

    @Test
    fun `composer does not manufacture confidence when assessment has none`() {
        val setup = setup(BreakoutDirection.UP)
        val context = context(setup)
        val confluence = SetupConfluenceAssessmentEngine.assess(context)

        val assessment = SetupLiveAssessment(
            evidence = EvidenceLedger(),
            confidenceAssessment = SetupConfidenceAssessment(
                reading = null,
                condition = SetupConfidenceCondition.INSUFFICIENT,
                change = SetupConfidenceChange.INITIAL
            ),
            evidenceAssessment = null
        )

        val opportunity = SetupOpportunityComposer.compose(
            setup = setup,
            strategy = strategy,
            instrument = instrument,
            timeframe = Timeframe.MINUTE_15,
            assessment = assessment,
            context = context,
            confluence = confluence,
            at = t0
        )

        assertEquals(null, opportunity.currentConfidence)
        assertTrue(opportunity.evidence.items.isEmpty())
    }

    @Test
    fun `research evidence is rejected`() {
        val setup = setup(BreakoutDirection.UP)
        val context = context(setup)
        val confluence = SetupConfluenceAssessmentEngine.assess(context)

        val research = EvidenceItem(
            id = EvidenceRef("research"),
            strategy = strategy,
            kind = EvidenceKind.HISTORICAL_EDGE,
            sample = EvidenceSample.OUT_OF_SAMPLE,
            summary = "research evidence",
            source = EvidenceSource(
                EvidenceSourceType.BACKTEST_RUN,
                "backtest-job-1"
            ),
            recordedAt = t0
        )

        val assessment = SetupLiveAssessment(
            evidence = EvidenceLedger(listOf(research)),
            confidenceAssessment = SetupConfidenceAssessment(
                reading = null,
                condition = SetupConfidenceCondition.INSUFFICIENT,
                change = SetupConfidenceChange.INITIAL
            ),
            evidenceAssessment = null
        )

        assertFailsWith<IllegalArgumentException> {
            SetupOpportunityComposer.compose(
                setup,
                strategy,
                instrument,
                Timeframe.MINUTE_15,
                assessment,
                context,
                confluence,
                t0
            )
        }
    }

    @Test
    fun `wrong strategy version in evidence is rejected`() {
        val setup = setup(BreakoutDirection.UP)
        val context = context(setup)
        val confluence = SetupConfluenceAssessmentEngine.assess(context)

        val otherStrategy = StrategyRef(StrategyId("test-strategy"), 2)

        val evidence = EvidenceItem(
            id = EvidenceRef("wrong-strategy"),
            strategy = otherStrategy,
            kind = EvidenceKind.TREND_ALIGNMENT,
            sample = EvidenceSample.LIVE,
            summary = "wrong strategy evidence",
            source = EvidenceSource(
                EvidenceSourceType.LIVE_MARKET_DATA,
                "snapshot-1"
            ),
            recordedAt = t0
        )

        val assessment = liveAssessment(
            setup,
            EvidenceLedger(listOf(evidence))
        )

        assertFailsWith<IllegalArgumentException> {
            SetupOpportunityComposer.compose(
                setup,
                strategy,
                instrument,
                Timeframe.MINUTE_15,
                assessment,
                context,
                confluence,
                t0
            )
        }
    }

    @Test
    fun `mismatched confluence is rejected`() {
        val setup = setup(BreakoutDirection.UP)
        val context = context(setup)
        val actual = SetupConfluenceAssessmentEngine.assess(context)

        val mismatched = actual.copy(
            quality = when (actual.quality) {
                SetupQuality.INSUFFICIENT -> SetupQuality.WEAK
                else -> SetupQuality.INSUFFICIENT
            }
        )

        val assessment = liveAssessment(setup)

        assertFailsWith<IllegalArgumentException> {
            SetupOpportunityComposer.compose(
                setup,
                strategy,
                instrument,
                Timeframe.MINUTE_15,
                assessment,
                context,
                mismatched,
                t0
            )
        }
    }

    @Test
    fun `context from another breakout is rejected`() {
        val setup = setup(BreakoutDirection.UP)
        val otherSetup = setup(BreakoutDirection.DOWN)
        val otherContext = context(otherSetup)
        val confluence = SetupConfluenceAssessmentEngine.assess(otherContext)
        val assessment = liveAssessment(setup)

        assertFailsWith<IllegalArgumentException> {
            SetupOpportunityComposer.compose(
                setup,
                strategy,
                instrument,
                Timeframe.MINUTE_15,
                assessment,
                otherContext,
                confluence,
                t0
            )
        }
    }


    // ASI-4.3 orchestrator regression tests

    @Test
    fun `orchestrator sorts candidates chronologically and registers occurrences`() {
        val orchestrator = OpportunityOrchestrator()
        val originalSetup = setup(BreakoutDirection.UP)
        val laterAt = t0.plusSeconds(120)
        val laterSetup = originalSetup.copy(
            breakout = originalSetup.breakout.copy(
                breakAt = laterAt,
                confirmedAt = laterAt
            )
        )

        val result = orchestrator.process(
            listOf(
                candidate(setup = laterSetup, evaluatedAt = laterAt),
                candidate(setup = originalSetup, evaluatedAt = t0)
            )
        )

        assertEquals(listOf(t0, laterAt), result.items.map { it.candidate.evaluatedAt })
        assertEquals(2, result.created.size)
        assertTrue(result.alreadyPresent.isEmpty())
        assertTrue(result.rejected.isEmpty())
        assertTrue(result.isSuccessful)
        assertEquals(2, orchestrator.size())
    }

    @Test
    fun `orchestrator repeated input is idempotent`() {
        val orchestrator = OpportunityOrchestrator()
        val input = candidate()

        val first = orchestrator.process(listOf(input))
        val second = orchestrator.process(listOf(input))

        assertEquals(1, first.created.size)
        assertEquals(1, second.alreadyPresent.size)
        assertTrue(second.rejected.isEmpty())
        assertEquals(1, orchestrator.size())
        assertEquals(first.created.single(), second.alreadyPresent.single())
    }

    @Test
    fun `orchestrator reports invalid candidate and continues processing`() {
        val orchestrator = OpportunityOrchestrator()
        val valid = candidate()
        val invalid = valid.copy(
            context = valid.context.copy(
                breakoutId = BreakoutId("ASI43_INVALID")
            )
        )

        val result = orchestrator.process(listOf(invalid, valid))

        assertEquals(1, result.created.size)
        assertEquals(1, result.rejected.size)
        assertTrue(!result.isSuccessful)
        assertEquals(1, orchestrator.size())
    }

    // ASI-4.4 registered lifecycle update tests

    @Test
    fun `registry retains a successful lifecycle update in its snapshot`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val updatedAt = t0.plusSeconds(1)
        val result = registry.updateRegistered(opportunity.id) {
            it.advanceTo(OpportunityState.ALERTED, updatedAt, "alert issued")
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        val updated = (result as com.algotrader.intelligence.common.TransitionResult.Applied).value
        assertEquals(OpportunityState.ALERTED, updated.state)
        assertEquals(updatedAt, updated.updatedAt)
        assertEquals(2, updated.transitions.size)
        assertEquals(updated, registry.snapshot().single())
    }

    @Test
    fun `registry rejection leaves the registered opportunity unchanged`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            it.advanceTo(
                OpportunityState.ENTRY_CONFIRMED,
                t0.plusSeconds(1),
                "invalid entry skip"
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry cancellation is retained and terminal opportunities cannot be changed`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val cancelledResult = registry.updateRegistered(opportunity.id) {
            it.cancel(
                CancellationReason.CONDITIONS_WEAKENED,
                "breakout conditions weakened",
                t0.plusSeconds(1)
            )
        }

        assertTrue(cancelledResult is com.algotrader.intelligence.common.TransitionResult.Applied)
        val cancelled = (cancelledResult as com.algotrader.intelligence.common.TransitionResult.Applied).value
        assertEquals(OpportunityState.CANCELLED, cancelled.state)
        assertEquals(CancellationReason.CONDITIONS_WEAKENED, cancelled.cancellation?.reason)
        assertEquals(cancelled, registry.snapshot().single())

        val terminalUpdate = registry.updateRegistered(opportunity.id) {
            it.advanceTo(OpportunityState.ALERTED, t0.plusSeconds(2), "should not reopen")
        }
        assertTrue(terminalUpdate is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(cancelled, registry.snapshot().single())
    }

    @Test
    fun `registry refuses an update that changes opportunity identity`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            com.algotrader.intelligence.common.TransitionResult.Applied(
                it.copy(id = OpportunityId("forged-opportunity-id"))
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry rejects unknown opportunity ids`() {
        val registry = OpportunityRegistry()
        val result = registry.updateRegistered(OpportunityId("missing-opportunity")) {
            it.advanceTo(OpportunityState.ALERTED, t0.plusSeconds(1), "unknown")
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `registry retains reassessment without changing lifecycle state`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            it.reassess(
                0.55,
                it.updatedAt.plusSeconds(1),
                "reassessment updated confidence"
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        val updated =
            (result as com.algotrader.intelligence.common.TransitionResult.Applied).value

        assertEquals(opportunity.state, updated.state)
        assertEquals(opportunity.transitions, updated.transitions)
        assertEquals(
            opportunity.confidenceHistory.readings.size + 1,
            updated.confidenceHistory.readings.size
        )
        assertEquals(updated, registry.snapshot().single())
    }

    @Test
    fun `registry reassessment appends new live evidence`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity
        val at = opportunity.updatedAt.plusSeconds(1)
        val evidence = EvidenceItem(
            id = EvidenceRef("reassessment-evidence"),
            strategy = opportunity.strategy,
            kind = EvidenceKind.CURRENT_PATTERN_MATCH,
            sample = EvidenceSample.LIVE,
            summary = "pattern remains valid",
            source = EvidenceSource(
                EvidenceSourceType.LIVE_MARKET_DATA,
                "reassessment-snapshot-1"
            ),
            recordedAt = at
        )

        val result = registry.reassessRegistered(
            id = opportunity.id,
            confidence = 0.61,
            at = at,
            reason = "pattern reassessed",
            newEvidence = listOf(evidence)
        )

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        val updated =
            (result as com.algotrader.intelligence.common.TransitionResult.Applied).value
        assertEquals(opportunity.state, updated.state)
        assertEquals(opportunity.transitions, updated.transitions)
        assertEquals(opportunity.execution, updated.execution)
        assertEquals(opportunity.confidenceHistory.readings.size + 1,
            updated.confidenceHistory.readings.size)
        assertEquals(opportunity.evidence.items + evidence, updated.evidence.items)
        assertEquals(updated, registry.snapshot().single())
    }

    @Test
    fun `registry reassessment rejects terminal opportunity atomically`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val cancellation = registry.updateRegistered(opportunity.id) {
            it.cancel(
                CancellationReason.CONDITIONS_WEAKENED,
                "conditions weakened",
                it.updatedAt.plusSeconds(1)
            )
        }
        assertTrue(cancellation is com.algotrader.intelligence.common.TransitionResult.Applied)
        val cancelled = registry.snapshot().single()

        val result = registry.reassessRegistered(
            id = opportunity.id,
            confidence = 0.4,
            at = cancelled.updatedAt.plusSeconds(1),
            reason = "attempt to reassess cancelled setup"
        )

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(cancelled, registry.snapshot().single())
    }

    @Test
    fun `registry reassessment rejects duplicate evidence atomically`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity
        val existingEvidence = opportunity.evidence.items.first()
        val before = registry.snapshot().single()

        val result = registry.reassessRegistered(
            id = opportunity.id,
            confidence = 0.6,
            at = opportunity.updatedAt.plusSeconds(1),
            reason = "duplicate evidence attempt",
            newEvidence = listOf(existingEvidence)
        )

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(before, registry.snapshot().single())
    }

    @Test
    fun `registry rejects reassessment confidence with mismatched update timestamp`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            when (
                val reassessed = it.reassess(
                    0.55,
                    it.updatedAt.plusSeconds(1),
                    "reassessment updated confidence"
                )
            ) {
                is com.algotrader.intelligence.common.TransitionResult.Applied ->
                    com.algotrader.intelligence.common.TransitionResult.Applied(
                        reassessed.value.copy(updatedAt = it.updatedAt)
                    )
                is com.algotrader.intelligence.common.TransitionResult.Rejected ->
                    reassessed
            }
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry retains valid expiry as a terminal state`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            it.expire("setup expired", it.updatedAt.plusSeconds(1))
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        val expired =
            (result as com.algotrader.intelligence.common.TransitionResult.Applied).value

        assertEquals(OpportunityState.EXPIRED, expired.state)
        assertEquals(null, expired.cancellation)
        assertEquals(expired, registry.snapshot().single())

        val reopened = registry.updateRegistered(opportunity.id) {
            it.advanceTo(
                OpportunityState.ALERTED,
                it.updatedAt.plusSeconds(1),
                "attempt to reopen expired opportunity"
            )
        }

        assertTrue(reopened is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(expired, registry.snapshot().single())
    }

    @Test
    fun `registry records execution after entry confirmation`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val entryStates = listOf(
            OpportunityState.ALERTED,
            OpportunityState.WAITING_FOR_ENTRY,
            OpportunityState.ENTRY_CONDITIONS_MET,
            OpportunityState.ENTRY_CONFIRMED
        )

        for (target in entryStates) {
            val result = registry.updateRegistered(opportunity.id) {
                it.advanceTo(
                    target,
                    it.updatedAt.plusSeconds(1),
                    "advance to $target"
                )
            }
            assertTrue(
                result is com.algotrader.intelligence.common.TransitionResult.Applied,
                "Expected transition to $target to be accepted"
            )
        }

        val beforeExecution = registry.snapshot().single()
        val result = registry.updateRegistered(opportunity.id) {
            val executionAt = it.updatedAt.plusSeconds(1)
            it.recordExecution(
                com.algotrader.intelligence.opportunity.ExecutionLink.UserActed(
                    executionAt,
                    "user acted"
                ),
                executionAt
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        val updated =
            (result as com.algotrader.intelligence.common.TransitionResult.Applied).value

        assertEquals(OpportunityState.ENTRY_CONFIRMED, updated.state)
        assertEquals(beforeExecution.transitions, updated.transitions)
        assertTrue(
            updated.execution is
                com.algotrader.intelligence.opportunity.ExecutionLink.UserActed
        )
        assertEquals(updated, registry.snapshot().single())
    }


    @Test
    fun `registry advances execution from user action to placed order`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        for (target in listOf(
            OpportunityState.ALERTED,
            OpportunityState.WAITING_FOR_ENTRY,
            OpportunityState.ENTRY_CONDITIONS_MET,
            OpportunityState.ENTRY_CONFIRMED
        )) {
            val result = registry.updateRegistered(opportunity.id) {
                it.advanceTo(target, it.updatedAt.plusSeconds(1), "advance to $target")
            }
            assertTrue(
                result is com.algotrader.intelligence.common.TransitionResult.Applied,
                "Expected transition to $target to be accepted"
            )
        }

        val acted = registry.updateRegistered(opportunity.id) {
            val at = it.updatedAt.plusSeconds(1)
            it.recordExecution(
                com.algotrader.intelligence.opportunity.ExecutionLink.UserActed(at, "acted"),
                at
            )
        }
        assertTrue(acted is com.algotrader.intelligence.common.TransitionResult.Applied)

        val beforeOrder = registry.snapshot().single()
        val orderAt = beforeOrder.updatedAt.plusSeconds(1)
        val orderResult = registry.updateRegistered(opportunity.id) {
            it.recordExecution(
                com.algotrader.intelligence.opportunity.ExecutionLink.OrderPlaced(
                    "test-order-1",
                    orderAt
                ),
                orderAt
            )
        }

        assertTrue(
            orderResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )
        val updated =
            (orderResult as com.algotrader.intelligence.common.TransitionResult.Applied).value
        assertEquals(beforeOrder.state, updated.state)
        assertEquals(beforeOrder.transitions, updated.transitions)
        assertEquals(
            com.algotrader.intelligence.opportunity.ExecutionLink.OrderPlaced(
                "test-order-1",
                orderAt
            ),
            updated.execution
        )
        assertEquals(updated, registry.snapshot().single())
    }

    @Test
    fun `registry rejects execution timestamp after opportunity update`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        for (target in listOf(
            OpportunityState.ALERTED,
            OpportunityState.WAITING_FOR_ENTRY,
            OpportunityState.ENTRY_CONDITIONS_MET,
            OpportunityState.ENTRY_CONFIRMED
        )) {
            val result = registry.updateRegistered(opportunity.id) {
                it.advanceTo(target, it.updatedAt.plusSeconds(1), "advance to $target")
            }
            assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        }

        val before = registry.snapshot().single()
        val result = registry.updateRegistered(opportunity.id) {
            val executionAt = it.updatedAt.plusSeconds(2)
            val link = com.algotrader.intelligence.opportunity.ExecutionLink.UserActed(
                executionAt,
                "forged future timestamp"
            )
            com.algotrader.intelligence.common.TransitionResult.Applied(
                it.copy(
                    execution = link,
                    updatedAt = it.updatedAt.plusSeconds(1)
                )
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(before, registry.snapshot().single())
    }

    @Test
    fun `registry rejects expiry after entry confirmation`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        for (target in listOf(
            OpportunityState.ALERTED,
            OpportunityState.WAITING_FOR_ENTRY,
            OpportunityState.ENTRY_CONDITIONS_MET,
            OpportunityState.ENTRY_CONFIRMED
        )) {
            val result = registry.updateRegistered(opportunity.id) {
                it.advanceTo(target, it.updatedAt.plusSeconds(1), "advance to $target")
            }
            assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        }

        val before = registry.snapshot().single()
        val result = registry.updateRegistered(opportunity.id) {
            it.expire("expiry after confirmed entry", it.updatedAt.plusSeconds(1))
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(before, registry.snapshot().single())
    }

    @Test
    fun `registry rejects forged transition history`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            val forgedAt = it.updatedAt.plusSeconds(1)
            com.algotrader.intelligence.common.TransitionResult.Applied(
                it.copy(
                    transitions = it.transitions + OpportunityTransition(
                        from = it.state,
                        to = it.state,
                        at = forgedAt,
                        reason = "forged self-transition",
                        confidence = null
                    ),
                    updatedAt = forgedAt
                )
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry rejects expiry with a blank transition reason`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            val expired = it.expire(
                "setup expired",
                it.updatedAt.plusSeconds(1)
            ).getOrThrow()

            com.algotrader.intelligence.common.TransitionResult.Applied(
                expired.copy(
                    transitions = expired.transitions.dropLast(1) +
                        expired.transitions.last().copy(reason = "")
                )
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry rejects transition confidence that disagrees with history`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            val advanced = it.advanceTo(
                OpportunityState.ALERTED,
                it.updatedAt.plusSeconds(1),
                "setup alerted",
                confidence = 0.82
            ).getOrThrow()

            com.algotrader.intelligence.common.TransitionResult.Applied(
                advanced.copy(
                    confidenceHistory = advanced.confidenceHistory.copy(
                        readings = advanced.confidenceHistory.readings.dropLast(1) +
                            advanced.confidenceHistory.readings.last().copy(value = 0.41)
                    )
                )
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry rejects confidence history added to a transition without confidence`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            val advanced = it.advanceTo(
                OpportunityState.ALERTED,
                it.updatedAt.plusSeconds(1),
                "setup alerted"
            ).getOrThrow()

            com.algotrader.intelligence.common.TransitionResult.Applied(
                advanced.copy(
                    confidenceHistory = advanced.confidenceHistory.record(
                        ConfidenceReading(
                            0.73,
                            advanced.updatedAt,
                            "unlinked confidence"
                        )
                    )
                )
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry rejects cancellation metadata inconsistent with transition`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            val cancelled = it.cancel(
                CancellationReason.CONDITIONS_WEAKENED,
                "conditions weakened",
                it.updatedAt.plusSeconds(1)
            ).getOrThrow()

            com.algotrader.intelligence.common.TransitionResult.Applied(
                cancelled.copy(
                    cancellation = cancelled.cancellation!!.copy(
                        detail = "different cancellation detail"
                    )
                )
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry rejects multiple confidence readings in one update`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            val firstAt = it.updatedAt.plusSeconds(1)
            val secondAt = it.updatedAt.plusSeconds(2)
            com.algotrader.intelligence.common.TransitionResult.Applied(
                it.copy(
                    confidenceHistory = it.confidenceHistory
                        .record(ConfidenceReading(0.61, firstAt, "first reading"))
                        .record(ConfidenceReading(0.67, secondAt, "second reading")),
                    updatedAt = secondAt
                )
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `registry rejects an update timestamp moving backwards`() {
        val registry = OpportunityRegistry()
        val created = registry.submit(candidate())
        val opportunity = (
            (created as com.algotrader.intelligence.common.TransitionResult.Applied).value
                as OpportunityRegistration.Created
            ).opportunity

        val result = registry.updateRegistered(opportunity.id) {
            com.algotrader.intelligence.common.TransitionResult.Applied(
                it.copy(updatedAt = it.updatedAt.minusSeconds(1))
            )
        }

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(opportunity, registry.snapshot().single())
    }

    @Test
    fun `supersession cancels predecessor and preserves successor`() {
        val registry = OpportunityRegistry()
        val later = t0.plusSeconds(10)

        val predecessorResult = registry.submit(candidate())
        val successorResult = registry.submit(
            candidate(
                setup = setup(BreakoutDirection.DOWN),
                evaluatedAt = later
            )
        )

        assertTrue(
            predecessorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )
        assertTrue(
            successorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )

        val predecessor = (
            (predecessorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity
        val successor = (
            (successorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val at = later.plusSeconds(1)
        val result = registry.supersedeRegistered(
            predecessorId = predecessor.id,
            successorId = successor.id,
            at = at,
            reason = "newer setup detected"
        )

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Applied)
        val cancelled = (
            result as com.algotrader.intelligence.common.TransitionResult.Applied
            ).value

        assertEquals(OpportunityState.CANCELLED, cancelled.state)
        assertEquals(CancellationReason.SUPERSEDED, cancelled.cancellation?.reason)
        assertTrue(cancelled.cancellation?.detail?.contains(successor.id.value) == true)
        assertEquals(at, cancelled.updatedAt)
        assertEquals(successor, registry.snapshot().single { it.id == successor.id })
        assertEquals(2, registry.size())
    }

    @Test
    fun `supersession rejects different instruments without mutation`() {
        val registry = OpportunityRegistry()
        val later = t0.plusSeconds(10)
        val otherInstrument = instrument.copy(
            instrument = Instrument("BANKNIFTY26JANFUT", "NSE"),
            underlying = "BANKNIFTY",
            contractId = "BANKNIFTY26JANFUT"
        )

        val predecessorResult = registry.submit(candidate())
        val successorResult = registry.submit(
            candidate(
                setup = setup(BreakoutDirection.DOWN),
                targetInstrument = otherInstrument,
                evaluatedAt = later
            )
        )

        assertTrue(
            predecessorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )
        assertTrue(
            successorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )

        val predecessor = (
            (predecessorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity
        val successor = (
            (successorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val before = registry.snapshot()
        val result = registry.supersedeRegistered(
            predecessor.id,
            successor.id,
            later.plusSeconds(1),
            "instrument mismatch"
        )

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(before, registry.snapshot())
    }

    @Test
    fun `supersession rejects different timeframes without mutation`() {
        val registry = OpportunityRegistry()
        val later = t0.plusSeconds(10)

        val predecessorResult = registry.submit(candidate())
        val successorResult = registry.submit(
            candidate(
                setup = setup(BreakoutDirection.DOWN),
                targetTimeframe = Timeframe.MINUTE_30,
                evaluatedAt = later
            )
        )

        assertTrue(
            predecessorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )
        assertTrue(
            successorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )

        val predecessor = (
            (predecessorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity
        val successor = (
            (successorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val before = registry.snapshot()
        val result = registry.supersedeRegistered(
            predecessor.id,
            successor.id,
            later.plusSeconds(1),
            "timeframe mismatch"
        )

        assertTrue(
            result is com.algotrader.intelligence.common.TransitionResult.Rejected
        )
        assertEquals(before, registry.snapshot())
    }

    @Test
    fun `supersession rejects a confirmed predecessor without mutation`() {
        val registry = OpportunityRegistry()
        val later = t0.plusSeconds(10)

        val predecessorResult = registry.submit(candidate())
        val successorResult = registry.submit(
            candidate(
                setup = setup(BreakoutDirection.DOWN),
                evaluatedAt = later
            )
        )

        assertTrue(
            predecessorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )
        assertTrue(
            successorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )

        val predecessor = (
            (predecessorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity
        val successor = (
            (successorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val progression = listOf(
            OpportunityState.ALERTED,
            OpportunityState.WAITING_FOR_ENTRY,
            OpportunityState.ENTRY_CONDITIONS_MET,
            OpportunityState.ENTRY_CONFIRMED
        )

        progression.forEachIndexed { index, state ->
            val result = registry.updateRegistered(predecessor.id) { current ->
                current.advanceTo(
                    state,
                    t0.plusSeconds(index.toLong() + 1),
                    "prepare confirmed-entry regression"
                )
            }
            assertTrue(
                result is com.algotrader.intelligence.common.TransitionResult.Applied,
                "Expected predecessor to advance to $state, got $result"
            )
        }

        val before = registry.snapshot()
        val result = registry.supersedeRegistered(
            predecessor.id,
            successor.id,
            later.plusSeconds(1),
            "predecessor entry already confirmed"
        )

        assertTrue(
            result is com.algotrader.intelligence.common.TransitionResult.Rejected
        )
        assertEquals(before, registry.snapshot())
        assertEquals(
            OpportunityState.ENTRY_CONFIRMED,
            registry.snapshot().single { it.id == predecessor.id }.state
        )
        assertEquals(
            successor,
            registry.snapshot().single { it.id == successor.id }
        )
    }

    @Test
    fun `supersession rejects a successor that is not newer`() {
        val registry = OpportunityRegistry()

        val predecessorResult = registry.submit(candidate())
        val successorResult = registry.submit(
            candidate(setup = setup(BreakoutDirection.DOWN))
        )

        assertTrue(
            predecessorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )
        assertTrue(
            successorResult is com.algotrader.intelligence.common.TransitionResult.Applied
        )

        val predecessor = (
            (predecessorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity
        val successor = (
            (successorResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val before = registry.snapshot()
        val result = registry.supersedeRegistered(
            predecessor.id,
            successor.id,
            t0.plusSeconds(1),
            "same-time setup"
        )

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(before, registry.snapshot())
    }

    @Test
    fun `supersession rejects missing and ambiguous opportunity ids`() {
        val registry = OpportunityRegistry()
        val original = setup(BreakoutDirection.UP)
        val laterAt = t0.plusSeconds(60)
        val laterOccurrence = original.copy(
            breakout = original.breakout.copy(
                breakAt = laterAt,
                confirmedAt = laterAt
            )
        )

        val first = registry.submit(candidate(setup = original))
        val second = registry.submit(
            candidate(setup = laterOccurrence, evaluatedAt = laterAt)
        )
        val successor = registry.submit(
            candidate(
                setup = setup(BreakoutDirection.DOWN),
                evaluatedAt = laterAt.plusSeconds(10)
            )
        )

        assertTrue(first is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(second is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(successor is com.algotrader.intelligence.common.TransitionResult.Applied)

        val successorOpportunity = (
            (successor as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val before = registry.snapshot()
        val ambiguous = registry.supersedeRegistered(
            OpportunityId("test-strategy@3#UP:105.0:a1"),
            successorOpportunity.id,
            laterAt.plusSeconds(20),
            "ambiguous predecessor"
        )
        val missing = registry.supersedeRegistered(
            OpportunityId("missing-opportunity"),
            successorOpportunity.id,
            laterAt.plusSeconds(20),
            "missing predecessor"
        )

        assertTrue(ambiguous is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertTrue(missing is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(before, registry.snapshot())
    }

    // ASI-4.7 deterministic registry query tests

    @Test
    fun `query returns ambiguous ids as all matching occurrences`() {
        val registry = OpportunityRegistry()
        val original = setup(BreakoutDirection.UP)
        val laterAt = t0.plusSeconds(60)
        val laterOccurrence = original.copy(
            breakout = original.breakout.copy(
                breakAt = laterAt,
                confirmedAt = laterAt
            )
        )

        registry.submit(candidate(setup = original))
        registry.submit(candidate(setup = laterOccurrence, evaluatedAt = laterAt))

        val matches = registry.query(
            OpportunityQuery(id = OpportunityId("test-strategy@3#UP:105.0:a1"))
        )

        assertEquals(2, matches.size)
        assertTrue(matches.all { it.id.value == "test-strategy@3#UP:105.0:a1" })
    }

    @Test
    fun `query filters use exact strategy instrument timeframe and state`() {
        val registry = OpportunityRegistry()
        registry.submit(candidate())
        registry.submit(
            candidate(
                setup = setup(BreakoutDirection.DOWN),
                targetTimeframe = Timeframe.MINUTE_30
            )
        )

        val matches = registry.query(
            OpportunityQuery(
                strategy = strategy,
                instrument = instrument,
                timeframe = Timeframe.MINUTE_15,
                states = setOf(OpportunityState.OPPORTUNITY_FOUND)
            )
        )

        assertEquals(1, matches.size)
        assertEquals(Timeframe.MINUTE_15, matches.single().timeframe)
        assertEquals(OpportunityState.OPPORTUNITY_FOUND, matches.single().state)
    }

    @Test
    fun `query ordering is deterministic regardless of submission order`() {
        val up = candidate(setup = setup(BreakoutDirection.UP))
        val down = candidate(setup = setup(BreakoutDirection.DOWN))

        val firstRegistry = OpportunityRegistry()
        firstRegistry.submit(up)
        firstRegistry.submit(down)

        val secondRegistry = OpportunityRegistry()
        secondRegistry.submit(down)
        secondRegistry.submit(up)

        assertEquals(
            firstRegistry.query().map { it.id },
            secondRegistry.query().map { it.id }
        )

    }

    @Test
    fun `active query excludes terminal opportunities without mutating registry`() {
        val registry = OpportunityRegistry()
        val submitted = registry.submit(candidate())
        assertTrue(submitted is com.algotrader.intelligence.common.TransitionResult.Applied)

        val opportunity = (
            (submitted as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val cancelled = registry.updateRegistered(opportunity.id) {
            it.cancel(
                CancellationReason.CONDITIONS_WEAKENED,
                "query regression test",
                t0.plusSeconds(1)
            )
        }
        assertTrue(cancelled is com.algotrader.intelligence.common.TransitionResult.Applied)

        val before = registry.snapshot()
        assertTrue(registry.activeSnapshot().isEmpty())
        assertEquals(1, registry.query().size)
        assertEquals(before, registry.snapshot())
    }

    // ASI-4.8 deterministic transition-history query tests

    @Test
    fun `transition history returns every occurrence for an ambiguous id`() {
        val registry = OpportunityRegistry()
        val original = setup(BreakoutDirection.UP)
        val createdResult = registry.submit(candidate(setup = original))
        assertTrue(createdResult is com.algotrader.intelligence.common.TransitionResult.Applied)

        val created = (
            (createdResult as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val advanced = registry.updateRegistered(created.id) {
            it.advanceTo(
                OpportunityState.ALERTED,
                t0.plusSeconds(10),
                "history test alert"
            )
        }
        assertTrue(advanced is com.algotrader.intelligence.common.TransitionResult.Applied)

        val laterAt = t0.plusSeconds(60)
        val laterOccurrence = original.copy(
            breakout = original.breakout.copy(
                breakAt = laterAt,
                confirmedAt = laterAt
            )
        )
        val second = registry.submit(
            candidate(setup = laterOccurrence, evaluatedAt = laterAt)
        )
        assertTrue(second is com.algotrader.intelligence.common.TransitionResult.Applied)

        val history = registry.transitionHistory(
            OpportunityTransitionQuery(
                id = OpportunityId("test-strategy@3#UP:105.0:a1")
            )
        )

        assertEquals(3, history.size)
        assertEquals(
            listOf(
                OpportunityState.OPPORTUNITY_FOUND,
                OpportunityState.ALERTED,
                OpportunityState.OPPORTUNITY_FOUND
            ),
            history.map { it.transition.to }
        )
        assertEquals(
            listOf(t0, t0.plusSeconds(10), laterAt),
            history.map { it.transition.at }
        )
        assertEquals(listOf(0, 1, 0), history.map { it.transitionIndex })
    }

    @Test
    fun `transition history applies inclusive lower and exclusive upper boundaries`() {
        val registry = OpportunityRegistry()
        val submitted = registry.submit(candidate())
        assertTrue(submitted is com.algotrader.intelligence.common.TransitionResult.Applied)

        val opportunity = (
            (submitted as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val advanced = registry.updateRegistered(opportunity.id) {
            it.advanceTo(
                OpportunityState.ALERTED,
                t0.plusSeconds(10),
                "boundary test alert"
            )
        }
        assertTrue(advanced is com.algotrader.intelligence.common.TransitionResult.Applied)

        val history = registry.transitionHistory(
            OpportunityTransitionQuery(
                fromInclusive = t0,
                untilExclusive = t0.plusSeconds(10)
            )
        )

        assertEquals(1, history.size)
        assertEquals(t0, history.single().transition.at)
    }

    @Test
    fun `transition history ordering is deterministic regardless of submission order`() {
        val up = candidate(setup = setup(BreakoutDirection.UP))
        val down = candidate(setup = setup(BreakoutDirection.DOWN))

        val firstRegistry = OpportunityRegistry()
        assertTrue(firstRegistry.submit(up) is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(firstRegistry.submit(down) is com.algotrader.intelligence.common.TransitionResult.Applied)

        val secondRegistry = OpportunityRegistry()
        assertTrue(secondRegistry.submit(down) is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(secondRegistry.submit(up) is com.algotrader.intelligence.common.TransitionResult.Applied)

        assertEquals(
            firstRegistry.transitionHistory(),
            secondRegistry.transitionHistory()
        )
    }

    @Test
    fun `transition history rejects an empty or reversed time range`() {
        assertFailsWith<IllegalArgumentException> {
            OpportunityTransitionQuery(
                fromInclusive = t0,
                untilExclusive = t0
            )
        }

        assertFailsWith<IllegalArgumentException> {
            OpportunityTransitionQuery(
                fromInclusive = t0.plusSeconds(1),
                untilExclusive = t0
            )
        }
    }

    @Test
    fun `transition history includes terminal opportunities and does not mutate registry`() {
        val registry = OpportunityRegistry()
        val submitted = registry.submit(candidate())
        assertTrue(submitted is com.algotrader.intelligence.common.TransitionResult.Applied)

        val opportunity = (
            (submitted as com.algotrader.intelligence.common.TransitionResult.Applied)
                .value as OpportunityRegistration.Created
            ).opportunity

        val cancelled = registry.updateRegistered(opportunity.id) {
            it.cancel(
                CancellationReason.CONDITIONS_WEAKENED,
                "history terminal-state test",
                t0.plusSeconds(5)
            )
        }
        assertTrue(cancelled is com.algotrader.intelligence.common.TransitionResult.Applied)

        val before = registry.snapshot()
        val history = registry.transitionHistory()

        assertEquals(2, history.size)
        assertEquals(OpportunityState.CANCELLED, history.last().transition.to)
        assertEquals(before, registry.snapshot())
        assertEquals(1, registry.size())
    }

    // ASI-4.1 registry regression tests

    private fun candidate(
        setup: BreakoutSetup = setup(BreakoutDirection.UP),
        targetInstrument: InstrumentRef = instrument,
        targetTimeframe: Timeframe = Timeframe.MINUTE_15,
        evaluatedAt: Instant = t0
    ): SetupOpportunityCandidate {
        val ctx = context(setup)
        return SetupOpportunityCandidate(
            setup = setup,
            strategy = strategy,
            instrument = targetInstrument,
            timeframe = targetTimeframe,
            assessment = liveAssessment(setup),
            context = ctx,
            confluence = SetupConfluenceAssessmentEngine.assess(ctx),
            evaluatedAt = evaluatedAt
        )
    }

    @Test
    fun `registry returns existing opportunity for duplicate occurrence`() {
        val registry = OpportunityRegistry()
        val input = candidate()

        val first = registry.submit(input)
        val second = registry.submit(input)

        val created = (first as com.algotrader.intelligence.common.TransitionResult.Applied).value
        val duplicate = (second as com.algotrader.intelligence.common.TransitionResult.Applied).value

        assertTrue(created is OpportunityRegistration.Created)
        assertTrue(duplicate is OpportunityRegistration.AlreadyPresent)
        assertEquals(1, registry.size())

        assertEquals(
            (created as OpportunityRegistration.Created).opportunity,
            (duplicate as OpportunityRegistration.AlreadyPresent).opportunity
        )
    }

    @Test
    fun `same breakout id on different instruments creates separate opportunities`() {
        val registry = OpportunityRegistry()
        val otherInstrument = instrument.copy(
            instrument = Instrument("BANKNIFTY26JANFUT", "NSE"),
            underlying = "BANKNIFTY",
            contractId = "BANKNIFTY26JANFUT"
        )

        val first = registry.submit(candidate())
        val second = registry.submit(candidate(targetInstrument = otherInstrument))

        assertTrue(first is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(second is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertEquals(2, registry.size())
    }

    @Test
    fun `same occurrence on different timeframes creates separate opportunities`() {
        val registry = OpportunityRegistry()

        val first = registry.submit(candidate(targetTimeframe = Timeframe.MINUTE_15))
        val second = registry.submit(candidate(targetTimeframe = Timeframe.MINUTE_30))

        assertTrue(first is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(second is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertEquals(2, registry.size())
    }

    @Test
    fun `new breakout occurrence with same id is registered separately`() {
        val registry = OpportunityRegistry()
        val original = setup(BreakoutDirection.UP)
        val laterAt = t0.plusSeconds(60)
        val laterSetup = original.copy(
            breakout = original.breakout.copy(
                breakAt = laterAt,
                confirmedAt = laterAt
            )
        )

        val first = registry.submit(candidate(original))
        val second = registry.submit(
            candidate(
                setup = laterSetup,
                evaluatedAt = laterAt
            )
        )

        assertTrue(first is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(second is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertEquals(2, registry.size())
    }

    @Test
    fun `older evaluation is rejected without changing registry`() {
        val registry = OpportunityRegistry()
        val later = t0.plusSeconds(120)
        val earlier = t0.plusSeconds(60)

        val accepted = registry.submit(candidate(evaluatedAt = later))
        val stale = registry.submit(candidate(evaluatedAt = earlier))

        assertTrue(accepted is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(stale is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(1, registry.size())
    }

    @Test
    fun `invalid candidate is rejected without partial registration`() {
        val registry = OpportunityRegistry()
        val valid = candidate()
        val invalid = valid.copy(
            context = valid.context.copy(
                breakoutId = BreakoutId("OTHER_BREAKOUT")
            )
        )

        val result = registry.submit(invalid)

        assertTrue(result is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertEquals(0, registry.size())

        val validResult = registry.submit(valid)
        assertTrue(validResult is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertEquals(1, registry.size())
    }

    @Test
    fun `different strategy versions register independently`() {
        val registry = OpportunityRegistry()
        val original = candidate()
        val nextVersion = original.copy(
            strategy = original.strategy.copy(version = original.strategy.version + 1),
            assessment = liveAssessment(
                original.setup,
                EvidenceLedger(
                    listOf(
                        EvidenceItem(
                            id = EvidenceRef("live-trend-v2"),
                            strategy = original.strategy.copy(version = original.strategy.version + 1),
                            kind = EvidenceKind.TREND_ALIGNMENT,
                            sample = EvidenceSample.LIVE,
                            summary = "trend aligned for strategy v2",
                            source = EvidenceSource(
                                EvidenceSourceType.LIVE_MARKET_DATA,
                                "${original.setup.breakout.id.value}@2"
                            ),
                            recordedAt = t0
                        )
                    )
                )
            )
        )

        val first = registry.submit(original)
        val second = registry.submit(nextVersion)

        assertTrue(first is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertTrue(second is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertEquals(2, registry.size())
    }

    @Test
    fun `rejected candidate does not advance stream chronology`() {
        val registry = OpportunityRegistry()
        val valid = candidate()
        val invalid = valid.copy(
            context = valid.context.copy(
                breakoutId = BreakoutId("INVALID_BREAKOUT")
            ),
            evaluatedAt = t0.plusSeconds(120)
        )

        val rejected = registry.submit(invalid)
        val accepted = registry.submit(valid.copy(evaluatedAt = t0.plusSeconds(60)))

        assertTrue(rejected is com.algotrader.intelligence.common.TransitionResult.Rejected)
        assertTrue(accepted is com.algotrader.intelligence.common.TransitionResult.Applied)
        assertEquals(1, registry.size())
    }

    private fun liveAssessment(
        setup: BreakoutSetup,
        evidence: EvidenceLedger = EvidenceLedger(
            listOf(
                EvidenceItem(
                    id = EvidenceRef("live-trend"),
                    strategy = strategy,
                    kind = EvidenceKind.TREND_ALIGNMENT,
                    sample = EvidenceSample.LIVE,
                    summary = "trend aligned",
                    source = EvidenceSource(
                        EvidenceSourceType.LIVE_MARKET_DATA,
                        "${setup.breakout.id.value}@3"
                    ),
                    recordedAt = t0
                )
            )
        )
    ): SetupLiveAssessment {
        val reading = ConfidenceReading(
            value = 0.75,
            at = t0,
            reason = "test"
        )

        return SetupLiveAssessment(
            evidence = evidence,
            confidenceAssessment = SetupConfidenceAssessment(
                reading = reading,
                condition = SetupConfidenceCondition.SUPPORTING,
                change = SetupConfidenceChange.INITIAL
            ),
            evidenceAssessment = SetupEvidenceAssessment(
                breakoutId = setup.breakout.id,
                asOfIndex = 3,
                asOf = t0,
                evidence = evidence,
                confidence = reading
            )
        )
    }

    private fun context(setup: BreakoutSetup): SetupContext =
        SetupContext(
            breakoutId = setup.breakout.id,
            direction = setup.breakout.direction,
            asOfIndex = 3,
            marketStructure = MarketStructureState.UPTREND,
            lifecycleStage = SetupStage.BROKEN,
            relevantLevels = emptyList(),
            factors = listOf(
                SetupContextFactor(
                    id = "structure",
                    kind = SetupContextFactorKind.MARKET_STRUCTURE,
                    polarity = SetupContextPolarity.SUPPORTING,
                    knownAtIndex = 2,
                    summary = "uptrend"
                )
            )
        )

    private fun setup(direction: BreakoutDirection): BreakoutSetup {
        val side = if (direction == BreakoutDirection.UP) {
            ZoneKind.RESISTANCE
        } else {
            ZoneKind.SUPPORT
        }

        val breakout = Breakout(
            id = BreakoutId(
                if (direction == BreakoutDirection.UP) {
                    "UP:105.0:a1"
                } else {
                    "DOWN:95.0:a1"
                }
            ),
            direction = direction,
            level = BreakoutLevel(
                side = side,
                price = if (direction == BreakoutDirection.UP) 105.0 else 95.0,
                origins = listOf(
                    LevelOrigin.Pivot(
                        SwingPivot(
                            type = if (direction == BreakoutDirection.UP) {
                                PivotType.HIGH
                            } else {
                                PivotType.LOW
                            },
                            index = 2,
                            timestamp = t0,
                            price = if (direction == BreakoutDirection.UP) 105.0 else 95.0,
                            confirmedIndex = 3,
                            confirmedAt = t0,
                            strength = 2,
                            prominence = 1.0
                        )
                    )
                )
            ),
            attempt = 1,
            breakIndex = 3,
            breakAt = t0,
            breakClose = if (direction == BreakoutDirection.UP) 106.0 else 94.0,
            confirmedIndex = 3,
            confirmedAt = t0,
            buffer = 0.0,
            trigger = if (direction == BreakoutDirection.UP) 105.0 else 95.0,
            retestBand = null,
            deadlines = LifecycleDeadlines(
                retestUntil = 6,
                failureUntil = 8,
                continuationUntil = 11,
                expiresAt = 11
            )
        )

        return BreakoutSetup(
            breakout = breakout,
            qualification = BreakoutQualification(
                status = QualificationStatus.QUALIFIED,
                checks = listOf(
                    QualificationCheck(
                        criterion = QualificationCriterion.TREND_ALIGNMENT,
                        required = true,
                        status = CheckStatus.PASS,
                        observed = null,
                        requiredValue = null,
                        trendState = MarketStructureState.UPTREND
                    )
                )
            ),
            events = listOf(
                BrokenEvent(3, t0, breakout.breakClose)
            )
        )
    }
}
