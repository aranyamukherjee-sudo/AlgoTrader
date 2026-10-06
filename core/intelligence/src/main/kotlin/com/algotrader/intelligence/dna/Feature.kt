package com.algotrader.intelligence.dna

/**
 * Broad family of a [FeatureRef]. The concrete feature is the free-string
 * [FeatureRef.type], so new indicators, patterns or generated rules need no
 * schema change.
 */
enum class FeatureDomain {
    INDICATOR,
    PRICE_ACTION,
    CHART_STRUCTURE,
    TREND,
    VOLATILITY,
    VOLUME,
    MARKET_CONTEXT,

    /** A rule whose logic lives in an existing engine Strategy class (opaque to the DNA). */
    ENGINE_RULE,
    CUSTOM
}

/** A typed parameter value; data-only so DNA stays machine-readable. */
sealed interface ParamValue {
    data class Num(val value: Double) : ParamValue {
        init {
            require(value.isFinite()) { "numeric parameter must be finite" }
        }
    }

    data class Text(val value: String) : ParamValue
    data class Flag(val value: Boolean) : ParamValue
}

/**
 * A reference to something computable/detectable from market data (an
 * indicator, a price-action structure, a context test). ASI-1 only defines the
 * reference; evaluating it belongs to later sprints.
 */
data class FeatureRef(
    val domain: FeatureDomain,
    val type: String,
    val params: Map<String, ParamValue> = emptyMap()
) {
    init {
        require(type.isNotBlank()) { "feature type must not be blank" }
        require(params.keys.all { it.isNotBlank() }) { "parameter names must not be blank" }
    }
}
