package com.algotrader.intelligence.structure

import kotlin.math.abs

/** How a pivot compares with the previous pivot of the SAME type. */
enum class StructureLabel {
    /** No earlier pivot of this type to compare with. */
    FIRST,
    HH, LH, EQH,
    HL, LL, EQL
}

data class ClassifiedPivot(val pivot: SwingPivot, val label: StructureLabel)

enum class MarketStructureState {
    /** Not enough comparable pivots: needs a labelled (non-FIRST) high AND a labelled low. */
    UNDEFINED,
    /** Latest high is HH and latest low is HL. */
    UPTREND,
    /** Latest high is LH and latest low is LL. */
    DOWNTREND,
    /** Any other comparable combination (equal levels, expansion HH+LL, contraction LH+HL). */
    RANGE
}

internal object MarketStructure {

    fun sameLevel(a: Double, b: Double, tolerance: Double): Boolean =
        abs(a - b) <= tolerance * maxOf(abs(a), abs(b))

    /** [pivots] must be in detector order. Each pivot is compared only with EARLIER pivots of its type. */
    fun classify(pivots: List<SwingPivot>, tolerance: Double): List<ClassifiedPivot> {
        var prevHigh: Double? = null
        var prevLow: Double? = null
        return pivots.map { p ->
            val prev = if (p.type == PivotType.HIGH) prevHigh else prevLow
            val label = when {
                prev == null -> StructureLabel.FIRST
                sameLevel(p.price, prev, tolerance) ->
                    if (p.type == PivotType.HIGH) StructureLabel.EQH else StructureLabel.EQL
                p.price > prev -> if (p.type == PivotType.HIGH) StructureLabel.HH else StructureLabel.HL
                else -> if (p.type == PivotType.HIGH) StructureLabel.LH else StructureLabel.LL
            }
            if (p.type == PivotType.HIGH) prevHigh = p.price else prevLow = p.price
            ClassifiedPivot(p, label)
        }
    }

    fun state(classified: List<ClassifiedPivot>): MarketStructureState {
        val high = classified.lastOrNull { it.pivot.type == PivotType.HIGH }?.label
        val low = classified.lastOrNull { it.pivot.type == PivotType.LOW }?.label
        if (high == null || low == null || high == StructureLabel.FIRST || low == StructureLabel.FIRST) {
            return MarketStructureState.UNDEFINED
        }
        return when {
            high == StructureLabel.HH && low == StructureLabel.HL -> MarketStructureState.UPTREND
            high == StructureLabel.LH && low == StructureLabel.LL -> MarketStructureState.DOWNTREND
            else -> MarketStructureState.RANGE
        }
    }
}
