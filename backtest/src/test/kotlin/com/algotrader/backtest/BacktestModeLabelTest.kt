package com.algotrader.backtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BacktestModeLabelTest {

    @Test
    fun fullSampleIsStd() {
        assertEquals("STD", BacktestModeLabel.forSample(BacktestSample.FULL))
    }

    @Test
    fun bothSegmentsAreOos() {
        assertEquals("OOS", BacktestModeLabel.forSample(BacktestSample.IN_SAMPLE))
        assertEquals("OOS", BacktestModeLabel.forSample(BacktestSample.OUT_OF_SAMPLE))
    }

    @Test
    fun segmentsStayExplicit() {
        assertEquals("In-Sample", BacktestModeLabel.segmentLabel(BacktestSample.IN_SAMPLE))
        assertEquals("Out-of-Sample", BacktestModeLabel.segmentLabel(BacktestSample.OUT_OF_SAMPLE))
        assertNull(BacktestModeLabel.segmentLabel(BacktestSample.FULL))
    }

    @Test
    fun rupeesUseIndianGrouping() {
        assertEquals("\u20b91,00,000", BacktestModeLabel.rupees(100_000.0))
        assertEquals("\u20b9999", BacktestModeLabel.rupees(999.0))
        assertEquals("\u20b91,000", BacktestModeLabel.rupees(1_000.0))
        assertEquals("\u20b912,34,567", BacktestModeLabel.rupees(1_234_567.0))
        assertEquals("\u20b91,00,00,000", BacktestModeLabel.rupees(10_000_000.0))
        assertEquals("\u20b91,00,000.50", BacktestModeLabel.rupees(100_000.5))
    }

    @Test
    fun standardCompactLine() {
        assertEquals(
            "STD \u00b7 \u20b91,00,000 \u00b7 Completed",
            BacktestModeLabel.compactLine(BacktestSample.FULL, 100_000.0, "Completed")
        )
    }

    @Test
    fun oosCompactLineShowsSplitAndSegment() {
        assertEquals(
            "OOS \u00b7 70/30 \u00b7 In-Sample \u00b7 Completed",
            BacktestModeLabel.compactLine(BacktestSample.IN_SAMPLE, 100_000.0, "Completed")
        )
        assertEquals(
            "OOS \u00b7 70/30 \u00b7 Out-of-Sample \u00b7 Completed",
            BacktestModeLabel.compactLine(BacktestSample.OUT_OF_SAMPLE, 100_000.0, "Completed")
        )
    }
}
