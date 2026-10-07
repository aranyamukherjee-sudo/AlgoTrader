package com.algotrader.intelligence.structure

/**
 * Parameters for [PriceActionStructure.analyze]. All defaults are plain
 * starting values, not tuned or validated for any market.
 *
 * @property left bars BEFORE a pivot that must all be strictly less extreme.
 * @property right bars AFTER a pivot that must all be no more extreme; this is also the confirmation lag.
 * @property maxStrength cap on [SwingPivot.strength] (the left-side dominated run).
 * @property equalityTolerance two prices within this FRACTION of the larger magnitude count as equal
 *   (0.0 = exact equality). Used for EQH/EQL labels.
 * @property zoneTolerance pivots whose prices lie within this FRACTION of the cluster's lowest price share a zone.
 * @property minZoneTouches pivots needed to form a zone.
 * @property atrPeriod Wilder ATR period.
 */
data class StructureConfig(
    val left: Int = 2,
    val right: Int = 2,
    val maxStrength: Int = 20,
    val equalityTolerance: Double = 0.0,
    val zoneTolerance: Double = 0.002,
    val minZoneTouches: Int = 2,
    val atrPeriod: Int = 14
) {
    init {
        require(left >= 1) { "left must be >= 1" }
        require(right >= 1) { "right must be >= 1 (a pivot cannot be confirmed without later bars)" }
        require(maxStrength >= left) { "maxStrength must be >= left" }
        require(equalityTolerance.isFinite() && equalityTolerance >= 0.0) { "equalityTolerance must be finite and >= 0" }
        require(zoneTolerance.isFinite() && zoneTolerance >= 0.0) { "zoneTolerance must be finite and >= 0" }
        require(minZoneTouches >= 2) { "minZoneTouches must be >= 2" }
        require(atrPeriod >= 1) { "atrPeriod must be >= 1" }
    }

    /** Fewest candles in which a single pivot can exist and be confirmed. */
    val minCandles: Int get() = left + right + 1
}
