package com.algotrader.intelligence.setup

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.pattern.PatternConfig
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PriceZone
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import com.algotrader.intelligence.structure.PivotType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BreakoutDetectorTest {

    private val instrument = Instrument("TEST", "NSE")
    private val timeframe = Timeframe.MINUTE_5

    private fun candle(
        index: Int,
        close: Double,
        high: Double = close + 0.5,
        low: Double = close - 0.5
    ) = Candle(
        instrument = instrument,
        timeframe = timeframe,
        timestamp = Instant.parse("2026-10-06T04:00:00Z").plusSeconds(index * 300L),
        open = close,
        high = high,
        low = low,
        close = close,
        volume = 1000.0
    )

    private fun candles(vararg closes: Double): List<Candle> =
        closes.mapIndexed { index, close -> candle(index, close) }

    private fun level(
        side: ZoneKind,
        price: Double,
        knownAt: Int = 0
    ): BreakoutLevel {
        val pivot = SwingPivot(
            type = if (side == ZoneKind.RESISTANCE) PivotType.HIGH else PivotType.LOW,
            index = knownAt,
            timestamp = Instant.parse("2026-10-06T04:00:00Z").plusSeconds(knownAt * 300L),
            price = price,
            confirmedIndex = knownAt,
            confirmedAt = Instant.parse("2026-10-06T04:00:00Z").plusSeconds(knownAt * 300L),
            strength = 1,
            prominence = 1.0
        )
        return BreakoutLevel(
            side = side,
            price = price,
            origins = listOf(LevelOrigin.Pivot(pivot))
        )
    }

    private fun structure(
        count: Int,
        levels: List<BreakoutLevel>
    ): StructureAnalysis {
        return StructureAnalysis(
            status = AnalysisStatus.OK,
            message = "test",
            candleCount = count,
            pivots = emptyList(),
            state = MarketStructureState.RANGE,
            zones = levels.map {
                PriceZone(
                    kind = it.side,
                    low = it.price,
                    high = it.price,
                    touches = 2,
                    firstIndex = 0,
                    lastIndex = 0,
                    lastConfirmedIndex = 0
                )
            },
            atr = null
        )
    }

    private fun patterns(count: Int) = PatternAnalysis(
        status = AnalysisStatus.OK,
        message = "test",
        candleCount = count,
        patterns = emptyList()
    )

    /*
     * These tests exercise the detector through the actual A.2 extractor.
     * Pivot levels are used explicitly so the tests do not depend on
     * SupportResistance clustering.
     */
    private fun detect(
        candles: List<Candle>,
        level: BreakoutLevel,
        confirmationCloses: Int = 1,
        buffer: BufferRule = BufferRule()
    ): List<Breakout> {
        val pivot = (level.origins.single() as LevelOrigin.Pivot).pivot

        val structure = StructureAnalysis(
            status = AnalysisStatus.OK,
            message = "test",
            candleCount = candles.size,
            pivots = listOf(
                com.algotrader.intelligence.structure.ClassifiedPivot(
                    pivot,
                    com.algotrader.intelligence.structure.StructureLabel.FIRST
                )
            ),
            state = MarketStructureState.RANGE,
            zones = emptyList(),
            atr = null
        )

        /*
         * LevelExtractor's pivot-side mapping comes from PivotType, so make
         * the supplied level match the pivot's natural side.
         */
        val actualLevel = level.copy(
            origins = listOf(
                LevelOrigin.Pivot(pivot)
            )
        )

        val config = SetupConfig(
            pattern = PatternConfig(),
            levelSources = setOf(LevelSource.PIVOT),
            buffer = buffer,
            confirmationCloses = confirmationCloses,
            retestWindow = 3,
            failureWindow = 4,
            continuationWindow = 5
        )

        return BreakoutDetector.detect(
            candles = candles,
            structure = structure,
            patterns = patterns(candles.size),
            config = config
        )
    }

    @Test
    fun detectsStrictUpwardCloseCrossing() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            candles(99.0, 100.0, 101.0),
            level
        )

        assertEquals(1, result.size)
        assertEquals(BreakoutDirection.UP, result.single().direction)
        assertEquals(2, result.single().confirmedIndex)
        assertEquals(101.0, result.single().breakClose)
        assertEquals(100.0, result.single().trigger)
    }

    @Test
    fun equalityDoesNotTrigger() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            candles(100.0, 100.0, 100.0),
            level
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun wickOnlyBreachDoesNotTrigger() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            listOf(
                candle(0, 99.0),
                candle(1, 99.0),
                candle(2, 99.0, high = 101.0),
            ),
            level
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun detectsStrictDownwardCloseCrossing() {
        val level = level(ZoneKind.SUPPORT, 100.0)

        val result = detect(
            candles(101.0, 100.0, 99.0),
            level
        )

        assertEquals(1, result.size)
        assertEquals(BreakoutDirection.DOWN, result.single().direction)
        assertEquals(2, result.single().confirmedIndex)
        assertEquals(99.0, result.single().breakClose)
        assertEquals(100.0, result.single().trigger)
    }

    @Test
    fun confirmationRequiresConsecutiveClosesBeyondTrigger() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            candles(99.0, 101.0, 101.5, 101.8),
            level,
            confirmationCloses = 2
        )

        assertEquals(1, result.size)
        assertEquals(2, result.single().confirmedIndex)
        assertEquals(1, result.single().breakIndex)
    }

    @Test
    fun failedConfirmationIsDiscardedWhenCloseReturnsInside() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            candles(99.0, 101.0, 100.0, 101.5, 101.6),
            level,
            confirmationCloses = 2
        )

        assertEquals(1, result.size)
        assertEquals(4, result.single().confirmedIndex)
        assertEquals(3, result.single().breakIndex)
    }

    @Test
    fun nonZeroAbsoluteBufferMovesTrigger() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            candles(100.0, 100.5, 101.1),
            level,
            buffer = BufferRule(absolute = 1.0)
        )

        assertEquals(1, result.size)
        assertEquals(2, result.single().breakIndex)
        assertEquals(2, result.single().confirmedIndex)
    }

    @Test
    fun breakoutIdAndAttemptAreDeterministic() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            candles(99.0, 101.0, 99.0, 101.5),
            level
        )

        assertEquals(2, result.size)
        assertEquals("UP:100.0:b1", result[0].id.value)
        assertEquals("UP:100.0:b2", result[1].id.value)
        assertEquals(1, result[0].attempt)
        assertEquals(2, result[1].attempt)
    }

    @Test
    fun deadlinesAreDerivedFromConfirmedIndex() {
        val level = level(ZoneKind.RESISTANCE, 100.0)

        val result = detect(
            candles(99.0, 101.0, 102.0),
            level
        )

        val breakout = result.single()
        assertEquals(4, breakout.deadlines.retestUntil)
        assertEquals(5, breakout.deadlines.failureUntil)
        assertEquals(6, breakout.deadlines.continuationUntil)
        assertEquals(6, breakout.deadlines.expiresAt)
    }

    @Test
    fun repeatedRunsAreIdentical() {
        val level = level(ZoneKind.RESISTANCE, 100.0)
        val data = candles(99.0, 101.0, 99.0, 101.5, 99.0, 102.0)

        val a = detect(data, level)
        val b = detect(data, level)

        assertEquals(a, b)
    }

    @Test
    fun prefixResultsMatchFullSeriesForConfirmedBreakouts() {
        val level = level(ZoneKind.RESISTANCE, 100.0)
        val data = candles(
            99.0, 101.0, 99.0, 101.5, 102.0, 99.0, 101.5, 102.0
        )

        val full = detect(data, level)

        for (prefixSize in 2..data.size) {
            val prefix = data.take(prefixSize)
            val prefixResult = detect(prefix, level)
            val expected = full.filter {
                it.confirmedIndex < prefixSize
            }

            assertEquals(
                expected,
                prefixResult,
                "prefixSize=$prefixSize"
            )
        }
    }
}
