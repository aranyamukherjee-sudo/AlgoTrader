package com.algotrader.intelligence.structure

import com.algotrader.domain.Candle

/**
 * Input checks for structure analysis. Returns a human-readable problem, or null when the series is usable.
 * Bad market data is an expected outcome, so it is reported, not thrown.
 *
 * Rules: one instrument and one timeframe; timestamps STRICTLY increasing (a duplicate or a step back is
 * rejected, never sorted or de-duplicated silently); finite OHLC; high >= low.
 */
object CandleSeries {
    fun problem(candles: List<Candle>): String? {
        if (candles.isEmpty()) return null
        val first = candles[0]
        for (i in candles.indices) {
            val c = candles[i]
            if (c.instrument != first.instrument) return "mixed instruments at index $i"
            if (c.timeframe != first.timeframe) return "mixed timeframes at index $i"
            if (i > 0) {
                val prev = candles[i - 1].timestamp
                if (c.timestamp == prev) return "duplicate timestamp at index $i (${c.timestamp})"
                if (c.timestamp.isBefore(prev)) return "timestamps decrease at index $i (${c.timestamp} < $prev)"
            }
            if (!(c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite())) {
                return "non-finite price at index $i"
            }
            if (c.high < c.low) return "high < low at index $i"
        }
        return null
    }
}
