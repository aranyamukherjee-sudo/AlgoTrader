package com.algotrader.intelligence.setup

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.PriceZone
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import com.algotrader.intelligence.structure.MarketStructureState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LevelExtractorTest {

    private val instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun candle(index: Int): Candle =
        Candle(
            instrument = Instrument("NIFTY 50", "NSE"),
            timeframe = Timeframe.MINUTE_5,
            timestamp = instant.plusSeconds(index.toLong() * 300),
            open = 100.0,
            high = 110.0,
            low = 90.0,
            close = 100.0,
            volume = 1000.0
        )

    private fun candles(count: Int): List<Candle> =
        (0 until count).map(::candle)

    private fun pivot(
        type: PivotType,
        index: Int,
        price: Double,
        confirmedIndex: Int = index
    ) = SwingPivot(
        type = type,
        index = index,
        timestamp = instant.plusSeconds(index.toLong() * 300),
        price = price,
        confirmedIndex = confirmedIndex,
        confirmedAt = instant.plusSeconds(confirmedIndex.toLong() * 300),
        strength = 2,
        prominence = 1.0
    )

    private fun structure(
        pivots: List<SwingPivot>,
        zones: List<PriceZone> = emptyList(),
        candleCount: Int = 20
    ): StructureAnalysis {
        val classified = pivots.map {
            com.algotrader.intelligence.structure.ClassifiedPivot(
                pivot = it,
                label = com.algotrader.intelligence.structure.StructureLabel.FIRST
            )
        }

        return StructureAnalysis(
            status = AnalysisStatus.OK,
            message = "",
            candleCount = candleCount,
            pivots = classified,
            state = MarketStructureState.RANGE,
            zones = zones,
            atr = 1.0
        )
    }

    private fun pattern(
        type: PatternType,
        high: Double,
        low: Double,
        neckline: Double? = null,
        confirmedIndex: Int = 5
    ) = DetectedPattern(
        type = type,
        pivots = listOf(
            pivot(PivotType.HIGH, 1, high, confirmedIndex),
            pivot(PivotType.LOW, 2, low, confirmedIndex),
            pivot(PivotType.HIGH, 3, high, confirmedIndex)
        ),
        startIndex = 1,
        endIndex = 3,
        confirmedIndex = confirmedIndex,
        confirmedAt = instant.plusSeconds(confirmedIndex.toLong() * 300),
        high = high,
        low = low,
        neckline = neckline
    )

    private fun patterns(
        values: List<DetectedPattern>,
        candleCount: Int = 20
    ) = PatternAnalysis(
        status = AnalysisStatus.OK,
        message = "",
        candleCount = candleCount,
        patterns = values
    )

    @Test
    fun `level key keeps support and resistance distinct`() {
        val support = BreakoutLevel(
            side = ZoneKind.SUPPORT,
            price = 100.0,
            origins = listOf(
                LevelOrigin.Zone(
                    PriceZone(
                        kind = ZoneKind.SUPPORT,
                        low = 100.0,
                        high = 101.0,
                        touches = 1,
                        firstIndex = 2,
                        lastIndex = 2,
                        lastConfirmedIndex = 2
                    )
                )
            )
        )

        val resistance = BreakoutLevel(
            side = ZoneKind.RESISTANCE,
            price = 100.0,
            origins = listOf(
                LevelOrigin.Zone(
                    PriceZone(
                        kind = ZoneKind.RESISTANCE,
                        low = 99.0,
                        high = 100.0,
                        touches = 1,
                        firstIndex = 3,
                        lastIndex = 3,
                        lastConfirmedIndex = 3
                    )
                )
            )
        )

        assertEquals(ZoneKind.SUPPORT, support.side)
        assertEquals(ZoneKind.RESISTANCE, resistance.side)
        assertEquals(100.0, support.price)
        assertEquals(100.0, resistance.price)
    }

    @Test
    fun `pivot levels use pivot price and correct side`() {
        val structure = structure(
            listOf(
                pivot(PivotType.HIGH, index = 2, price = 120.0),
                pivot(PivotType.LOW, index = 4, price = 100.0)
            )
        )

        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure,
            patterns = patterns(emptyList()),
            config = SetupConfig(levelSources = setOf(LevelSource.PIVOT)),
            asOfIndex = 10
        )

        assertEquals(
            listOf(
                ZoneKind.SUPPORT to 100.0,
                ZoneKind.RESISTANCE to 120.0
            ),
            result.map { it.side to it.price }
        )

        assertTrue(result.all { it.origins.size == 1 })
        assertTrue(result[0].origins.single() is LevelOrigin.Pivot)
    }

    @Test
    fun `zone levels use support low and resistance high`() {
        val support = PriceZone(
            kind = ZoneKind.SUPPORT,
            low = 100.0,
            high = 100.1,
            touches = 2,
            firstIndex = 2,
            lastIndex = 5,
            lastConfirmedIndex = 5
        )
        val resistance = PriceZone(
            kind = ZoneKind.RESISTANCE,
            low = 120.0,
            high = 120.1,
            touches = 2,
            firstIndex = 3,
            lastIndex = 6,
            lastConfirmedIndex = 6
        )

        val structure = structure(
            pivots = listOf(
                pivot(PivotType.LOW, 2, 100.0),
                pivot(PivotType.LOW, 5, 100.1),
                pivot(PivotType.HIGH, 3, 120.0),
                pivot(PivotType.HIGH, 6, 120.1)
            ),
            zones = listOf(support, resistance)
        )

        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure,
            patterns = patterns(emptyList()),
            config = SetupConfig(levelSources = setOf(LevelSource.ZONE)),
            asOfIndex = 10
        )

        assertEquals(
            listOf(
                ZoneKind.SUPPORT to 100.0,
                ZoneKind.RESISTANCE to 120.1
            ),
            result.map { it.side to it.price }
        )
    }

    @Test
    fun `pivot and zone origins merge at identical level`() {
        val zone = PriceZone(
            kind = ZoneKind.RESISTANCE,
            low = 120.0,
            high = 120.0,
            touches = 2,
            firstIndex = 2,
            lastIndex = 5,
            lastConfirmedIndex = 5
        )

        val structure = structure(
            pivots = listOf(
                pivot(PivotType.HIGH, 2, 120.0),
                pivot(PivotType.HIGH, 5, 120.0)
            ),
            zones = listOf(zone)
        )

        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure,
            patterns = patterns(emptyList()),
            config = SetupConfig(
                levelSources = setOf(LevelSource.PIVOT, LevelSource.ZONE)
            ),
            asOfIndex = 10
        )

        assertEquals(1, result.size)
        assertEquals(ZoneKind.RESISTANCE, result.single().side)
        assertEquals(120.0, result.single().price)
        assertEquals(3, result.single().origins.size)
        assertEquals(
            listOf(LevelSource.PIVOT, LevelSource.PIVOT, LevelSource.ZONE),
            result.single().origins.map { it.source }
        )
    }

    @Test
    fun `pattern boundaries extract supported pattern geometry`() {
        val top = pattern(
            type = PatternType.DOUBLE_TOP,
            high = 125.0,
            low = 110.0,
            neckline = 110.0
        )
        val bottom = pattern(
            type = PatternType.DOUBLE_BOTTOM,
            high = 125.0,
            low = 100.0,
            neckline = 125.0
        )
        val rectangle = pattern(
            type = PatternType.RECTANGLE,
            high = 130.0,
            low = 105.0
        )
        val ascending = pattern(
            type = PatternType.ASCENDING_TRIANGLE,
            high = 135.0,
            low = 110.0
        )
        val descending = pattern(
            type = PatternType.DESCENDING_TRIANGLE,
            high = 130.0,
            low = 100.0
        )

        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure(emptyList()),
            patterns = patterns(
                listOf(top, bottom, rectangle, ascending, descending)
            ),
            config = SetupConfig(
                levelSources = setOf(LevelSource.PATTERN_BOUNDARY)
            ),
            asOfIndex = 10
        )

        assertEquals(
            listOf(
                ZoneKind.SUPPORT to 100.0,
                ZoneKind.SUPPORT to 105.0,
                ZoneKind.SUPPORT to 110.0,
                ZoneKind.RESISTANCE to 125.0,
                ZoneKind.RESISTANCE to 130.0,
                ZoneKind.RESISTANCE to 135.0
            ),
            result.map { it.side to it.price }
        )

        assertEquals(2, result.count { it.origins.any { origin ->
            origin is LevelOrigin.PatternBoundary &&
                origin.role == PatternBoundaryRole.NECKLINE
        } })
    }

    @Test
    fun `unsupported sloped pattern boundaries produce no levels`() {
        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure(emptyList()),
            patterns = patterns(
                listOf(
                    pattern(PatternType.SYMMETRICAL_TRIANGLE, 130.0, 100.0),
                    pattern(PatternType.BULL_FLAG, 140.0, 110.0),
                    pattern(PatternType.BEAR_FLAG, 130.0, 90.0)
                )
            ),
            config = SetupConfig(
                levelSources = setOf(LevelSource.PATTERN_BOUNDARY)
            ),
            asOfIndex = 10
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `as of index excludes origins not yet known`() {
        val structure = structure(
            listOf(
                pivot(PivotType.HIGH, 2, 120.0, confirmedIndex = 5),
                pivot(PivotType.LOW, 4, 100.0, confirmedIndex = 9)
            )
        )

        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure,
            patterns = patterns(emptyList()),
            config = SetupConfig(levelSources = setOf(LevelSource.PIVOT)),
            asOfIndex = 7
        )

        assertEquals(1, result.size)
        assertEquals(120.0, result.single().price)
    }

    @Test
    fun `pattern is unavailable before its confirmation candle`() {
        val confirmedAtFive = pattern(
            type = PatternType.RECTANGLE,
            high = 130.0,
            low = 100.0,
            confirmedIndex = 5
        )

        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure(emptyList()),
            patterns = patterns(listOf(confirmedAtFive)),
            config = SetupConfig(
                levelSources = setOf(LevelSource.PATTERN_BOUNDARY)
            ),
            asOfIndex = 4
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `invalid analysis status returns empty levels`() {
        val structure = StructureAnalysis(
            status = AnalysisStatus.INSUFFICIENT_DATA,
            message = "not enough",
            candleCount = 20,
            pivots = emptyList(),
            state = MarketStructureState.UNDEFINED,
            zones = emptyList(),
            atr = null
        )

        val result = LevelExtractor.extract(
            candles = candles(20),
            structure = structure,
            patterns = patterns(emptyList()),
            asOfIndex = 10
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `levels have deterministic ordering`() {
        val structure = structure(
            listOf(
                pivot(PivotType.HIGH, 3, 130.0),
                pivot(PivotType.LOW, 4, 90.0),
                pivot(PivotType.HIGH, 5, 120.0),
                pivot(PivotType.LOW, 6, 100.0)
            )
        )

        val config = SetupConfig(levelSources = setOf(LevelSource.PIVOT))

        val first = LevelExtractor.extract(
            candles = candles(20),
            structure = structure,
            patterns = patterns(emptyList()),
            config = config,
            asOfIndex = 10
        )

        val second = LevelExtractor.extract(
            candles = candles(20),
            structure = structure,
            patterns = patterns(emptyList()),
            config = config,
            asOfIndex = 10
        )

        assertEquals(first, second)
        assertEquals(
            listOf(
                ZoneKind.SUPPORT to 90.0,
                ZoneKind.SUPPORT to 100.0,
                ZoneKind.RESISTANCE to 120.0,
                ZoneKind.RESISTANCE to 130.0
            ),
            first.map { it.side to it.price }
        )
    }

    @Test
    fun `level extraction requires valid as of index`() {
        val structure = structure(emptyList())
        val patterns = patterns(emptyList())
        val candles = candles(5)

        assertFailsWith<IllegalArgumentException> {
            LevelExtractor.extract(
                candles,
                structure,
                patterns,
                asOfIndex = -1
            )
        }

        assertFailsWith<IllegalArgumentException> {
            LevelExtractor.extract(
                candles,
                structure,
                patterns,
                asOfIndex = candles.size
            )
        }
    }
}
