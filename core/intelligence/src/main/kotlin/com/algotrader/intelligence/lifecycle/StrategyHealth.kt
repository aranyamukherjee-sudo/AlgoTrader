package com.algotrader.intelligence.lifecycle

import java.time.Instant

/**
 * Health of a STRATEGY over time. Deliberately separate from opportunity
 * confidence: confidence is one setup's quality right now; health is whether
 * the strategy as a whole still shows its historical edge.
 */
enum class StrategyHealthStatus {
    HEALTHY,
    WEAKENING,
    DEGRADED,
    RETIRED;

    fun canTransitionTo(target: StrategyHealthStatus): Boolean =
        target == this || target in StrategyHealthRules.allowedFrom(this)
}

object StrategyHealthRules {
    private val table: Map<StrategyHealthStatus, Set<StrategyHealthStatus>> = mapOf(
        StrategyHealthStatus.HEALTHY to setOf(
            StrategyHealthStatus.WEAKENING, StrategyHealthStatus.DEGRADED, StrategyHealthStatus.RETIRED
        ),
        StrategyHealthStatus.WEAKENING to setOf(
            StrategyHealthStatus.HEALTHY, StrategyHealthStatus.DEGRADED, StrategyHealthStatus.RETIRED
        ),
        StrategyHealthStatus.DEGRADED to setOf(StrategyHealthStatus.WEAKENING, StrategyHealthStatus.RETIRED),
        StrategyHealthStatus.RETIRED to emptySet()
    )

    fun allowedFrom(status: StrategyHealthStatus): Set<StrategyHealthStatus> = table.getValue(status)
}

/**
 * One health assessment. [score] (0.0 worst .. 1.0 best) is optional because
 * scoring belongs to later sprints; a missing score is null, never a default.
 * Any non-HEALTHY status must say why.
 */
data class StrategyHealth(
    val status: StrategyHealthStatus,
    val assessedAt: Instant,
    val score: Double? = null,
    val reasons: List<String> = emptyList()
) {
    init {
        require(score == null || (score.isFinite() && score >= 0.0 && score <= 1.0)) {
            "health score must be within 0.0..1.0 when set"
        }
        require(reasons.all { it.isNotBlank() }) { "health reasons must not be blank" }
        if (status != StrategyHealthStatus.HEALTHY) {
            require(reasons.isNotEmpty()) { "a $status assessment must state at least one reason" }
        }
    }
}
