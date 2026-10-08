package com.algotrader.intelligence.setup.context

import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.setup.Breakout
import com.algotrader.intelligence.setup.BreakoutDirection
import com.algotrader.intelligence.setup.BreakoutId
import com.algotrader.intelligence.setup.BreakoutLevel
import com.algotrader.intelligence.setup.BreakoutSetup
import com.algotrader.intelligence.setup.SetupEvent
import com.algotrader.intelligence.setup.BrokenEvent
import com.algotrader.intelligence.setup.QualificationCheck
import com.algotrader.intelligence.setup.QualificationCriterion
import com.algotrader.intelligence.setup.QualificationStatus
import com.algotrader.intelligence.setup.CheckStatus
import com.algotrader.intelligence.setup.LevelOrigin
import com.algotrader.intelligence.setup.LifecycleDeadlines
import com.algotrader.intelligence.setup.PatternBoundaryRole
import com.algotrader.intelligence.setup.SetupStage
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.ClassifiedPivot
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.PriceZone
import com.algotrader.intelligence.structure.StructureLabel
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.Instant

class SetupContextTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `aligned structure pattern level and retest produce strong quality`() {
        val pattern = pattern(
            type = PatternType.ASCENDING_TRIANGLE,
            confirmed = 6
        )
        val setup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.PatternBoundary(
                        pattern,
                        PatternBoundaryRole.TRIANGLE_FLAT_TOP
                    )
                )
            ),
            events = listOf(
                BrokenEvent(5, t0.plusSeconds(5), 101.0),
                com.algotrader.intelligence.setup.RetestTouchedEvent(6, t0.plusSeconds(6), 101.0),
                com.algotrader.intelligence.setup.RetestHeldEvent(7, t0.plusSeconds(7), 101.0)
            )
        )

        val structure = structure(
            count = 8,
            state = MarketStructureState.UPTREND,
            pivots = listOf(
                pivot(PivotType.LOW, 90.0, 2, 3),
                pivot(PivotType.HIGH, 100.0, 3, 4)
            ),
            zones = listOf(
                PriceZone(
                    kind = ZoneKind.SUPPORT,
                    low = 99.0,
                    high = 99.5,
                    touches = 2,
                    firstIndex = 2,
                    lastIndex = 3,
                    lastConfirmedIndex = 4
                )
            )
        )

        val context = SetupContextExtractor.extract(
            setup,
            structure,
            patternAnalysis(8, pattern),
            asOfIndex = 7
        )

        val assessment = SetupConfluenceAssessmentEngine.assess(context)

        assertEquals(SetupQuality.STRONG, assessment.quality)
        assertEquals(3, assessment.supportingFactorCount)
        assertEquals(0, assessment.conflictingFactorCount)
        assertEquals(SetupStage.RETEST_HELD, context.lifecycleStage)
    }

    @Test
    fun `opposing overhead level creates conflict and caps quality`() {
        val setup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(LevelOrigin.Pivot(pivot(PivotType.HIGH, 100.0, 3, 4)))
            ),
            events = listOf(BrokenEvent(5, t0.plusSeconds(5), 101.0))
        )

        val structure = structure(
            count = 6,
            state = MarketStructureState.UPTREND,
            pivots = listOf(
                pivot(PivotType.LOW, 99.0, 2, 3),
                pivot(PivotType.HIGH, 100.0, 3, 4),
                pivot(PivotType.HIGH, 100.4, 4, 5)
            )
        )

        val context = SetupContextExtractor.extract(
            setup,
            structure,
            patternAnalysis(6),
            asOfIndex = 5
        )

        val assessment = SetupConfluenceAssessmentEngine.assess(context)

        assertEquals(SetupQuality.WEAK, assessment.quality)
        assertTrue(
            assessment.conflictingFactorKinds.contains(
                SetupContextFactorKind.LEVEL_CONTEXT
            )
        )
    }

    @Test
    fun `duplicate level origins are merged and do not double count confluence`() {
        val setup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.Pivot(pivot(PivotType.HIGH, 100.0, 3, 4))
                )
            ),
            events = listOf(BrokenEvent(5, t0.plusSeconds(5), 101.0))
        )

        val structure = structure(
            count = 6,
            state = MarketStructureState.UPTREND,
            pivots = listOf(
                pivot(PivotType.LOW, 99.5, 2, 3),
                pivot(PivotType.LOW, 99.55, 3, 4)
            ),
            zones = listOf(
                PriceZone(
                    kind = ZoneKind.SUPPORT,
                    low = 99.5,
                    high = 99.55,
                    touches = 2,
                    firstIndex = 2,
                    lastIndex = 3,
                    lastConfirmedIndex = 4
                )
            )
        )

        val context = SetupContextExtractor.extract(
            setup,
            structure,
            patternAnalysis(6),
            asOfIndex = 5
        )

        val level = context.relevantLevels.single()

        assertEquals(ZoneKind.SUPPORT, level.side)
        assertEquals(3, level.sourceIds.size)
        assertEquals(level.sourceIds.distinct().sorted(), level.sourceIds)
        assertEquals(3, level.sourceIds.size)
        assertEquals(
            listOf(
                "PIVOT#2:LOW",
                "PIVOT#3:LOW",
                "ZONE#SUPPORT:99.5:99.55:3"
            ),
            level.sourceIds
        )
        assertEquals(
            listOf(SetupContextFactorKind.LEVEL_CONTEXT, SetupContextFactorKind.MARKET_STRUCTURE),
            context.supportingFactors.map { it.kind }
        )
    }

    @Test
    fun `future pattern and future lifecycle event are excluded`() {
        val futurePattern = pattern(
            type = PatternType.ASCENDING_TRIANGLE,
            confirmed = 8
        )

        val setup = setup(
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
                com.algotrader.intelligence.setup.RetestTouchedEvent(7, t0.plusSeconds(7), 101.0),
                com.algotrader.intelligence.setup.RetestHeldEvent(8, t0.plusSeconds(8), 101.0)
            )
        )

        val context = SetupContextExtractor.extract(
            setup,
            structure(
                count = 6,
                state = MarketStructureState.UNDEFINED
            ),
            patternAnalysis(6),
            asOfIndex = 5
        )

        assertTrue(context.factors.isEmpty())
        assertEquals(SetupStage.BROKEN, context.lifecycleStage)
    }

    @Test
    fun `exact as of pattern and retest are included`() {
        val pattern = pattern(
            type = PatternType.DOUBLE_BOTTOM,
            confirmed = 6
        )

        val setup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.PatternBoundary(
                        pattern,
                        PatternBoundaryRole.NECKLINE
                    )
                )
            ),
            events = listOf(
                BrokenEvent(5, t0.plusSeconds(5), 101.0),
                com.algotrader.intelligence.setup.RetestTouchedEvent(6, t0.plusSeconds(6), 101.0),
                com.algotrader.intelligence.setup.RetestHeldEvent(6, t0.plusSeconds(6), 101.0)
            )
        )

        val context = SetupContextExtractor.extract(
            setup,
            structure(7, MarketStructureState.UNDEFINED),
            patternAnalysis(7, pattern),
            asOfIndex = 6
        )

        assertEquals(SetupStage.RETEST_HELD, context.lifecycleStage)
        assertEquals(
            SetupContextPolarity.SUPPORTING,
            context.factors.single { it.kind == SetupContextFactorKind.PATTERN_CONTEXT }.polarity
        )
        assertTrue(
            context.factors.any {
                it.kind == SetupContextFactorKind.LIFECYCLE_CONTEXT &&
                    it.knownAtIndex == 6
            }
        )
    }

    @Test
    fun `failed lifecycle is conflicting`() {
        val setup = setup(
            direction = BreakoutDirection.DOWN,
            level = BreakoutLevel(
                side = ZoneKind.SUPPORT,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.Pivot(pivot(PivotType.LOW, 100.0, 3, 4))
                )
            ),
            events = listOf(
                BrokenEvent(5, t0.plusSeconds(5), 101.0),
                com.algotrader.intelligence.setup.FailedEvent(7, t0.plusSeconds(7), 99.0, false, false)
            )
        )

        val context = SetupContextExtractor.extract(
            setup,
            structure(8, MarketStructureState.UNDEFINED),
            patternAnalysis(8),
            asOfIndex = 7
        )

        val factor = context.factors.single()
        assertEquals(SetupContextFactorKind.LIFECYCLE_CONTEXT, factor.kind)
        assertEquals(SetupContextPolarity.CONFLICTING, factor.polarity)
    }

    @Test
    fun `insufficient context has insufficient quality`() {
        val setup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.Pivot(pivot(PivotType.HIGH, 100.0, 3, 4))
                )
            ),
            events = listOf(BrokenEvent(5, t0.plusSeconds(5), 101.0))
        )

        val context = SetupContextExtractor.extract(
            setup,
            structure(6, MarketStructureState.UNDEFINED),
            patternAnalysis(6),
            asOfIndex = 5
        )

        val assessment = SetupConfluenceAssessmentEngine.assess(context)

        assertEquals(SetupQuality.INSUFFICIENT, assessment.quality)
    }

    @Test
    fun `full series analysis is rejected to protect no lookahead`() {
        val setup = setup(
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.Pivot(pivot(PivotType.HIGH, 100.0, 3, 4))
                )
            ),
            events = listOf(BrokenEvent(5, t0.plusSeconds(5), 101.0))
        )

        assertFailsWith<IllegalArgumentException> {
            SetupContextExtractor.extract(
                setup,
                structure(10, MarketStructureState.UPTREND),
                patternAnalysis(10),
                asOfIndex = 5
            )
        }
    }

    @Test
    fun `same inputs produce identical context`() {
        val setup = setup(
            direction = BreakoutDirection.DOWN,
            level = BreakoutLevel(
                side = ZoneKind.SUPPORT,
                price = 100.0,
                origins = listOf(
                    LevelOrigin.Pivot(pivot(PivotType.LOW, 100.0, 3, 4))
                )
            ),
            events = listOf(
                BrokenEvent(5, t0.plusSeconds(5), 101.0),
                com.algotrader.intelligence.setup.RetestTouchedEvent(6, t0.plusSeconds(6), 101.0),
                com.algotrader.intelligence.setup.RetestHeldEvent(7, t0.plusSeconds(7), 101.0)
            )
        )

        val structure = structure(
            count = 8,
            state = MarketStructureState.DOWNTREND,
            pivots = listOf(
                pivot(PivotType.HIGH, 101.0, 2, 3)
            )
        )

        val patterns = patternAnalysis(8)

        val a = SetupContextExtractor.extract(setup, structure, patterns, 7)
        val b = SetupContextExtractor.extract(setup, structure, patterns, 7)

        assertEquals(a, b)
        assertEquals(
            a.factors.map { it.id },
            a.factors.map { it.id }.sorted()
        )
    }

    @Test
    fun `assessment counts independent factor kinds only once`() {
        val context = SetupContext(
            breakoutId = BreakoutId("b"),
            direction = BreakoutDirection.UP,
            asOfIndex = 10,
            marketStructure = MarketStructureState.UPTREND,
            lifecycleStage = SetupStage.RETEST_HELD,
            relevantLevels = emptyList(),
            factors = listOf(
                SetupContextFactor(
                    id = "pattern-1",
                    kind = SetupContextFactorKind.PATTERN_CONTEXT,
                    polarity = SetupContextPolarity.SUPPORTING,
                    knownAtIndex = 5,
                    summary = "pattern"
                ),
                SetupContextFactor(
                    id = "pattern-2",
                    kind = SetupContextFactorKind.PATTERN_CONTEXT,
                    polarity = SetupContextPolarity.SUPPORTING,
                    knownAtIndex = 6,
                    summary = "pattern"
                ),
                SetupContextFactor(
                    id = "structure",
                    kind = SetupContextFactorKind.MARKET_STRUCTURE,
                    polarity = SetupContextPolarity.SUPPORTING,
                    knownAtIndex = 7,
                    summary = "structure"
                )
            ).sortedBy { it.id }
        )

        val assessment = SetupConfluenceAssessmentEngine.assess(context)

        assertEquals(2, assessment.supportingFactorCount)
        assertEquals(SetupQuality.MODERATE, assessment.quality)
    }

    private fun setup(
        direction: BreakoutDirection,
        level: BreakoutLevel,
        events: List<SetupEvent>
    ): BreakoutSetup {
        val breakout = Breakout(
            id = BreakoutId("ctx-test"),
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

        val qualification = com.algotrader.intelligence.setup.BreakoutQualification(
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

    private fun structure(
        count: Int,
        state: MarketStructureState,
        pivots: List<SwingPivot> = emptyList(),
        zones: List<PriceZone> = emptyList()
    ): StructureAnalysis =
        StructureAnalysis(
            status = AnalysisStatus.OK,
            message = "",
            candleCount = count,
            pivots = pivots.map {
                ClassifiedPivot(
                    pivot = it,
                    label = StructureLabel.FIRST
                )
            },
            state = state,
            zones = zones,
            atr = null
        )

    private fun patternAnalysis(
        count: Int,
        vararg patterns: DetectedPattern
    ): PatternAnalysis =
        PatternAnalysis(
            status = AnalysisStatus.OK,
            message = "",
            candleCount = count,
            patterns = patterns.toList()
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

    private fun pivot(
        type: PivotType,
        price: Double,
        index: Int,
        confirmedIndex: Int
    ): SwingPivot =
        SwingPivot(
            type = type,
            index = index,
            timestamp = t0.plusSeconds(index.toLong()),
            price = price,
            confirmedIndex = confirmedIndex,
            confirmedAt = t0.plusSeconds(confirmedIndex.toLong()),
            strength = 2,
            prominence = 1.0
        )
}
