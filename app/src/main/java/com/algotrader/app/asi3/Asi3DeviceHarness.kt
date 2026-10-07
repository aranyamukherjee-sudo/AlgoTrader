package com.algotrader.app.asi3

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.pattern.PatternConfig
import com.algotrader.intelligence.pattern.PatternDetector
import java.time.Instant

object Asi3DeviceHarness {

    data class Result(
        val candleCount: Int,
        val status: String,
        val message: String,
        val patternCount: Int,
        val typeCounts: Map<String, Int>,
        val patterns: List<String>,
        val deterministic: Boolean
    )

    private val instrument = Instrument("ASI3-FIXTURE", "NSE")

    private val fixtures = listOf(
        "DOUBLE_TOP" to doubleArrayOf(100.0, 120.0, 110.0, 120.0, 100.0),
        "DOUBLE_BOTTOM" to doubleArrayOf(120.0, 100.0, 110.0, 100.0, 120.0),
        "M_TOP" to doubleArrayOf(100.0, 120.0, 110.0, 115.0, 100.0),
        "W_BOTTOM" to doubleArrayOf(120.0, 100.0, 110.0, 105.0, 120.0),
        "RECTANGLE" to doubleArrayOf(100.0, 120.0, 100.0, 120.0, 100.0, 120.0, 100.0),
        "ASCENDING_TRIANGLE" to doubleArrayOf(100.0, 120.0, 105.0, 120.0, 110.0, 120.0, 112.0),
        "DESCENDING_TRIANGLE" to doubleArrayOf(120.0, 100.0, 115.0, 100.0, 110.0, 100.0, 108.0),
        "SYMMETRICAL_TRIANGLE" to doubleArrayOf(100.0, 130.0, 105.0, 125.0, 110.0, 120.0, 112.0),
        "BULL_FLAG" to doubleArrayOf(105.0, 100.0, 105.0, 110.0, 120.0, 130.0, 120.0, 127.0, 118.0),
        "BEAR_FLAG" to doubleArrayOf(95.0, 100.0, 95.0, 90.0, 80.0, 70.0, 80.0, 73.0, 82.0)
    )

    private fun candlesOf(mids: DoubleArray): List<Candle> =
        mids.mapIndexed { i, mid ->
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_15,
                timestamp = Instant.parse("2026-01-05T04:00:00Z")
                    .plusSeconds(15L * 60L * i),
                open = mid,
                high = mid + 0.5,
                low = mid - 0.5,
                close = mid,
                volume = 1000.0
            )
        }

    private fun allCandles(): List<Candle> =
        fixtures.flatMapIndexed { family, (_, mids) ->
            candlesOf(mids).map { candle ->
                candle.copy(
                    timestamp = candle.timestamp.plusSeconds(family.toLong() * 14400L)
                )
            }
        }.sortedBy { it.timestamp }

    fun run(): Result {
        val candles = allCandles()
        val config = PatternConfig()

        val first = PatternDetector.detect(candles, config)
        val second = PatternDetector.detect(candles, config)

        val typeCounts = first.patterns
            .groupingBy { it.type.name }
            .eachCount()
            .toSortedMap()

        val patternLines = first.patterns.map { p ->
            "${p.type.name}: start=${p.startIndex}, end=${p.endIndex}, " +
                "confirmed=${p.confirmedIndex}, confirmedAt=${p.confirmedAt}"
        }

        return Result(
            candleCount = candles.size,
            status = first.status.name,
            message = first.message,
            patternCount = first.patterns.size,
            typeCounts = typeCounts,
            patterns = patternLines,
            deterministic = first == second
        )
    }
}
