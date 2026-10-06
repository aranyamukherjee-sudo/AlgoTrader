package com.algotrader.discovery.split

import com.algotrader.discovery.fixtures.SyntheticCandles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataSplitTest {

    private val candles = SyntheticCandles.allStrong // 1200 synthetic bars
    private val split = DataSplit.chronological(candles)

    @Test
    fun `default split is 60-20-rest with an embargo gap`() {
        assertEquals(720, split.train.size)
        assertEquals(240, split.validation.size)
        assertEquals(230, split.holdout.size)
        // 5 bars are discarded at each boundary, so the first bar after a segment is 6 bars later
        val bar = 15L * 60L
        assertEquals(6 * bar, split.validation.first().timestamp.epochSecond - split.train.last().timestamp.epochSecond)
        assertEquals(6 * bar, split.holdout.first().timestamp.epochSecond - split.validation.last().timestamp.epochSecond)
    }

    @Test
    fun `segments are strictly ordered in time and share no candle`() {
        assertTrue(split.train.last().timestamp.isBefore(split.validation.first().timestamp))
        assertTrue(split.validation.last().timestamp.isBefore(split.holdout.first().timestamp))
        val train = split.train.map { it.timestamp }.toSet()
        val validation = split.validation.map { it.timestamp }.toSet()
        val holdout = split.holdout.map { it.timestamp }.toSet()
        assertTrue(train.intersect(validation).isEmpty())
        assertTrue(train.intersect(holdout).isEmpty())
        assertTrue(validation.intersect(holdout).isEmpty())
    }

    @Test
    fun `input order does not matter`() {
        assertEquals(split, DataSplit.chronological(candles.reversed()))
    }

    @Test
    fun `segment accessor returns the matching segment`() {
        assertEquals(split.train, split.segment(SegmentRole.TRAIN))
        assertEquals(split.validation, split.segment(SegmentRole.VALIDATION))
        assertEquals(split.holdout, split.segment(SegmentRole.HOLDOUT))
    }

    @Test
    fun `a split that leaks later data into an earlier segment cannot be built`() {
        assertFailsWith<IllegalArgumentException> {
            DataSplit(train = split.train, validation = split.train.takeLast(50), holdout = split.holdout, embargoBars = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DataSplit(train = split.validation, validation = split.train, holdout = split.holdout, embargoBars = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DataSplit(split.train, split.validation, emptyList(), embargoBars = 0)
        }
    }

    @Test
    fun `bad input is rejected`() {
        assertFailsWith<IllegalArgumentException> { DataSplit.chronological(candles + candles.first()) } // duplicate timestamp
        assertFailsWith<IllegalArgumentException> { DataSplit.chronological(candles.take(100)) } // segments too small
        assertFailsWith<IllegalArgumentException> { DataSplit.chronological(candles, trainFraction = 0.8, validationFraction = 0.2) }
        assertFailsWith<IllegalArgumentException> { DataSplit.chronological(candles, trainFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { DataSplit.chronological(candles, embargoBars = -1) }
    }

    @Test
    fun `fingerprint identifies the data and is stable`() {
        assertEquals(split.fingerprint(), DataSplit.chronological(candles).fingerprint())
        val changed = candles.toMutableList()
        val i = 800
        changed[i] = changed[i].copy(close = changed[i].close + 0.01)
        assertNotEquals(split.fingerprint(), DataSplit.chronological(changed).fingerprint())
    }
}
