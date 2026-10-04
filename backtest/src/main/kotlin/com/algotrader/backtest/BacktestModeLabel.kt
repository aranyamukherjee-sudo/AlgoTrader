package com.algotrader.backtest

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compact, presentation-only labels for the test mode of a saved backtest.
 *
 * STD = standard / full-period backtest. OOS = out-of-sample backtest.
 * Jobs saved before out-of-sample testing carry [BacktestSample.FULL] and are
 * therefore STD. Pure, so the labelling rules are unit-tested.
 */
object BacktestModeLabel {

    const val STANDARD = "STD"
    const val OUT_OF_SAMPLE = "OOS"

    /** "70/30", derived from the split constant so it can never drift from it. */
    const val SPLIT_LABEL = "${OutOfSampleSplit.IN_SAMPLE_PERCENT}/${100 - OutOfSampleSplit.IN_SAMPLE_PERCENT}"

    fun forSample(sample: BacktestSample): String =
        if (sample == BacktestSample.FULL) STANDARD else OUT_OF_SAMPLE

    /** Explicit segment name, or null for a standard (FULL) job that has no segments. */
    fun segmentLabel(sample: BacktestSample): String? = when (sample) {
        BacktestSample.FULL -> null
        BacktestSample.IN_SAMPLE -> "In-Sample"
        BacktestSample.OUT_OF_SAMPLE -> "Out-of-Sample"
    }

    /** Indian digit grouping with a rupee sign and no decimals for whole amounts: 100000 -> "₹1,00,000". */
    fun rupees(amount: Double): String {
        val rounded = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
        val negative = rounded.signum() < 0
        val plain = rounded.abs().toPlainString()
        val whole = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "")

        val grouped = if (whole.length <= 3) {
            whole
        } else {
            val head = whole.dropLast(3)
            val tail = whole.takeLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
        }

        val fractionPart = if (fraction.isBlank() || fraction.all { it == '0' }) "" else ".$fraction"
        return (if (negative) "-" else "") + "\u20b9" + grouped + fractionPart
    }

    /**
     * One-line summary for a saved test:
     *   STD · ₹1,00,000 · Completed
     *   OOS · 70/30 · In-Sample · Completed
     */
    fun compactLine(sample: BacktestSample, initialCapital: Double, statusLabel: String): String {
        val parts = mutableListOf(forSample(sample))
        if (sample == BacktestSample.FULL) {
            parts += rupees(initialCapital)
        } else {
            parts += SPLIT_LABEL
            segmentLabel(sample)?.let { parts += it }
        }
        parts += statusLabel
        return parts.joinToString(" \u00b7 ")
    }
}
