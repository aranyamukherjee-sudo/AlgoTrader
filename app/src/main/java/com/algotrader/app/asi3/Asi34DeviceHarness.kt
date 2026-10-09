package com.algotrader.app.asi3

import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.common.getOrThrow
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.FeatureDomain
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.dna.InstrumentKind
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.MarketScope
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyOrigin
import com.algotrader.intelligence.dna.TradeSide
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidencePolarity
import com.algotrader.intelligence.evidence.EvidenceSample
import com.algotrader.intelligence.evidence.EvidenceSource
import com.algotrader.intelligence.evidence.EvidenceSourceType
import com.algotrader.intelligence.dna.EvidenceRef
import com.algotrader.intelligence.lifecycle.StrategyLifecycle
import com.algotrader.intelligence.lifecycle.StrategyRecord
import java.time.Instant

/**
 * ASI-3.4 on-device verification of the production strategy lifecycle.
 * Uses production lifecycle/evidence APIs and deterministic local fixtures.
 */
object Asi34DeviceHarness {

    data class Result(
        val passed: Boolean,
        val message: String,
        val lifecyclePath: String,
        val historyCount: Int,
        val missingEvidenceRejected: Boolean,
        val illegalJumpRejected: Boolean,
        val retirementTerminal: Boolean,
        val indexOnlyRejected: Boolean,
        val rejectedChangePreservedOriginal: Boolean,
        val deterministic: Boolean
    )

    private val t0 = Instant.parse("2026-10-06T04:00:00Z")

    private val index = InstrumentRef(
        instrument = Instrument("ASI34-INDEX", "NSE"),
        kind = InstrumentKind.INDEX
    )

    private val future = InstrumentRef(
        instrument = Instrument("ASI34-FUT", "NSE"),
        kind = InstrumentKind.FUTURES,
        underlying = "ASI34",
        contractId = "ASI34-FUT-1"
    )

    private fun dna(tradeTarget: InstrumentRef? = future) = StrategyDna(
        id = StrategyId("asi34.lifecycle.fixture"),
        version = 1,
        name = "ASI-3.4 Lifecycle Fixture",
        origin = StrategyOrigin.GENERATED,
        market = MarketScope(signalSource = index, tradeTarget = tradeTarget),
        timeframe = Timeframe.MINUTE_15,
        sides = setOf(TradeSide.LONG),
        structures = listOf(FeatureRef(FeatureDomain.CHART_STRUCTURE, "asi34_structure")),
        requiredContext = Condition.Leaf(
            "asi34_context",
            FeatureRef(FeatureDomain.TREND, "asi34_trend"),
            "Deterministic lifecycle fixture context"
        ),
        entry = Condition.Leaf(
            "asi34_entry",
            FeatureRef(FeatureDomain.PRICE_ACTION, "asi34_entry"),
            "Deterministic lifecycle fixture entry"
        ),
        exit = Condition.Leaf(
            "asi34_exit",
            FeatureRef(FeatureDomain.PRICE_ACTION, "asi34_exit"),
            "Deterministic lifecycle fixture exit"
        ),
        invalidation = Condition.Leaf(
            "asi34_invalidation",
            FeatureRef(FeatureDomain.PRICE_ACTION, "asi34_invalidation"),
            "Deterministic lifecycle fixture invalidation"
        )
    )

    private fun evidence(strategy: StrategyDna, polarity: EvidencePolarity = EvidencePolarity.SUPPORTING) =
        EvidenceItem(
            id = EvidenceRef(if (polarity == EvidencePolarity.SUPPORTING) "asi34-support" else "asi34-contradict"),
            strategy = strategy.ref,
            kind = EvidenceKind.OUT_OF_SAMPLE_PERFORMANCE,
            sample = EvidenceSample.OUT_OF_SAMPLE,
            summary = "Deterministic ASI-3.4 research fixture",
            source = EvidenceSource(
                EvidenceSourceType.BACKTEST_RUN,
                "asi34-device-fixture"
            ),
            recordedAt = t0,
            polarity = polarity
        )

