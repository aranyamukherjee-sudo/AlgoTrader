package com.algotrader.discovery.split

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WalkForwardSplitTest {

    private fun candles(count: Int): List<Candle> =
        (0 until count).map { i ->
            Candle(
                timestamp = Instant.ofEpochSecond(i.toLong() * 60L),
                open = 100.0 + i,
                high = 101.0 + i,
                low = 99.0 + i,
                close = 100.5 + i,
                volume = 1_000.0 + i,
                instrument = Instrument(symbol = "TEST", exchange = "NSE"),
                timeframe = Timeframe.MINUTE_1
            )
        }

    @Test
    fun `rolling folds are chronological and embargoed`() {
        val folds = WalkForwardSplit.rolling(
            candles = candles(30),
            trainBars = 10,
            validationBars = 5,
            stepBars = 5,
            embargoBars = 2
        )

        assertEquals(3, folds.size)

        folds.forEachIndexed { index, fold ->
            assertEquals(index, fold.index)
            assertEquals(10, fold.train.size)
            assertEquals(5, fold.validation.size)

            assertTrue(fold.train.last().timestamp.isBefore(fold.validation.first().timestamp))

            val trainLastIndex = fold.train.last().timestamp.epochSecond / 60L
            val validationFirstIndex = fold.validation.first().timestamp.epochSecond / 60L
            assertEquals(2L, validationFirstIndex - trainLastIndex - 1L)

            assertTrue(
                fold.train.map { it.timestamp }.intersect(
                    fold.validation.map { it.timestamp }
                ).isEmpty()
            )
        }
    }

    @Test
    fun `rolling folds advance deterministically`() {
        val folds = WalkForwardSplit.rolling(
            candles = candles(40),
            trainBars = 10,
            validationBars = 5,
            stepBars = 5,
            embargoBars = 2
        )

        assertEquals(5, folds.size)

        assertEquals(0L, folds[0].train.first().timestamp.epochSecond / 60L)
        assertEquals(5L, folds[1].train.first().timestamp.epochSecond / 60L)
        assertEquals(10L, folds[2].train.first().timestamp.epochSecond / 60L)

        assertEquals(12L, folds[0].validation.first().timestamp.epochSecond / 60L)
        assertEquals(17L, folds[1].validation.first().timestamp.epochSecond / 60L)
        assertEquals(22L, folds[2].validation.first().timestamp.epochSecond / 60L)
    }

    @Test
    fun `input ordering does not affect deterministic result`() {
        val source = candles(35)
        val reversed = source.asReversed()

        val a = WalkForwardSplit.rolling(
            source, trainBars = 10, validationBars = 5, stepBars = 5, embargoBars = 2
        )
        val b = WalkForwardSplit.rolling(
            reversed, trainBars = 10, validationBars = 5, stepBars = 5, embargoBars = 2
        )

        assertEquals(
            a.map { it.fingerprint() },
            b.map { it.fingerprint() }
        )
    }

    @Test
    fun `duplicate timestamps are rejected`() {
        val source = candles(20).toMutableList()
        source[5] = source[4]

        assertFailsWith<IllegalArgumentException> {
            WalkForwardSplit.rolling(
                source,
                trainBars = 5,
                validationBars = 3,
                embargoBars = 1
            )
        }
    }

    @Test
    fun `insufficient data is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WalkForwardSplit.rolling(
                candles = candles(15),
                trainBars = 10,
                validationBars = 5,
                embargoBars = 2,
                minFolds = 1
            )
        }
    }

    @Test
    fun `minimum fold count is enforced`() {
        assertFailsWith<IllegalArgumentException> {
            WalkForwardSplit.rolling(
                candles = candles(25),
                trainBars = 10,
                validationBars = 5,
                stepBars = 5,
                embargoBars = 2,
                minFolds = 4
            )
        }
    }

    @Test
    fun `fold fingerprint is stable`() {
        val first = WalkForwardSplit.rolling(
            candles = candles(30),
            trainBars = 10,
            validationBars = 5,
            stepBars = 5,
            embargoBars = 2
        )

        val second = WalkForwardSplit.rolling(
            candles = candles(30),
            trainBars = 10,
            validationBars = 5,
            stepBars = 5,
            embargoBars = 2
        )

        assertEquals(
            first.map { it.fingerprint() },
            second.map { it.fingerprint() }
        )
    }
}
