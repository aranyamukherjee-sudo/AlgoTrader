package com.algotrader.data

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import java.time.Instant
import kotlin.random.Random

class SyntheticMarketDataGenerator(
    private val seed: Long = 42L
) {

    fun generate(
        instrument: Instrument,
        timeframe: Timeframe,
        start: Instant,
        count: Int,
        startingPrice: Double = 100.0,
        trend: Double = 0.0,
        volatility: Double = 1.0
    ): List<Candle> {
        require(count > 0) { "count must be greater than zero" }
        require(startingPrice > 0.0) { "startingPrice must be positive" }
        require(volatility >= 0.0) { "volatility cannot be negative" }

        val random = Random(seed)
        val result = ArrayList<Candle>(count)

        var previousClose = startingPrice
        var timestamp = start

        repeat(count) {
            val open = previousClose
            val change = trend + random.nextDouble(
                from = -volatility,
                until = volatility
            )

            val close = (open + change).coerceAtLeast(0.01)
            val high = maxOf(open, close) +
                random.nextDouble(0.0, volatility * 0.5)
            val low = (minOf(open, close) -
                random.nextDouble(0.0, volatility * 0.5))
                .coerceAtLeast(0.01)

            val volume = random.nextDouble(100.0, 1000.0)

            result += Candle(
                instrument = instrument,
                timeframe = timeframe,
                timestamp = timestamp,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )

            previousClose = close
            timestamp = nextTimestamp(timestamp, timeframe)
        }

        return result
    }

    private fun nextTimestamp(
        timestamp: Instant,
        timeframe: Timeframe
    ): Instant {
        val seconds = when (timeframe) {
            Timeframe.MINUTE_1 -> 60L
            Timeframe.MINUTE_5 -> 300L
            Timeframe.MINUTE_15 -> 900L
            Timeframe.MINUTE_30 -> 1800L
            Timeframe.HOUR_1 -> 3600L
            Timeframe.HOUR_4 -> 14400L
            Timeframe.DAY_1 -> 86400L
        }

        return timestamp.plusSeconds(seconds)
    }
}
