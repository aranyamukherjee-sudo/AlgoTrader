package com.algotrader.backtest

import com.algotrader.domain.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutOfSampleSplitTest {

    private fun series(n: Int): List<Candle> = (0 until n).map { testCandle(it, close = 100.0 + it) }

    private fun split(n: Int): OutOfSampleSplit.Outcome.Split {
        val outcome = OutOfSampleSplit.split(series(n))
        return assertIs<OutOfSampleSplit.Outcome.Split>(outcome)
    }

    @Test
    fun `splits 70 30 chronologically using floor`() {
        val s = split(200)
        assertEquals(140, s.inSample.size)
        assertEquals(60, s.outOfSample.size)
        assertEquals(0, s.inSample.first().timestamp.epochSecond / 86_400L)
        assertEquals(139, s.inSample.last().timestamp.epochSecond / 86_400L)
        assertEquals(140, s.outOfSample.first().timestamp.epochSecond / 86_400L)
        assertEquals(199, s.outOfSample.last().timestamp.epochSecond / 86_400L)
    }

    @Test
    fun `k is floor of 0_70 times N for non-round sizes`() {
        for (n in 167..1000) {
            val expected = Math.floor(0.70 * n).toInt()
            // 0.70 * n in binary floating point can land just below an integer;
            // the implementation must match exact decimal floor(0.70 * N).
            val exact = (n * 70) / 100
            assertEquals(exact, OutOfSampleSplit.inSampleSize(n))
            assertTrue(kotlin.math.abs(expected - exact) <= 1)
        }
        assertEquals(116, OutOfSampleSplit.inSampleSize(167))
    }

    @Test
    fun `segments are disjoint contiguous and cover every candle exactly once`() {
        for (n in listOf(167, 168, 200, 333, 1000, 1234)) {
            val input = series(n)
            val s = split(n)
            assertEquals(n, s.inSample.size + s.outOfSample.size)
            assertEquals(input, s.inSample + s.outOfSample)
            val inTimes = s.inSample.map { it.timestamp }.toSet()
            assertTrue(s.outOfSample.none { it.timestamp in inTimes })
            assertTrue(s.inSample.last().timestamp < s.outOfSample.first().timestamp)
        }
    }

    @Test
    fun `order is preserved and never shuffled`() {
        val s = split(250)
        assertEquals(s.inSample.sortedBy { it.timestamp }, s.inSample)
        assertEquals(s.outOfSample.sortedBy { it.timestamp }, s.outOfSample)
    }

    @Test
    fun `in-sample segment is independent of later candles`() {
        val a = series(200)
        val b = a.take(180) + (180 until 200).map { testCandle(it, close = 9_999.0) }
        val sa = assertIs<OutOfSampleSplit.Outcome.Split>(OutOfSampleSplit.split(a))
        val sb = assertIs<OutOfSampleSplit.Outcome.Split>(OutOfSampleSplit.split(b))
        // Same N, same k: changing only OOS-region candles must not touch IS.
        assertEquals(sa.inSample, sb.inSample)
    }

    @Test
    fun `minimum is 167 total candles`() {
        assertIs<OutOfSampleSplit.Outcome.Rejected>(OutOfSampleSplit.split(series(166)))
        assertIs<OutOfSampleSplit.Outcome.Rejected>(OutOfSampleSplit.split(series(0)))
        val s = split(167)
        assertEquals(116, s.inSample.size)
        assertEquals(51, s.outOfSample.size)
    }

    @Test
    fun `every accepted size has at least 50 candles in each segment`() {
        for (n in 167..2000) {
            val s = split(n)
            assertTrue(s.inSample.size >= 50, "IS too small at $n")
            assertTrue(s.outOfSample.size >= 50, "OOS too small at $n")
        }
    }

    @Test
    fun `rejects non-increasing timestamps without sorting`() {
        val duplicate = series(200).toMutableList().also { it[100] = it[99] }
        val rejectedDuplicate = OutOfSampleSplit.split(duplicate)
        assertIs<OutOfSampleSplit.Outcome.Rejected>(rejectedDuplicate)

        val reversed = series(200).toMutableList().also {
            val t = it[50]; it[50] = it[51]; it[51] = t
        }
        assertIs<OutOfSampleSplit.Outcome.Rejected>(OutOfSampleSplit.split(reversed))

        val fullyReversed = series(200).reversed()
        assertIs<OutOfSampleSplit.Outcome.Rejected>(OutOfSampleSplit.split(fullyReversed))
    }

    @Test
    fun `segments are copies and do not alias the input`() {
        val input = series(200).toMutableList()
        val s = split(200)
        input[0] = testCandle(0, close = -1.0)
        assertFalse(s.inSample[0].close == -1.0)
    }

    @Test
    fun `result sample defaults to FULL`() {
        val r = StrategyBacktestRunner().run("moving_average_crossover", series(100))
        assertEquals(BacktestSample.FULL, r.sample)
        assertEquals(BacktestSample.IN_SAMPLE, r.copy(sample = BacktestSample.IN_SAMPLE).sample)
    }
}
