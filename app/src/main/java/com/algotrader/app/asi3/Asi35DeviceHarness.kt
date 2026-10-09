package com.algotrader.app.asi3

import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.setup.Breakout
import com.algotrader.intelligence.setup.BreakoutDirection
import com.algotrader.intelligence.setup.BreakoutId
import com.algotrader.intelligence.setup.BreakoutLevel
import com.algotrader.intelligence.setup.BreakoutSetup
import com.algotrader.intelligence.setup.BrokenEvent
import com.algotrader.intelligence.setup.BreakoutQualification
import com.algotrader.intelligence.setup.CheckStatus
import com.algotrader.intelligence.setup.LevelOrigin
import com.algotrader.intelligence.setup.LifecycleDeadlines
import com.algotrader.intelligence.setup.PatternBoundaryRole
import com.algotrader.intelligence.setup.QualificationCheck
import com.algotrader.intelligence.setup.QualificationCriterion
import com.algotrader.intelligence.setup.QualificationStatus
import com.algotrader.intelligence.setup.RetestHeldEvent
import com.algotrader.intelligence.setup.RetestTouchedEvent
import com.algotrader.intelligence.setup.SetupEvent
import com.algotrader.intelligence.setup.SetupStage
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessmentEngine
import com.algotrader.intelligence.setup.context.SetupContext
import com.algotrader.intelligence.setup.context.SetupContextExtractor
import com.algotrader.intelligence.setup.context.SetupContextFactorKind
import com.algotrader.intelligence.setup.context.SetupContextPolarity
import com.algotrader.intelligence.setup.context.SetupQuality
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.ClassifiedPivot
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.PriceZone
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.StructureLabel
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import java.time.Instant

/**
 * ASI-3.5 physical-device validation.
 *
 * Uses production context extraction and confluence assessment APIs with
 * deterministic local fixtures. It does not place trades or use live prices.
 */
object Asi35DeviceHarness {

    data class Result(
        val passed: Boolean,
        val message: String,
        val strongConfluencePassed: Boolean,
        val supportingKindCount: Int,
        val supportingLevelFound: Boolean,
        val conflictingLevelDetected: Boolean,
        val conflictQualityPassed: Boolean,
        val futureEvidenceExcluded: Boolean,
        val lookaheadRejected: Boolean,
        val deterministic: Boolean,
        val lifecycleStage: String
    )

    private val t0 = Instant.parse("2026-10-06T04:00:00Z")

    private fun pivot(
        type: PivotType,
        price: Double,
        index: Int,
        confirmedIndex: Int
    ) = SwingPivot(
        type = type,
        index = index,
        timestamp = t0.plusSeconds(index.toLong()),
        price = price,
        confirmedIndex = confirmedIndex,
        confirmedAt = t0.plusSeconds(confirmedIndex.toLong()),
        strength = 2,
        prominence = 1.0
    )

    private fun pattern(
        type: PatternType,
        confirmed: Int
    ): DetectedPattern {
        val p0 = pivot(PivotType.HIGH, 100.0, 1, 2)
        val p1 = pivot(PivotType.LOW, 90.0, 2, 3)
        val p2 = pivot(PivotType.HIGH, 100.0, 3, confirmed)

        return DetectedPattern(
            type = type,
            pivots = listOf(p0, p1, p2),
            startIndex = 1,
            endIndex = 3,
            confirmedIndex = confirmed,
            confirmedAt = t0.plusSeconds(confirmed.toLong()),
            high = 100.0,
            low = 90.0,
            neckline = 95.0
        )
    }

    private fun patternAnalysis(
        count: Int,
        vararg patterns: DetectedPattern
    ) = PatternAnalysis(
        status = AnalysisStatus.OK,
        message = "",
        candleCount = count,
        patterns = patterns.toList()
    )

    private fun structure(
        count: Int,
        state: MarketStructureState,
        pivots: List<SwingPivot> = emptyList(),
        zones: List<PriceZone> = emptyList()
    ) = StructureAnalysis(
        status = AnalysisStatus.OK,
        message = "",
        candleCount = count,
        pivots = pivots.map {
            ClassifiedPivot(pivot = it, label = StructureLabel.FIRST)
        },
        state = state,
        zones = zones,
        atr = null
    )

    private fun setup(
        direction: BreakoutDirection,
        level: BreakoutLevel,
        events: List<SetupEvent>
    ): BreakoutSetup {
        val breakout = Breakout(
            id = BreakoutId("asi35-device-fixture"),
            direction = direction,
            level = level,
            attempt = 1,
            breakIndex = 5,
            breakAt = t0.plusSeconds(5),
            breakClose = 101.0,
            confirmedIndex = 5,
            confirmedAt = t0.plusSeconds(5),
            buffer = 0.0,
            trigger = 100.0,
            retestBand = 1.0,
            deadlines = LifecycleDeadlines(
                retestUntil = 10,
                failureUntil = 12,
                continuationUntil = 15,
                expiresAt = 20
            )
        )

        val qualification = BreakoutQualification(
            status = QualificationStatus.QUALIFIED,
            checks = listOf(
                QualificationCheck(
                    criterion = QualificationCriterion.TREND_ALIGNMENT,
                    required = true,
                    status = CheckStatus.PASS,
                    observed = null,
                    requiredValue = null,
                    trendState = null
                )
            )
        )

        return BreakoutSetup(
            breakout = breakout,
            qualification = qualification,
            events = events
        )
    }

