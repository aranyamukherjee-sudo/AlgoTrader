package com.algotrader.intelligence.pattern

import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.StructureConfig
import com.algotrader.intelligence.structure.SwingPivot
import java.time.Instant

/**
 * Pattern families (ASI-3.2). These are descriptive geometry labels over confirmed swing pivots, NOT signals and
 * NOT a prediction of direction. Declaration order is the tie-break order of the result.
 */
enum class PatternType {
    DOUBLE_TOP,
    DOUBLE_BOTTOM,

    /** "M": second high clearly lower than the first, but inside the same upper part of the swing. */
    M_TOP,

    /** "W": second low clearly higher than the first, but inside the same lower part of the swing. */
    W_BOTTOM,
    RECTANGLE,
    ASCENDING_TRIANGLE,
    DESCENDING_TRIANGLE,
    SYMMETRICAL_TRIANGLE,
    BULL_FLAG,
    BEAR_FLAG
}

/**
 * One detected pattern.
 *
 * @property pivots the member ASI-3.1 pivots, chronological. Layout per family:
 *   double/M/W = `[extreme1, neckline pivot, extreme2]`; rectangle/triangle = 4 alternating pivots;
 *   flags = `[pole start, pole end, flag pullback, flag counter-high/low]`.
 * @property confirmedIndex the candle at which the pattern became knowable: the latest member pivot's
 *   `confirmedIndex`. No candle after it is used, and none can change the pattern. Consumers must not act on the
 *   pattern before this candle.
 * @property high / [low] envelope of the member pivot prices.
 * @property neckline neckline price for double/M/W patterns, null for the others. Range or trendline boundaries
 *   for the other families are derivable from [pivots].
 */
data class DetectedPattern(
    val type: PatternType,
    val pivots: List<SwingPivot>,
    val startIndex: Int,
    val endIndex: Int,
    val confirmedIndex: Int,
    val confirmedAt: Instant,
    val high: Double,
    val low: Double,
    val neckline: Double? = null
) {
    init {
        require(pivots.size >= 3) { "a pattern needs at least 3 pivots" }
        require(pivots.zipWithNext().all { (a, b) -> a.index <= b.index }) { "pivots must be chronological" }
        require(startIndex <= endIndex && endIndex <= confirmedIndex) { "start <= end <= confirmed must hold" }
    }
}

/**
 * Result of pattern detection as of the last candle supplied. [status] is inherited from the ASI-3.1 structure
 * analysis (invalid input / too few candles); a series that is long enough but simply has no pattern is OK + empty.
 * [patterns] are ordered by confirmedIndex, then startIndex, then [PatternType] order.
 */
data class PatternAnalysis(
    val status: AnalysisStatus,
    val message: String,
    val candleCount: Int,
    val patterns: List<DetectedPattern>
)

/**
 * Tunables. Defaults are plain starting values, not tuned or validated on any market. All ratios are FRACTIONS.
 *
 * @property structure the ASI-3.1 configuration used to find pivots (left/right windows = confirmation lag).
 * @property levelTolerance two prices within this fraction of the larger magnitude are "the same level".
 * @property minDepth minimum swing depth (neckline depth / range height) as a fraction of the reference price.
 * @property maxSecondExtremeRatio M/W: how far the second extreme may sit from the first, as a fraction of the
 *   first-extreme-to-neckline distance (inclusive bound).
 * @property poleMinMove flags: minimum pole move as a fraction of the pole's starting price.
 * @property maxFlagRetrace flags: deepest allowed pullback as a fraction of the pole (inclusive bound).
 */
data class PatternConfig(
    val structure: StructureConfig = StructureConfig(),
    val levelTolerance: Double = 0.005,
    val minDepth: Double = 0.01,
    val maxSecondExtremeRatio: Double = 0.5,
    val poleMinMove: Double = 0.03,
    val maxFlagRetrace: Double = 0.5
) {
    init {
        require(levelTolerance.isFinite() && levelTolerance >= 0.0 && levelTolerance < 1.0) { "levelTolerance must be in [0, 1)" }
        require(minDepth.isFinite() && minDepth >= 0.0) { "minDepth must be finite and >= 0" }
        require(maxSecondExtremeRatio.isFinite() && maxSecondExtremeRatio > 0.0 && maxSecondExtremeRatio <= 1.0) {
            "maxSecondExtremeRatio must be in (0, 1]"
        }
        require(poleMinMove.isFinite() && poleMinMove > 0.0) { "poleMinMove must be finite and > 0" }
        require(maxFlagRetrace.isFinite() && maxFlagRetrace > 0.0 && maxFlagRetrace <= 1.0) {
            "maxFlagRetrace must be in (0, 1]"
        }
    }
}
