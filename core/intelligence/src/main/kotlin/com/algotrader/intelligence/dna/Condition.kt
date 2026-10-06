package com.algotrader.intelligence.dna

/**
 * A boolean rule tree. Pure data: it describes a rule, it never evaluates one.
 * "Break + confirmation" is [AllOf]; "structure failure OR trailing condition"
 * is [AnyOf]. Leaves point at a [FeatureRef], so a condition can be
 * indicator-based, price-action-based, or a mixture.
 */
sealed interface Condition {

    data class Leaf(
        /** Unique within one StrategyDna. */
        val id: String,
        val feature: FeatureRef,
        val description: String
    ) : Condition {
        init {
            require(id.isNotBlank()) { "condition id must not be blank" }
            require(description.isNotBlank()) { "condition description must not be blank" }
        }
    }

    data class AllOf(val conditions: List<Condition>) : Condition {
        init {
            require(conditions.isNotEmpty()) { "AllOf needs at least one condition" }
        }
    }

    data class AnyOf(val conditions: List<Condition>) : Condition {
        init {
            require(conditions.isNotEmpty()) { "AnyOf needs at least one condition" }
        }
    }

    fun leaves(): List<Leaf> = when (this) {
        is Leaf -> listOf(this)
        is AllOf -> conditions.flatMap { it.leaves() }
        is AnyOf -> conditions.flatMap { it.leaves() }
    }
}
