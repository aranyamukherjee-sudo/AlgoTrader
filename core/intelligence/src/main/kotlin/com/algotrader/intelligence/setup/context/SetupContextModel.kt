package com.algotrader.intelligence.setup.context

import com.algotrader.intelligence.setup.BreakoutDirection
import com.algotrader.intelligence.setup.BreakoutId
import com.algotrader.intelligence.setup.SetupStage
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.ZoneKind

enum class SetupContextFactorKind {
    MARKET_STRUCTURE,
    PATTERN_CONTEXT,
    LEVEL_CONTEXT,
    LIFECYCLE_CONTEXT
}

enum class SetupContextPolarity {
    SUPPORTING,
    CONFLICTING,
    NEUTRAL
}

enum class SetupQuality {
    INSUFFICIENT,
    WEAK,
    MODERATE,
    STRONG
}

data class SetupContextLevel(
    val side: ZoneKind,
    val price: Double,
    val knownAtIndex: Int,
    val sourceIds: List<String>
) {
    init {
        require(price.isFinite()) { "level price must be finite" }
        require(knownAtIndex >= 0) { "knownAtIndex must be non-negative" }
        require(sourceIds.isNotEmpty()) { "context level needs at least one source" }
        require(sourceIds == sourceIds.distinct().sorted()) {
            "context level sources must be unique and deterministic"
        }
    }
}

data class SetupContextFactor(
    val id: String,
    val kind: SetupContextFactorKind,
    val polarity: SetupContextPolarity,
    val knownAtIndex: Int,
    val summary: String,
    val sourceIds: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "factor id must not be blank" }
        require(knownAtIndex >= 0) { "knownAtIndex must be non-negative" }
        require(summary.isNotBlank()) { "factor summary must not be blank" }
        require(sourceIds == sourceIds.distinct().sorted()) {
            "factor sources must be unique and deterministic"
        }
    }
}

data class SetupContext(
    val breakoutId: BreakoutId,
    val direction: BreakoutDirection,
    val asOfIndex: Int,
    val marketStructure: MarketStructureState,
    val lifecycleStage: SetupStage,
    val relevantLevels: List<SetupContextLevel>,
    val factors: List<SetupContextFactor>
) {
    init {
        require(asOfIndex >= 0) { "asOfIndex must be non-negative" }
        require(
            relevantLevels.zipWithNext().all { (a, b) ->
                compareLevels(a, b) <= 0
            }
        ) { "relevant levels must use deterministic ordering" }
        require(factors.map { it.id } == factors.map { it.id }.distinct()) {
            "context factor ids must be unique"
        }
        require(
            factors.zipWithNext().all { (a, b) ->
                compareFactors(a, b) <= 0
            }
        ) { "context factors must use deterministic ordering" }
        require(
            relevantLevels.all { it.knownAtIndex <= asOfIndex }
        ) { "context level cannot be known after asOfIndex" }
        require(
            factors.all { it.knownAtIndex <= asOfIndex }
        ) { "context factor cannot be known after asOfIndex" }
    }

    val supportingFactors: List<SetupContextFactor>
        get() = factors.filter { it.polarity == SetupContextPolarity.SUPPORTING }

    val conflictingFactors: List<SetupContextFactor>
        get() = factors.filter { it.polarity == SetupContextPolarity.CONFLICTING }

    companion object {
        private fun compareLevels(
            a: SetupContextLevel,
            b: SetupContextLevel
        ): Int {
            val side = a.side.ordinal.compareTo(b.side.ordinal)
            if (side != 0) return side

            val price = a.price.compareTo(b.price)
            if (price != 0) return price

            val known = a.knownAtIndex.compareTo(b.knownAtIndex)
            if (known != 0) return known

            return a.sourceIds.joinToString("|").compareTo(
                b.sourceIds.joinToString("|")
            )
        }

        private fun compareFactors(
            a: SetupContextFactor,
            b: SetupContextFactor
        ): Int =
            a.id.compareTo(b.id)
    }
}