    private fun checkOnce(): Result {
        val triangle = pattern(
            type = PatternType.ASCENDING_TRIANGLE,
            confirmed = 6
        )

        val alignedSetup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.PatternBoundary(
                        triangle,
                        PatternBoundaryRole.TRIANGLE_FLAT_TOP
                    )
                )
            ),
            events = listOf(
                BrokenEvent(5, t0.plusSeconds(5), 101.0),
                RetestTouchedEvent(6, t0.plusSeconds(6), 101.0),
                RetestHeldEvent(7, t0.plusSeconds(7), 101.0)
            )
        )

        val alignedStructure = structure(
            count = 8,
            state = MarketStructureState.UPTREND,
            pivots = listOf(
                pivot(PivotType.LOW, 90.0, 2, 3),
                pivot(PivotType.HIGH, 100.0, 3, 4)
            ),
            zones = listOf(
                PriceZone(
                    kind = ZoneKind.SUPPORT,
                    low = 99.7,
                    high = 99.8,
                    touches = 2,
                    firstIndex = 2,
                    lastIndex = 3,
                    lastConfirmedIndex = 4
                )
            )
        )

        val alignedPatterns = patternAnalysis(8, triangle)

        val contextA = SetupContextExtractor.extract(
            alignedSetup,
            alignedStructure,
            alignedPatterns,
            asOfIndex = 7
        )
        val contextB = SetupContextExtractor.extract(
            alignedSetup,
            alignedStructure,
            alignedPatterns,
            asOfIndex = 7
        )

        val assessment = SetupConfluenceAssessmentEngine.assess(contextA)
        val repeatedAssessment = SetupConfluenceAssessmentEngine.assess(contextB)

        val strongPassed =
            contextA == contextB &&
            assessment == repeatedAssessment &&
            assessment.quality == SetupQuality.STRONG &&
            assessment.supportingFactorCount == 3 &&
            assessment.conflictingFactorCount == 0 &&
            contextA.lifecycleStage == SetupStage.RETEST_HELD

        val supportingLevelFound = contextA.relevantLevels.any {
            it.side == ZoneKind.SUPPORT &&
                it.price < alignedSetup.breakout.level.price
        }

        val conflictSetup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.Pivot(pivot(PivotType.HIGH, 100.0, 3, 4))
                )
            ),
            events = listOf(
                BrokenEvent(5, t0.plusSeconds(5), 101.0)
            )
        )

        val conflictContext = SetupContextExtractor.extract(
            conflictSetup,
            structure(
                count = 6,
                state = MarketStructureState.UPTREND,
                pivots = listOf(
                    pivot(PivotType.LOW, 99.0, 2, 3),
                    pivot(PivotType.HIGH, 100.0, 3, 4),
                    pivot(PivotType.HIGH, 100.4, 4, 5)
                )
            ),
            patternAnalysis(6),
            asOfIndex = 5
        )

        val conflictAssessment =
            SetupConfluenceAssessmentEngine.assess(conflictContext)

        val conflictingLevelDetected = conflictContext.factors.any {
            it.kind == SetupContextFactorKind.LEVEL_CONTEXT &&
                it.polarity == SetupContextPolarity.CONFLICTING
        }

        val conflictQualityPassed =
            conflictingLevelDetected &&
            conflictAssessment.quality == SetupQuality.WEAK

        val futurePattern = pattern(
            type = PatternType.ASCENDING_TRIANGLE,
            confirmed = 8
        )

        val futureSetup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.PatternBoundary(
                        futurePattern,
                        PatternBoundaryRole.TRIANGLE_FLAT_TOP
                    )
                )
            ),
            events = listOf(
                BrokenEvent(5, t0.plusSeconds(5), 101.0),
                RetestTouchedEvent(7, t0.plusSeconds(7), 101.0),
                RetestHeldEvent(8, t0.plusSeconds(8), 101.0)
            )
        )

        val prefixContext = SetupContextExtractor.extract(
            futureSetup,
            structure(6, MarketStructureState.UNDEFINED),
            patternAnalysis(6, futurePattern),
            asOfIndex = 5
        )

        val futureEvidenceExcluded =
            prefixContext.factors.isEmpty() &&
            prefixContext.lifecycleStage == SetupStage.BROKEN &&
            prefixContext.asOfIndex == 5

        val lookaheadRejected = try {
            SetupContextExtractor.extract(
                alignedSetup,
                structure(10, MarketStructureState.UPTREND),
                patternAnalysis(10, triangle),
                asOfIndex = 5
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }

        val passed =
            strongPassed &&
            supportingLevelFound &&
            conflictQualityPassed &&
            futureEvidenceExcluded &&
            lookaheadRejected

        return Result(
            passed = passed,
            message = if (passed) {
                "All ASI-3.5 production context checks passed."
            } else {
                "One or more ASI-3.5 production context checks failed."
            },
            strongConfluencePassed = strongPassed,
            supportingKindCount = assessment.supportingFactorCount,
            supportingLevelFound = supportingLevelFound,
            conflictingLevelDetected = conflictingLevelDetected,
            conflictQualityPassed = conflictQualityPassed,
            futureEvidenceExcluded = futureEvidenceExcluded,
            lookaheadRejected = lookaheadRejected,
            deterministic = true,
            lifecycleStage = contextA.lifecycleStage.name
        )
    }

    fun run(): Result {
        val first = checkOnce()
        val second = checkOnce()
        val deterministic =
            first.copy(deterministic = false) ==
                second.copy(deterministic = false)

        val passed = first.passed && deterministic

        return first.copy(
            passed = passed,
            deterministic = deterministic,
            message = if (passed) {
                "All ASI-3.5 production context checks passed."
            } else {
                "ASI-3.5 validation failed or repeated results differed."
            }
        )
    }
}
