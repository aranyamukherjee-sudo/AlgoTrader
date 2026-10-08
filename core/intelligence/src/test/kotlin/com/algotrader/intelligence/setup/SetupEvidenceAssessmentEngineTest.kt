package com.algotrader.intelligence.setup

import com.algotrader.intelligence.TestFixtures
import com.algotrader.domain.Candle
import com.algotrader.intelligence.confidence.ConfidenceHistory
import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupEvidenceAssessmentEngineTest {

    private val strategy: StrategyRef =
        TestFixtures.priceActionDna().ref

    private val t0: Instant =
        Instant.parse("2026-10-06T04:00:00Z")

    private fun at(index: Int): Instant =
        t0.plusSeconds(index.toLong() * 60L)

    private fun pivot(
        type: PivotType,
        index: Int,
        confirmedIndex: Int,
        price: Double
    ): SwingPivot =
        SwingPivot(
            type = type,
            index = index,
            timestamp = at(index),
            price = price,
            confirmedIndex = confirmedIndex,
            confirmedAt = at(confirmedIndex),
            strength = 1,
            prominence = 1.0
        )

    private fun pattern(
        confirmedIndex: Int
    ): DetectedPattern {
        val pivots = listOf(
            pivot(PivotType.LOW, 0, 0, 95.0),
            pivot(PivotType.HIGH, 1, 1, 105.0),
            pivot(PivotType.LOW, 2, 2, 96.0)
        )

        return DetectedPattern(
            type = PatternType.DOUBLE_BOTTOM,
            pivots = pivots,
            startIndex = 0,
            endIndex = 2,
            confirmedIndex = confirmedIndex,
            confirmedAt = at(confirmedIndex),
            high = 105.0,
            low = 95.0,
            neckline = 105.0
        )
    }

    private fun breakout(
        confirmedIndex: Int = 3,
        withPattern: Boolean = true
    ): Breakout {
        val origins = mutableListOf<LevelOrigin>()

        origins += LevelOrigin.Pivot(
            pivot(
                type = PivotType.HIGH,
                index = 0,
                confirmedIndex = 0,
                price = 105.0
            )
        )

        if (withPattern) {
            origins += LevelOrigin.PatternBoundary(
                pattern = pattern(confirmedIndex = 2),
                role = PatternBoundaryRole.NECKLINE
            )
        }

        return Breakout(
            id = BreakoutId("UP:105.0:a1"),
            direction = BreakoutDirection.UP,
            level = BreakoutLevel(
                side = ZoneKind.RESISTANCE,
                price = 105.0,
                origins = origins
            ),
            attempt = 1,
            breakIndex = confirmedIndex,
            breakAt = at(confirmedIndex),
            breakClose = 106.0,
            confirmedIndex = confirmedIndex,
            confirmedAt = at(confirmedIndex),
            buffer = 0.0,
            trigger = 105.0,
            retestBand = null,
            deadlines = LifecycleDeadlines(
                retestUntil = confirmedIndex + 3,
                failureUntil = confirmedIndex + 5,
                continuationUntil = confirmedIndex + 8,
                expiresAt = confirmedIndex + 8
            )
        )
    }

    private fun qualification(
        trendStatus: CheckStatus = CheckStatus.PASS,
        rangeStatus: CheckStatus = CheckStatus.PASS
    ): BreakoutQualification =
        BreakoutQualification(
            status = if (
                trendStatus == CheckStatus.PASS &&
                rangeStatus == CheckStatus.PASS
            ) {
                QualificationStatus.QUALIFIED
            } else {
                QualificationStatus.UNQUALIFIED
            },
            checks = listOf(
                QualificationCheck(
                    criterion = QualificationCriterion.TREND_ALIGNMENT,
                    required = true,
                    status = trendStatus,
                    observed = null,
                    requiredValue = null,
                    trendState = MarketStructureState.UPTREND
                ),
                QualificationCheck(
                    criterion = QualificationCriterion.BREAK_DISTANCE,
                    required = false,
                    status = CheckStatus.UNAVAILABLE,
                    observed = null,
                    requiredValue = null,
                    trendState = null
                ),
                QualificationCheck(
                    criterion = QualificationCriterion.CLOSE_LOCATION,
                    required = false,
                    status = CheckStatus.UNAVAILABLE,
                    observed = null,
                    requiredValue = null,
                    trendState = null
                ),
                QualificationCheck(
                    criterion = QualificationCriterion.RANGE_EXPANSION,
                    required = true,
                    status = rangeStatus,
                    observed = if (
                        rangeStatus == CheckStatus.PASS
                    ) 1.8 else 0.4,
                    requiredValue = 1.0,
                    trendState = null
                ),
                QualificationCheck(
                    criterion = QualificationCriterion.VOLUME_CONFIRMATION,
                    required = false,
                    status = CheckStatus.UNAVAILABLE,
                    observed = null,
                    requiredValue = null,
                    trendState = null
                )
            )
        )

    private fun setup(
        patternConfirmedIndex: Int = 2,
        events: List<SetupEvent> = listOf(
            BrokenEvent(3, at(3), 106.0)
        ),
        trendStatus: CheckStatus = CheckStatus.PASS,
        rangeStatus: CheckStatus = CheckStatus.PASS,
        withPattern: Boolean = true
    ): BreakoutSetup {
        val baseBreakout = breakout(
            confirmedIndex = 3,
            withPattern = withPattern
        )

        val adjustedBreakout =
            if (withPattern) {
                val adjustedPattern = pattern(patternConfirmedIndex)

                baseBreakout.copy(
                    level = baseBreakout.level.copy(
                        origins = listOf(
                            baseBreakout.level.origins.first(),
                            LevelOrigin.PatternBoundary(
                                adjustedPattern,
                                PatternBoundaryRole.NECKLINE
                            )
                        )
                    )
                )
            } else {
                baseBreakout
            }

        return BreakoutSetup(
            breakout = adjustedBreakout,
            qualification = qualification(
                trendStatus,
                rangeStatus
            ),
            events = events
        )
    }

    @Test
    fun `composes evidence and confidence into one live assessment`() {
        val setup = setup()

        val result = SetupEvidenceAssessmentEngine.assess(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3)
        )

        assertEquals(
            setup.breakout.id,
            result.evidenceAssessment?.breakoutId
        )
        assertEquals(
            3,
            result.evidenceAssessment?.asOfIndex
        )
        assertEquals(
            at(3),
            result.evidenceAssessment?.asOf
        )
        assertEquals(
            result.evidence,
            result.evidenceAssessment?.evidence
        )
        assertEquals(
            result.reading,
            result.evidenceAssessment?.confidence
        )
        assertTrue(result.isActionable)
    }

    @Test
    fun `propagates confidence degradation and contradiction`() {
        val setup = setup(
            trendStatus = CheckStatus.FAIL,
            rangeStatus = CheckStatus.FAIL
        )

        val prior = ConfidenceHistory(
            listOf(
                ConfidenceReading(
                    value = 1.0,
                    at = at(2)
                )
            )
        )

        val result = SetupEvidenceAssessmentEngine.assess(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3),
            prior = prior
        )

        assertEquals(
            SetupConfidenceCondition.CONTRADICTING,
            result.condition
        )
        assertEquals(
            SetupConfidenceChange.DEGRADED,
            result.change
        )
        assertTrue(result.isDegraded)
        assertTrue(result.isContradicting)
        assertTrue(result.isActionable)
        assertNotNull(result.reading)
        assertNotNull(result.evidenceAssessment)
    }

    @Test
    fun `preserves insufficient evidence without fabricating confidence`() {
        val setup = setup(
            withPattern = false,
            trendStatus = CheckStatus.UNAVAILABLE,
            rangeStatus = CheckStatus.UNAVAILABLE
        )

        val result = SetupEvidenceAssessmentEngine.assess(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3)
        )

        assertTrue(result.evidence.isEmpty)
        assertEquals(
            SetupConfidenceCondition.INSUFFICIENT,
            result.condition
        )
        assertEquals(
            SetupConfidenceChange.INITIAL,
            result.change
        )
        assertFalse(result.isActionable)
        assertNull(result.reading)
        assertNull(result.evidenceAssessment)
    }

    @Test
    fun `future pattern is excluded at earlier asOf index`() {
        val setup = setup(
            patternConfirmedIndex = 5
        )

        val result = SetupEvidenceAssessmentEngine.assess(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3)
        )

        assertTrue(
            result.evidence.ofKind(
                com.algotrader.intelligence.evidence.EvidenceKind.CURRENT_PATTERN_MATCH
            ).isEmpty()
        )
    }

    @Test
    fun `same inputs produce identical assessment`() {
        val setup = setup()

        val first = SetupEvidenceAssessmentEngine.assess(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3)
        )

        val second = SetupEvidenceAssessmentEngine.assess(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3)
        )

        assertEquals(first, second)
    }

    @Test
    fun `rejects assessment before breakout confirmation`() {
        val setup = setup()

        val exception = runCatching {
            SetupEvidenceAssessmentEngine.assess(
                setup = setup,
                strategy = strategy,
                asOfIndex = 2,
                asOf = at(2)
            )
        }.exceptionOrNull()

        assertNotNull(exception)
        assertTrue(
            exception.message.orEmpty().contains(
                "asOfIndex must not precede breakout confirmation"
            )
        )
    }
}
