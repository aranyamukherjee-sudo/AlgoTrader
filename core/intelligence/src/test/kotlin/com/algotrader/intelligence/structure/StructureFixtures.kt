package com.algotrader.intelligence.structure

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import java.time.Instant

/**
 * Hand-built, mechanical fixtures. They exist to pin down the SEMANTICS of the structure code; they are not market
 * data and say nothing about real-market behaviour or performance.
 */
internal object StructureFixtures {
    val T0: Instant = Instant.parse("2026-10-06T04:00:00Z")
    val instrument = Instrument("NIFTY 50", "NSE")
    fun at(index: Int): Instant = T0.plusSeconds(index * 300L)

    fun candle(
        index: Int,
        high: Double,
        low: Double,
        close: Double = (high + low) / 2.0,
        instrument: Instrument = this.instrument,
        timeframe: Timeframe = Timeframe.MINUTE_5,
        timestamp: Instant = at(index)
    ) = Candle(instrument, timeframe, timestamp, open = close, high = high, low = low, close = close, volume = 1000.0)

    /** One candle per mid price: high = mid + [halfRange], low = mid - [halfRange], close = mid. */
    fun fromMids(mids: List<Double>, halfRange: Double = 0.5): List<Candle> =
        mids.mapIndexed { i, m -> candle(i, m + halfRange, m - halfRange, m) }

    fun fromMids(vararg mids: Double, halfRange: Double = 0.5): List<Candle> = fromMids(mids.toList(), halfRange)

    /** Deterministic pseudo-random walk (fixed LCG) used only for property-style checks. */
    fun walk(size: Int, seed: Long = 12345L): List<Candle> {
        var x = seed
        var mid = 1000.0
        val mids = ArrayList<Double>()
        repeat(size) {
            x = (x * 1103515245L + 12345L) and 0x7fffffffL
            mid += ((x shr 8) % 21).toDouble() - 10.0
            mids += mid
        }
        return fromMids(mids, halfRange = 3.0)
    }

    fun pivot(type: PivotType, index: Int, price: Double, right: Int = 1) = SwingPivot(
        type = type, index = index, timestamp = at(index), price = price,
        confirmedIndex = index + right, confirmedAt = at(index + right), strength = 1, prominence = 1.0
    )

    /** left = right = 1: compact configuration for hand-built zig-zags. */
    val tight = StructureConfig(left = 1, right = 1, maxStrength = 5)
}
