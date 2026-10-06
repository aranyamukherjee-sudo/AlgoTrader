package com.algotrader.discovery.split

import com.algotrader.domain.Candle
import com.algotrader.discovery.reproducibility.Hashing

enum class SegmentRole { TRAIN, VALIDATION, HOLDOUT }

/**
 * Three chronological, non-overlapping segments, with an embargo gap between
 * them:
 * - TRAIN: where candidates are generated and first filtered.
 * - VALIDATION: unseen by train; only train survivors are evaluated on it.
 * - HOLDOUT: the latest, untouched segment; only validation survivors are
 *   evaluated on it, once, as the final check.
 *
 * Invariants are enforced at construction, so a DataSplit that leaks later
 * data into an earlier segment cannot exist.
 */
data class DataSplit(
    val train: List<Candle>,
    val validation: List<Candle>,
    val holdout: List<Candle>,
    val embargoBars: Int
) {
    init {
        require(embargoBars >= 0) { "embargoBars must not be negative" }
        for ((role, candles) in listOf(
            SegmentRole.TRAIN to train, SegmentRole.VALIDATION to validation, SegmentRole.HOLDOUT to holdout
        )) {
            require(candles.isNotEmpty()) { "$role segment must not be empty" }
            require(candles.zipWithNext().all { (a, b) -> a.timestamp.isBefore(b.timestamp) }) {
                "$role candles must have strictly increasing timestamps"
            }
        }
        require(train.last().timestamp.isBefore(validation.first().timestamp)) {
            "validation must start after train ends"
        }
        require(validation.last().timestamp.isBefore(holdout.first().timestamp)) {
            "holdout must start after validation ends"
        }
    }

    fun segment(role: SegmentRole): List<Candle> = when (role) {
        SegmentRole.TRAIN -> train
        SegmentRole.VALIDATION -> validation
        SegmentRole.HOLDOUT -> holdout
    }

    /** Stable hash of every candle in every segment (dataset identity for reproducibility). */
    fun fingerprint(): String {
        val text = StringBuilder()
        for (role in SegmentRole.values()) {
            text.append(role.name).append(';')
            for (c in segment(role)) {
                text.append(c.timestamp.toEpochMilli()).append(',')
                    .append(c.open).append(',').append(c.high).append(',')
                    .append(c.low).append(',').append(c.close).append(',')
                    .append(c.volume).append('\n')
            }
        }
        return Hashing.sha256Hex(text.toString())
    }

    companion object {
        /**
         * Splits candles by time: first [trainFraction] for train, next
         * [validationFraction] for validation, the remainder for holdout,
         * discarding [embargoBars] bars at each boundary.
         */
        fun chronological(
            candles: List<Candle>,
            trainFraction: Double = 0.6,
            validationFraction: Double = 0.2,
            embargoBars: Int = 5,
            minSegmentBars: Int = 30
        ): DataSplit {
            require(trainFraction > 0.0 && validationFraction > 0.0 && trainFraction + validationFraction < 1.0) {
                "fractions must be positive and leave room for a holdout"
            }
            require(embargoBars >= 0) { "embargoBars must not be negative" }
            val sorted = candles.sortedBy { it.timestamp }
            require(sorted.zipWithNext().all { (a, b) -> a.timestamp.isBefore(b.timestamp) }) {
                "candles must have unique timestamps"
            }
            val n = sorted.size
            val trainEnd = (n * trainFraction).toInt()
            val validationStart = trainEnd + embargoBars
            val validationEnd = validationStart + (n * validationFraction).toInt()
            val holdoutStart = validationEnd + embargoBars
            require(holdoutStart < n) { "not enough candles for the requested split" }

            val train = sorted.subList(0, trainEnd).toList()
            val validation = sorted.subList(validationStart, validationEnd).toList()
            val holdout = sorted.subList(holdoutStart, n).toList()
            require(train.size >= minSegmentBars && validation.size >= minSegmentBars && holdout.size >= minSegmentBars) {
                "each segment needs at least $minSegmentBars bars (train=${train.size}, " +
                    "validation=${validation.size}, holdout=${holdout.size})"
            }
            return DataSplit(train, validation, holdout, embargoBars)
        }
    }
}