    private fun record(
        strategy: StrategyDna = dna(),
        withEvidence: Boolean = false
    ): StrategyRecord {
        var record = StrategyRecord(dna = strategy, createdAt = t0)
        if (withEvidence) {
            record = record.addEvidence(evidence(strategy), t0).getOrThrow()
        }
        return record
    }

    private fun advance(
        record: StrategyRecord,
        target: StrategyLifecycle,
        minute: Long
    ) = record.transitionTo(target, t0.plusSeconds(minute * 60), "ASI-3.4 device test: $target")

    private fun check(): Result {
        val supported = record(withEvidence = true)
        val path = listOf(
            StrategyLifecycle.VALIDATING,
            StrategyLifecycle.PROMISING,
            StrategyLifecycle.MONITORING,
            StrategyLifecycle.ACTIVE,
            StrategyLifecycle.DEGRADING,
            StrategyLifecycle.VALIDATING,
            StrategyLifecycle.RETIRED
        )

        var current = supported
        path.forEachIndexed { index, state ->
            current = advance(current, state, index.toLong() + 1).getOrThrow()
        }

        val pathPassed =
            current.lifecycle == StrategyLifecycle.RETIRED &&
            current.history.size == path.size &&
            current.history.map { it.to } == path

        val validatingWithoutEvidence =
            advance(record(), StrategyLifecycle.VALIDATING, 1).getOrThrow()

        val missingEvidenceRejected =
            advance(validatingWithoutEvidence, StrategyLifecycle.PROMISING, 2)
                .let { it is TransitionResult.Rejected }

        val illegalJumpRejected =
            advance(record(withEvidence = true), StrategyLifecycle.ACTIVE, 1)
                .let { it is TransitionResult.Rejected }

        val retiredTerminal = StrategyLifecycle.values().all { target ->
            advance(current, target, 20) is TransitionResult.Rejected
        } && current.lifecycle.isTerminal &&
            current.lifecycle.allowedNext().isEmpty()

        val indexDna = dna(tradeTarget = null)
        var indexRecord = record(indexDna, withEvidence = true)
        indexRecord = advance(indexRecord, StrategyLifecycle.VALIDATING, 1).getOrThrow()
        indexRecord = advance(indexRecord, StrategyLifecycle.PROMISING, 2).getOrThrow()
        val indexOnlyRejected =
            advance(indexRecord, StrategyLifecycle.MONITORING, 3)
                .let { it is TransitionResult.Rejected }

        val untouched = record(withEvidence = true)
        val rejected = advance(untouched, StrategyLifecycle.ACTIVE, 1)
        val rejectedChangePreservedOriginal =
            rejected is TransitionResult.Rejected &&
            untouched.lifecycle == StrategyLifecycle.DISCOVERED &&
            untouched.history.isEmpty() &&
            untouched.updatedAt == t0

        val passed = pathPassed &&
            missingEvidenceRejected &&
            illegalJumpRejected &&
            retiredTerminal &&
            indexOnlyRejected &&
            rejectedChangePreservedOriginal

        return Result(
            passed = passed,
            message = if (passed) {
                "All ASI-3.4 production lifecycle checks passed."
            } else {
                "One or more ASI-3.4 lifecycle checks failed."
            },
            lifecyclePath = (listOf(StrategyLifecycle.DISCOVERED) + path).joinToString(" -> "),
            historyCount = current.history.size,
            missingEvidenceRejected = missingEvidenceRejected,
            illegalJumpRejected = illegalJumpRejected,
            retirementTerminal = retiredTerminal,
            indexOnlyRejected = indexOnlyRejected,
            rejectedChangePreservedOriginal = rejectedChangePreservedOriginal,
            deterministic = true
        )
    }

    fun run(): Result {
        val first = check()
        val second = check()
        val deterministic = first.copy(deterministic = true) == second.copy(deterministic = true)
        return first.copy(
            passed = first.passed && deterministic,
            deterministic = deterministic,
            message = if (first.passed && deterministic) {
                "All ASI-3.4 production lifecycle checks passed."
            } else {
                "ASI-3.4 verification failed; inspect individual checks."
            }
        )
    }
}
