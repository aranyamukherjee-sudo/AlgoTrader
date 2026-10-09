package com.algotrader.app.asi3

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
import com.algotrader.intelligence.opportunity.OpportunityState
import com.algotrader.intelligence.opportunity.SetupOpportunityComposer

/**
 * ASI-3.6 deterministic on-device verification harness.
 * Fixture builders are reused from the passing composer JVM tests.
 */
object Asi36DeviceHarness {

    private val t0 = java.time.Instant.parse("2026-01-05T10:00:00Z")
    private val strategy = com.algotrader.intelligence.dna.StrategyRef(
        com.algotrader.intelligence.dna.StrategyId("test-strategy"),
        3
    )
    private val instrument = InstrumentRef(
        instrument = Instrument("NIFTY26JANFUT", "NSE"),
        kind = InstrumentKind.FUTURES,
        underlying = "NIFTY",
        contractId = "NIFTY26JANFUT"
    )
    private val timeframe = Timeframe.MINUTE_15

    data class Result(
        val passed: Boolean,
        val message: String,
        val direction: String,
        val side: String,
        val state: String,
        val confidence: Double?,
        val evidenceCount: Int,
        val opportunityId: String,
        val deterministic: Boolean,
        val researchRejected: Boolean
    )

    fun run(): Result {
        val upSetup = setup(BreakoutDirection.UP)
        val upContext = context(upSetup)
        val upConfluence = SetupConfluenceAssessmentEngine.assess(upContext)
        val upAssessment = liveAssessment(upSetup)

        val up = SetupOpportunityComposer.compose(
            setup = upSetup,
            strategy = strategy,
            instrument = instrument,
            timeframe = timeframe,
            assessment = upAssessment,
            context = upContext,
            confluence = upConfluence,
            at = t0
        )

        check(up.side == TradeSide.LONG) {
            "UP breakout did not produce LONG side"
        }
        check(up.state == OpportunityState.OPPORTUNITY_FOUND) {
            "Unexpected opportunity state: ${up.state}"
        }
        check(up.currentConfidence == 0.75) {
            "Confidence not preserved: ${up.currentConfidence}"
        }
        check(up.evidence.items.size == 1) {
            "Expected one live evidence item"
        }
        check(up.transitions.size == 1) {
            "Composer unexpectedly advanced opportunity state"
        }

        val repeated = SetupOpportunityComposer.compose(
            setup = upSetup,
            strategy = strategy,
            instrument = instrument,
            timeframe = timeframe,
            assessment = upAssessment,
            context = upContext,
            confluence = upConfluence,
            at = t0
        )
        val deterministic = up == repeated
        check(deterministic) { "Identical inputs produced different opportunities" }

        val downSetup = setup(BreakoutDirection.DOWN)
        val downContext = context(downSetup)
        val down = SetupOpportunityComposer.compose(
            setup = downSetup,
            strategy = strategy,
            instrument = instrument,
            timeframe = timeframe,
            assessment = liveAssessment(downSetup),
            context = downContext,
            confluence = SetupConfluenceAssessmentEngine.assess(downContext),
            at = t0
        )
        check(down.side == TradeSide.SHORT) {
            "DOWN breakout did not produce SHORT side"
        }

        val research = EvidenceItem(
            id = EvidenceRef("asi36-device-research"),
            strategy = strategy,
            kind = EvidenceKind.HISTORICAL_EDGE,
            sample = EvidenceSample.OUT_OF_SAMPLE,
            summary = "research-only fixture",
            source = EvidenceSource(
                EvidenceSourceType.BACKTEST_RUN,
                "asi36-device-backtest-fixture"
            ),
            recordedAt = t0
        )

        val researchAssessment = SetupLiveAssessment(
            evidence = EvidenceLedger(listOf(research)),
            confidenceAssessment = SetupConfidenceAssessment(
                reading = null,
                condition = SetupConfidenceCondition.INSUFFICIENT,
                change = SetupConfidenceChange.INITIAL
            ),
            evidenceAssessment = null
        )

        val researchRejected = try {
            SetupOpportunityComposer.compose(
                setup = upSetup,
                strategy = strategy,
                instrument = instrument,
                timeframe = timeframe,
                assessment = researchAssessment,
                context = upContext,
                confluence = upConfluence,
                at = t0
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        check(researchRejected) { "Research evidence was incorrectly accepted" }

        return Result(
            passed = true,
            message = "All ASI-3.6 checks passed.",
            direction = upSetup.breakout.direction.name,
            side = up.side.name,
            state = up.state.name,
            confidence = up.currentConfidence,
            evidenceCount = up.evidence.items.size,
            opportunityId = up.id.value,
            deterministic = deterministic,
            researchRejected = researchRejected
        )
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
