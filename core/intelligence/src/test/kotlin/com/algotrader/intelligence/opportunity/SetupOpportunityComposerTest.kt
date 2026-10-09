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
