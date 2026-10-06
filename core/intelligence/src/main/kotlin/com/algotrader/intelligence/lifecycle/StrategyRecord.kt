package com.algotrader.intelligence.lifecycle

import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidenceScope
import java.time.Instant

data class LifecycleChange(
    val from: StrategyLifecycle,
    val to: StrategyLifecycle,
    val at: Instant,
    val reason: String
)

/**
 * The research-side record of ONE strategy version: its immutable DNA, its
 * lifecycle, its RESEARCH evidence and its health. It knows nothing about
 * individual opportunities, so an opportunity being cancelled can never
 * retire or degrade the strategy.
 *
 * A new DNA version gets a new record; evidence is bound to the exact version.
 */
data class StrategyRecord(
    val dna: StrategyDna,
    val createdAt: Instant,
    val lifecycle: StrategyLifecycle = StrategyLifecycle.DISCOVERED,
    val evidence: EvidenceLedger = EvidenceLedger(),
    val health: StrategyHealth? = null,
    val history: List<LifecycleChange> = emptyList(),
    val updatedAt: Instant = createdAt
) {
    init {
        require(evidence.items.all { it.scope == EvidenceScope.RESEARCH }) {
            "a strategy record holds research evidence only; live evidence belongs to opportunities"
        }
        require(evidence.items.all { it.strategy == dna.ref }) {
            "evidence must be about this exact strategy version (${dna.ref})"
        }
        require((history.lastOrNull()?.to ?: StrategyLifecycle.DISCOVERED) == lifecycle) {
            "lifecycle must match the last recorded change"
        }
    }

    val ref: StrategyRef get() = dna.ref

    fun transitionTo(
        target: StrategyLifecycle,
        at: Instant,
        reason: String
    ): TransitionResult<StrategyRecord> {
        if (reason.isBlank()) return reject("a lifecycle change needs a reason")
        if (at.isBefore(updatedAt)) return reject("change time is before the last update")
        if (lifecycle.isTerminal) return reject("$lifecycle is terminal; create a new strategy version instead")
        if (target == lifecycle) return reject("already $lifecycle")
        if (!lifecycle.canTransitionTo(target)) return reject("$lifecycle cannot move to $target")

        val needsEvidence = target == StrategyLifecycle.PROMISING ||
            target == StrategyLifecycle.MONITORING || target == StrategyLifecycle.ACTIVE
        if (needsEvidence && evidence.supporting().isEmpty()) {
            return reject("$target requires supporting research evidence")
        }
        val isLive = target == StrategyLifecycle.MONITORING || target == StrategyLifecycle.ACTIVE
        if (isLive && !dna.market.hasTradeTarget) {
            return reject("$target requires a tradable trade target; an index is reference-only")
        }

        return TransitionResult.Applied(
            copy(
                lifecycle = target,
                history = history + LifecycleChange(lifecycle, target, at, reason),
                updatedAt = at
            )
        )
    }

    fun addEvidence(item: EvidenceItem, at: Instant): TransitionResult<StrategyRecord> {
        if (at.isBefore(updatedAt)) return reject("time is before the last update")
        if (item.scope != EvidenceScope.RESEARCH) return reject("live evidence cannot be added to a strategy record")
        if (item.strategy != dna.ref) return reject("evidence belongs to ${item.strategy}, not ${dna.ref}")
        if (evidence.items.any { it.id == item.id }) return reject("evidence ${item.id.id} already recorded")
        return TransitionResult.Applied(copy(evidence = evidence.add(item), updatedAt = at))
    }

    /** Health may only move along [StrategyHealthRules]; RETIRED health requires a RETIRED lifecycle. */
    fun assessHealth(assessment: StrategyHealth, at: Instant): TransitionResult<StrategyRecord> {
        if (at.isBefore(updatedAt)) return reject("time is before the last update")
        val retiredHealth = assessment.status == StrategyHealthStatus.RETIRED
        if (retiredHealth && lifecycle != StrategyLifecycle.RETIRED) {
            return reject("retire the strategy through its lifecycle before marking health RETIRED")
        }
        if (lifecycle == StrategyLifecycle.RETIRED && !retiredHealth) {
            return reject("a RETIRED strategy can only have RETIRED health")
        }
        val previous = health
        if (previous != null && !previous.status.canTransitionTo(assessment.status)) {
            return reject("health cannot move from ${previous.status} to ${assessment.status}")
        }
        return TransitionResult.Applied(copy(health = assessment, updatedAt = at))
    }

    private fun reject(reason: String): TransitionResult.Rejected = TransitionResult.Rejected(reason)
}
