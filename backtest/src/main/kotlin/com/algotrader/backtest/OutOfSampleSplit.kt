package com.algotrader.backtest

import com.algotrader.domain.Candle

/**
 * Chronological in-sample / out-of-sample split of a candle series.
 *
 * Rules:
 * - in-sample = candles[0 until k], out-of-sample = candles[k until N],
 *   where k = floor(0.70 * N), computed with integer arithmetic (N * 70 / 100)
 *   so it is exact;
 * - candles are never shuffled and never sorted: the input timestamps must
 *   already be strictly increasing, otherwise the split is rejected;
 * - each segment needs at least [MIN_SEGMENT_CANDLES] candles, and the whole
 *   series at least [MIN_TOTAL_CANDLES];
 * - the two segments are disjoint and contiguous: no candle is in both.
 *
 * This is not parameter optimization. Strategy parameters are unchanged; the
 * two segments are simply backtested separately.
 */
object OutOfSampleSplit {

    const val IN_SAMPLE_PERCENT = 70
    const val MIN_SEGMENT_CANDLES = 50

    /** ceil(50 / 0.30) = 167, so floor(0.70 * 167) = 116 in-sample and 51 out-of-sample. */
    const val MIN_TOTAL_CANDLES = 167

    sealed interface Outcome {
        data class Split(
            val inSample: List<Candle>,
            val outOfSample: List<Candle>
        ) : Outcome

        data class Rejected(val reason: String) : Outcome
    }

    fun inSampleSize(total: Int): Int =
        ((total.toLong() * IN_SAMPLE_PERCENT) / 100L).toInt()

    fun split(candles: List<Candle>): Outcome {
        val total = candles.size

        if (total < MIN_TOTAL_CANDLES) {
            return Outcome.Rejected(
                "Out-of-sample testing needs at least $MIN_TOTAL_CANDLES candles " +
                    "(found $total)."
            )
        }

        for (i in 1 until total) {
            if (!candles[i].timestamp.isAfter(candles[i - 1].timestamp)) {
                return Outcome.Rejected(
                    "Candles are not in strictly increasing time order " +
                        "(position $i). Out-of-sample testing was not run."
                )
            }
        }

        val k = inSampleSize(total)
        val inSampleCount = k
        val outOfSampleCount = total - k

        if (inSampleCount < MIN_SEGMENT_CANDLES || outOfSampleCount < MIN_SEGMENT_CANDLES) {
            return Outcome.Rejected(
                "Each segment needs at least $MIN_SEGMENT_CANDLES candles " +
                    "(in-sample $inSampleCount, out-of-sample $outOfSampleCount)."
            )
        }

        // Independent copies so neither segment can alias the other or the input.
        return Outcome.Split(
            inSample = ArrayList(candles.subList(0, k)),
            outOfSample = ArrayList(candles.subList(k, total))
        )
    }
}
