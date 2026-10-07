package com.algotrader.discovery.split

import com.algotrader.domain.Candle
import com.algotrader.discovery.reproducibility.Hashing

/**
 * One deterministic walk-forward research fold.
 *
 * The validation window always occurs after the training window, with an
 * explicit embargo gap between them. No candle belongs to both windows.
 */
data class WalkForwardFold(
    val index: Int,
    val train: List<Candle>,
    val validation: List<Candle>,
    val embargoBars: Int
) {
    init {
        require(index >= 0) { "fold index must not be negative" }
        require(embargoBars >= 0) { "embargoBars must not be negative" }
        require(train.isNotEmpty()) { "train segment must not be empty" }
        require(validation.isNotEmpty()) { "validation segment must not be empty" }

        require(train.zipWithNext().all { (a, b) ->
            a.timestamp.isBefore(b.timestamp)
        }) {
            "train candles must have strictly increasing timestamps"
        }

        require(validation.zipWithNext().all { (a, b) ->
            a.timestamp.isBefore(b.timestamp)
        }) {
            "validation candles must have strictly increasing timestamps"
        }

        require(train.last().timestamp.isBefore(validation.first().timestamp)) {
            "validation must start after train ends"
        }
    }

    /** Stable identity for this fold's exact candle contents and boundaries. */
    fun fingerprint(): String {
        val text = StringBuilder()
        text.append("fold=").append(index).append(';')
        text.append("embargo=").append(embargoBars).append('\n')

        text.append("TRAIN\n")
        appendCandles(text, train)

        text.append("VALIDATION\n")
        appendCandles(text, validation)

        return Hashing.sha256Hex(text.toString())
    }

    private fun appendCandles(text: StringBuilder, candles: List<Candle>) {
        for (c in candles) {
            text.append(c.timestamp.toEpochMilli()).append(',')
                .append(c.open).append(',')
                .append(c.high).append(',')
                .append(c.low).append(',')
                .append(c.close).append(',')
                .append(c.volume).append('\n')
        }
    }
}

/**
 * Deterministic rolling walk-forward folds.
 *
 * Layout:
 *
 *   [train][embargo][validation]
 *                  [train][embargo][validation]
 *                               ...
 *
 * The next fold advances by [stepBars]. Training windows are rolling by
 * default: each fold uses the immediately preceding [trainBars] candles.
 *
 * Validation windows are fixed at [validationBars]. Embargo candles are
 * excluded from both train and validation and therefore cannot leak across
 * the boundary.
 */
object WalkForwardSplit {

    fun rolling(
        candles: List<Candle>,
        trainBars: Int,
        validationBars: Int,
        stepBars: Int = validationBars,
        embargoBars: Int = 5,
        minFolds: Int = 1
    ): List<WalkForwardFold> {
        require(trainBars >= 1) { "trainBars must be at least 1" }
        require(validationBars >= 1) { "validationBars must be at least 1" }
        require(stepBars >= 1) { "stepBars must be at least 1" }
        require(embargoBars >= 0) { "embargoBars must not be negative" }
        require(minFolds >= 1) { "minFolds must be at least 1" }

        val sorted = candles.sortedBy { it.timestamp }
        require(sorted.size == candles.size) {
            "candles must not contain null entries"
        }
        require(sorted.zipWithNext().all { (a, b) ->
            a.timestamp.isBefore(b.timestamp)
        }) {
            "candles must have unique timestamps"
        }

        val folds = ArrayList<WalkForwardFold>()
        var trainStart = 0

        while (true) {
            val trainEndExclusive = trainStart + trainBars
            val validationStart = trainEndExclusive + embargoBars
            val validationEndExclusive = validationStart + validationBars

            if (validationEndExclusive > sorted.size) break

            folds += WalkForwardFold(
                index = folds.size,
                train = sorted.subList(trainStart, trainEndExclusive).toList(),
                validation = sorted.subList(validationStart, validationEndExclusive).toList(),
                embargoBars = embargoBars
            )

            trainStart += stepBars
        }

        require(folds.size >= minFolds) {
            "not enough candles for $minFolds walk-forward fold(s); " +
                "available=${folds.size}, required=$minFolds"
        }

        return folds
    }
}
