package com.algotrader.intelligence.setup

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.ClassifiedPivot
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.StructureLabel
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.ZoneKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreakoutQualifierTest {

    private val instrument = Instrument("A4-FIXTURE", "NSE")

    private fun candles(
        closes: List<Double>,
        volumes: List<Double> = List(closes.size) { 100.0 }
    ): List<Candle> =
        closes.mapIndexed { i, close ->
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_5,
                timestamp = Instant.parse("2026-01-01T04:00:00Z")
                    .plusSeconds(i * 300L),
                open = close,
                high = close + 1.0,
                low = close - 1.0,
                close = close,
                volume = volumes[i]
            )
        }

    private fun structure(
        candles: List<Candle>,
        pivots: List<ClassifiedPivot> = emptyList()
    ) = StructureAnalysis(
        status = AnalysisStatus.OK,
        message = "ok",
        candleCount = candles.size,
        pivots = pivots,
        state = MarketStructureState.UNDEFINED,
        zones = emptyList(),
        atr = null
    )

    private fun breakout(
        candles: List<Candle>,
        direction: BreakoutDirection,
        level: Double,
        confirmedIndex: Int
    ): Breakout {
        val pivot = SwingPivot(
            type = if (direction == BreakoutDirection.UP) PivotType.HIGH else PivotType.LOW,
            index = 0,
            timestamp = candles[0].timestamp,
            price = level,
            confirmedIndex = 0,
            confirmedAt = candles[0].timestamp,
            strength = 1,
            prominence = 1.0
        )
        val breakoutLevel = BreakoutLevel(
            side = if (direction == BreakoutDirection.UP)
                ZoneKind.RESISTANCE else ZoneKind.SUPPORT,
            price = level,
            origins = listOf(LevelOrigin.Pivot(pivot))
        )
        val trigger = if (direction == BreakoutDirection.UP) level else level
        return Breakout(
            id = BreakoutId("${direction.name}:$level:a1"),
            direction = direction,
            level = breakoutLevel,
            attempt = 1,
            breakIndex = confirmedIndex,
            breakAt = candles[confirmedIndex].timestamp,
            breakClose = candles[confirmedIndex].close,
            confirmedIndex = confirmedIndex,
            confirmedAt = candles[confirmedIndex].timestamp,
            buffer = 0.0,
            trigger = trigger,
            retestBand = null,
            deadlines = LifecycleDeadlines(
                retestUntil = confirmedIndex + 1,
                failureUntil = confirmedIndex + 2,
                continuationUntil = confirmedIndex + 3,
                expiresAt = confirmedIndex + 3
            )
        )
    }

    private fun pivot(
        type: PivotType,
        index: Int,
        confirmedIndex: Int,
        price: Double,
        candles: List<Candle>
    ) = ClassifiedPivot(
        pivot = SwingPivot(
            type = type,
            index = index,
            timestamp = candles[index].timestamp,
            price = price,
            confirmedIndex = confirmedIndex,
            confirmedAt = candles[confirmedIndex].timestamp,
            strength = 1,
            prominence = 1.0
        ),
        label = StructureLabel.FIRST
    )

    @Test
    fun `range trend passes for both breakout directions`() {
        val c = candles(listOf(99.0, 101.0, 102.0))
        val s = structure(
            c,
            listOf(
                pivot(PivotType.HIGH, 0, 0, 100.0, c).copy(label = StructureLabel.EQH),
                pivot(PivotType.LOW, 1, 1, 99.0, c).copy(label = StructureLabel.EQL)
            )
        )

        val up = BreakoutQualifier.qualify(
            c, s, breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig()
        )
        val down = BreakoutQualifier.qualify(
            c, s, breakout(c, BreakoutDirection.DOWN, 100.0, 1),
            QualificationConfig()
        )

        assertEquals(CheckStatus.PASS, up.checks[0].status)
        assertEquals(CheckStatus.PASS, down.checks[0].status)
    }

    @Test
    fun `opposite trend fails required alignment`() {
        val c = candles(listOf(99.0, 101.0, 102.0))
        val asTrend = structure(
            c,
            listOf(
                pivot(PivotType.HIGH, 0, 0, 100.0, c)
                    .copy(label = StructureLabel.LH),
                pivot(PivotType.LOW, 1, 1, 99.0, c)
                    .copy(label = StructureLabel.LL)
            )
        )

        val result = BreakoutQualifier.qualify(
            c,
            asTrend,
            breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig()
        )

        assertEquals(MarketStructureState.DOWNTREND, result.checks[0].trendState)
        assertEquals(CheckStatus.FAIL, result.checks[0].status)
        assertEquals(QualificationStatus.UNQUALIFIED, result.status)
    }

    @Test
    fun `undefined trend is unavailable and required alignment fails qualification`() {
        val c = candles(listOf(99.0, 101.0))
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig()
        )

        assertEquals(CheckStatus.UNAVAILABLE, result.checks[0].status)
        assertEquals(MarketStructureState.UNDEFINED, result.checks[0].trendState)
        assertEquals(QualificationStatus.UNQUALIFIED, result.status)
    }

    @Test
    fun `break distance uses configured threshold`() {
        val c = candles(listOf(99.0, 103.0, 104.0))
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig(
                minBreakDistance = BufferRule(absolute = 2.0)
            )
        )

        val check = result.checks.first {
            it.criterion == QualificationCriterion.BREAK_DISTANCE
        }
        assertEquals(CheckStatus.PASS, check.status)
        assertEquals(3.0, check.observed)
        assertEquals(2.0, check.requiredValue)
    }

    @Test
    fun `close location is directional`() {
        val c = candles(listOf(99.0, 101.8, 102.0)).mapIndexed { i, candle ->
            if (i == 1) {
                candle.copy(
                    high = 102.0,
                    low = 100.0
                )
            } else {
                candle
            }
        }
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig(minCloseLocation = 0.8)
        )

        val check = result.checks.first {
            it.criterion == QualificationCriterion.CLOSE_LOCATION
        }
        assertEquals(CheckStatus.PASS, check.status)
        assertNotNull(check.observed)
    }

    @Test
    fun `range expansion requires ATR`() {
        val c = candles(listOf(99.0, 101.0))
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig(minRangeAtr = 1.0)
        )

        val check = result.checks.first {
            it.criterion == QualificationCriterion.RANGE_EXPANSION
        }
        assertEquals(CheckStatus.UNAVAILABLE, check.status)
    }

    @Test
    fun `volume compares only prior candles`() {
        val c = candles(
            closes = listOf(99.0, 101.0, 102.0, 103.0),
            volumes = listOf(100.0, 100.0, 300.0, 99999.0)
        )
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 2),
            QualificationConfig(
                volume = VolumeRule(lookback = 2, minRatio = 1.5)
            )
        )

        val check = result.checks.first {
            it.criterion == QualificationCriterion.VOLUME_CONFIRMATION
        }
        assertEquals(CheckStatus.PASS, check.status)
        assertEquals(3.0, check.observed)
    }

    @Test
    fun `disabled criteria are optional and unavailable`() {
        val c = candles(listOf(99.0, 101.0))
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig()
        )

        result.checks.drop(1).forEach {
            assertEquals(false, it.required)
            assertEquals(CheckStatus.UNAVAILABLE, it.status)
        }
    }

    @Test
    fun `required unavailable criterion makes qualification unqualified`() {
        val c = candles(listOf(99.0, 101.0))
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 1),
            QualificationConfig(
                minRangeAtr = 1.0
            )
        )

        assertEquals(
            QualificationStatus.UNQUALIFIED,
            result.status
        )
    }

    @Test
    fun `atr period is configurable and validated`() {
        val c = candles(List(15) { 100.0 + it })
        val result = BreakoutQualifier.qualify(
            c,
            structure(c),
            breakout(c, BreakoutDirection.UP, 100.0, 14),
            QualificationConfig(
                minRangeAtr = 0.1,
                atrPeriod = 5
            )
        )

        val check = result.checks.first {
            it.criterion == QualificationCriterion.RANGE_EXPANSION
        }

        assertEquals(CheckStatus.PASS, check.status)
        assertNotNull(check.observed)

        assertFailsWith<IllegalArgumentException> {
            QualificationConfig(atrPeriod = 0)
        }
    }

    @Test
    fun `repeated qualification is deterministic`() {
        val c = candles(listOf(99.0, 101.0, 102.0))
        val s = structure(c)
        val b = breakout(c, BreakoutDirection.UP, 100.0, 1)
        val config = QualificationConfig(
            minBreakDistance = BufferRule(absolute = 0.5),
            minCloseLocation = 0.5,
            volume = VolumeRule(lookback = 1, minRatio = 1.0)
        )

        val first = BreakoutQualifier.qualify(c, s, b, config)
        val second = BreakoutQualifier.qualify(c, s, b, config)

        assertEquals(first, second)
    }
}
