package com.algotrader.intelligence.setup

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.TestFixtures
import com.algotrader.intelligence.dna.EvidenceRef
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidencePolarity
import com.algotrader.intelligence.evidence.EvidenceSample
import com.algotrader.intelligence.evidence.EvidenceSourceType
import com.algotrader.intelligence.evidence.EvidenceUnit
import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetupEvidenceExtractorTest {

    private val instrument = Instrument("ASI-3.4.2-FIXTURE", "NSE")
    private val time0 = Instant.parse("2026-10-06T04:00:00Z")

    private fun at(index: Int): Instant =
        time0.plusSeconds(index * 300L)

    private fun candle(index: Int, close: Double = 100.0): Candle =
        Candle(
            instrument = instrument,
            timeframe = Timeframe.MINUTE_5,
            timestamp = at(index),
            open = close,
            high = close + 1.0,
            low = close - 1.0,
            close = close,
            volume = 100.0
        )

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
                    observed = if (rangeStatus == CheckStatus.PASS) 1.8 else 0.4,
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
        rangeStatus: CheckStatus = CheckStatus.PASS
    ): BreakoutSetup {
        val breakout = breakout(
            confirmedIndex = 3,
            withPattern = true
        )

        val adjustedPattern = pattern(patternConfirmedIndex)
        val adjustedBreakout = breakout.copy(
            level = breakout.level.copy(
                origins = listOf(
                    breakout.level.origins.first(),
                    LevelOrigin.PatternBoundary(
                        adjustedPattern,
                        PatternBoundaryRole.NECKLINE
                    )
                )
            )
        )

        return BreakoutSetup(
            breakout = adjustedBreakout,
            qualification = qualification(trendStatus, rangeStatus),
            events = events
        )
    }

    @Test
    fun `extracts live pattern trend and volatility evidence`() {
        val setup = setup()
        val strategy = TestFixtures.priceActionDna().ref

        val ledger = SetupEvidenceExtractor.extract(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3)
        )

        assertTrue(ledger.ofKind(EvidenceKind.CURRENT_PATTERN_MATCH).isNotEmpty())
        assertEquals(1, ledger.ofKind(EvidenceKind.TREND_ALIGNMENT).size)
        assertEquals(1, ledger.ofKind(EvidenceKind.VOLATILITY_CONDITION).size)
        assertTrue(ledger.items.all { it.sample == EvidenceSample.LIVE })
        assertTrue(
            ledger.items.all {
                it.source.type == EvidenceSourceType.LIVE_MARKET_DATA
            }
        )
    }

    @Test
    fun `pattern confirmed after asOf is excluded`() {
        val setup = setup(patternConfirmedIndex = 5)
        val strategy = TestFixtures.priceActionDna().ref

        val ledger = SetupEvidenceExtractor.extract(
            setup = setup,
            strategy = strategy,
            asOfIndex = 3,
            asOf = at(3)
        )

        assertTrue(
            ledger.ofKind(EvidenceKind.CURRENT_PATTERN_MATCH).isEmpty()
        )
    }

    @Test
    fun `qualification polarity reflects existing checks`() {
        val setup = setup(
            trendStatus = CheckStatus.FAIL,
            rangeStatus = CheckStatus.PASS
        )
        val strategy = TestFixtures.priceActionDna().ref

        val ledger = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            3,
            at(3)
        )

        assertEquals(
            EvidencePolarity.CONTRADICTING,
            ledger.ofKind(EvidenceKind.TREND_ALIGNMENT).single().polarity
        )
        assertEquals(
            EvidencePolarity.SUPPORTING,
            ledger.ofKind(EvidenceKind.VOLATILITY_CONDITION).single().polarity
        )
    }

    @Test
    fun `retest evidence respects asOfIndex`() {
        val events = listOf(
            BrokenEvent(3, at(3), 106.0),
            RetestTouchedEvent(4, at(4), 105.2),
            RetestHeldEvent(5, at(5), 106.1)
        )

        val setup = setup(events = events)
        val strategy = TestFixtures.priceActionDna().ref

        val early = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            4,
            at(4)
        )
        val late = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            5,
            at(5)
        )

        assertTrue(
            early.ofKind(
                EvidenceKind.BREAKOUT_RETEST_CONFIRMATION
            ).isEmpty()
        )

        assertEquals(
            1,
            late.ofKind(
                EvidenceKind.BREAKOUT_RETEST_CONFIRMATION
            ).size
        )
    }

    @Test
    fun `unavailable qualification checks produce no evidence`() {
        val setup = setup(
            trendStatus = CheckStatus.UNAVAILABLE,
            rangeStatus = CheckStatus.UNAVAILABLE
        )
        val strategy = TestFixtures.priceActionDna().ref

        val ledger = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            3,
            at(3)
        )

        assertTrue(
            ledger.ofKind(EvidenceKind.TREND_ALIGNMENT).isEmpty()
        )
        assertTrue(
            ledger.ofKind(EvidenceKind.VOLATILITY_CONDITION).isEmpty()
        )
    }

    @Test
    fun `evidence facts do not encode index as a numeric magnitude`() {
        val setup = setup(
            events = listOf(
                BrokenEvent(3, at(3), 106.0),
                RetestTouchedEvent(4, at(4), 105.2),
                RetestHeldEvent(5, at(5), 106.1)
            )
        )
        val strategy = TestFixtures.priceActionDna().ref

        val ledger = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            5,
            at(5)
        )

        assertTrue(
            ledger.items.all {
                it.value == null || it.unit != EvidenceUnit.COUNT
            }
        )
    }

    @Test
    fun `terminal future event does not leak into earlier assessment`() {
        val events = listOf(
            BrokenEvent(3, at(3), 106.0),
            RetestTouchedEvent(4, at(4), 105.2),
            RetestHeldEvent(5, at(5), 106.1),
            ContinuedEvent(
                index = 6,
                at = at(6),
                close = 108.0,
                extremeBeaten = 108.5,
                viaRetest = true
            )
        )

        val setup = setup(events = events)
        val strategy = TestFixtures.priceActionDna().ref

        val ledger = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            5,
            at(5)
        )

        assertTrue(
            ledger.items.none {
                it.id.id.contains("CONTINUED")
            }
        )
    }

    @Test
    fun `same inputs produce identical ledger`() {
        val setup = setup()
        val strategy = TestFixtures.priceActionDna().ref

        val first = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            3,
            at(3)
        )
        val second = SetupEvidenceExtractor.extract(
            setup,
            strategy,
            3,
            at(3)
        )

        assertEquals(first, second)
        assertEquals(
            first.items.map { it.id },
            first.items.map { it.id }.sortedBy(EvidenceRef::id)
        )
    }

    @Test
    fun `assessment before breakout confirmation is rejected`() {
        val setup = setup()
        val strategy = TestFixtures.priceActionDna().ref

        assertFailsWith<IllegalArgumentException> {
            SetupEvidenceExtractor.extract(
                setup,
                strategy,
                2,
                at(2)
            )
        }
    }
}
