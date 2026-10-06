package com.algotrader.intelligence.dna

import com.algotrader.domain.Timeframe

@JvmInline
value class StrategyId(val value: String) {
    init {
        require(value.isNotBlank()) { "strategy id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Pins one exact version of one strategy. Evidence and opportunities point at
 * a [StrategyRef], never just an id, so evolving a strategy (a new version)
 * cannot rewrite what earlier versions or their opportunities were based on.
 */
data class StrategyRef(val id: StrategyId, val version: Int) {
    init {
        require(version >= 1) { "version must be at least 1" }
    }
}

/** Pointer to an evidence item (kept in an EvidenceLedger) that supports a DNA version. */
@JvmInline
value class EvidenceRef(val id: String) {
    init {
        require(id.isNotBlank()) { "evidence id must not be blank" }
    }
}

enum class StrategyOrigin { BUILT_IN, USER_DEFINED, DISCOVERED, GENERATED }

enum class TradeSide { LONG, SHORT }

enum class StrategyComposition { INDICATOR_BASED, PRICE_ACTION_BASED, HYBRID, RULE_BASED }

/**
 * Links a DNA to an existing executable engine Strategy (same ids and
 * parameters as StrategyRegistry / StrategyConfiguration). The DNA does not
 * depend on the engine; this is just a string reference.
 */
data class EngineBinding(
    val engineStrategyId: String,
    val parameters: Map<String, Double> = emptyMap()
) {
    init {
        require(engineStrategyId.isNotBlank()) { "engineStrategyId must not be blank" }
    }
}

/**
 * Machine-readable, immutable description of one version of a strategy: a
 * reusable rule set. It is NOT an opportunity (a current market occurrence)
 * and it holds no live state, lifecycle or evidence values.
 *
 * Nothing assumes indicators: [indicators] and [structures] are both optional
 * and conditions can reference any [FeatureRef]. Evidence accrues over time
 * while DNA is fixed per version, so DNA holds only [evidenceRefs] (ids); the
 * evidence itself lives in an EvidenceLedger on the StrategyRecord.
 */
data class StrategyDna(
    val id: StrategyId,
    val version: Int = 1,
    val name: String,
    val origin: StrategyOrigin,
    val market: MarketScope,
    val timeframe: Timeframe,
    val sides: Set<TradeSide>,
    val indicators: List<FeatureRef> = emptyList(),
    val structures: List<FeatureRef> = emptyList(),
    /** Market context that must hold (trend alignment, volatility regime...). */
    val requiredContext: Condition? = null,
    val entry: Condition,
    val confirmation: Condition? = null,
    /** First-class: every DNA defines its exit conditions. */
    val exit: Condition,
    /** When true the setup is void (before entry) / the structure has failed. */
    val invalidation: Condition? = null,
    val risk: RiskSpec = RiskSpec(),
    val evidenceRefs: List<EvidenceRef> = emptyList(),
    val engineBinding: EngineBinding? = null,
    /** Previous version / parent strategy this one evolved from. */
    val derivedFrom: StrategyRef? = null,
    val description: String = ""
) {
    init {
        require(version >= 1) { "version must be at least 1" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(sides.isNotEmpty()) { "at least one trade side is required" }
        require(indicators.all { it.domain == FeatureDomain.INDICATOR }) {
            "indicators must have domain INDICATOR"
        }
        require(structures.all {
            it.domain == FeatureDomain.PRICE_ACTION || it.domain == FeatureDomain.CHART_STRUCTURE
        }) { "structures must have domain PRICE_ACTION or CHART_STRUCTURE" }
        require(evidenceRefs.toSet().size == evidenceRefs.size) { "evidenceRefs must be unique" }

        val duplicateLeafIds = listOfNotNull(requiredContext, entry, confirmation, exit, invalidation)
            .flatMap { it.leaves() }
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateLeafIds.isEmpty()) { "duplicate condition ids: $duplicateLeafIds" }

        if (derivedFrom != null && derivedFrom.id == id) {
            require(derivedFrom.version < version) {
                "a new version must be greater than the version it derives from"
            }
        }
    }

    val ref: StrategyRef get() = StrategyRef(id, version)

    val composition: StrategyComposition
        get() = when {
            indicators.isNotEmpty() && structures.isNotEmpty() -> StrategyComposition.HYBRID
            indicators.isNotEmpty() -> StrategyComposition.INDICATOR_BASED
            structures.isNotEmpty() -> StrategyComposition.PRICE_ACTION_BASED
            else -> StrategyComposition.RULE_BASED
        }

    /**
     * Starts the next version of this strategy: same id, version + 1, lineage
     * pointing at this version, and NO evidence refs (evidence for the old
     * version is never inherited). Callers then change rules via copy().
     */
    fun nextVersion(): StrategyDna = copy(
        version = version + 1,
        derivedFrom = ref,
        evidenceRefs = emptyList()
    )
}
