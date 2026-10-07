package com.algotrader.discovery.pipeline

import com.algotrader.discovery.evaluation.SegmentEvaluation
import com.algotrader.discovery.scoring.CandidateScore
import com.algotrader.discovery.scoring.DiscoveryPolicy
import com.algotrader.discovery.scoring.Gate
import com.algotrader.intelligence.common.getOrThrow
import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.dna.EvidenceRef
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidenceSample
import com.algotrader.intelligence.evidence.EvidenceSource
import com.algotrader.intelligence.evidence.EvidenceSourceType
import com.algotrader.intelligence.evidence.EvidenceUnit
import com.algotrader.intelligence.lifecycle.StrategyLifecycle
import com.algotrader.intelligence.lifecycle.StrategyRecord
import java.time.Instant

/**
 * Turns a candidate that passed every gate into an ASI-1 StrategyRecord at
 * PROMISING. It can never go further: MONITORING and ACTIVE are live states
 * that discovery does not touch.
 */
internal object DiscoveryPromotion {

    fun promote(
        dna: StrategyDna,
        train: SegmentEvaluation,
        validation: SegmentEvaluation,
        holdout: SegmentEvaluation,
        gates: List<Gate>,
        score: CandidateScore,
        policy: DiscoveryPolicy,
        runKey: String,
        at: Instant,
        walkForward: WalkForwardEvaluation? = null
    ): CandidateRecord {
        val items = evidence(dna.ref, train, validation, holdout, runKey, at, walkForward)
        val finalDna = dna.copy(evidenceRefs = items.map { it.id })

        var record = StrategyRecord(finalDna, createdAt = at)
        for (item in items) record = record.addEvidence(item, at).getOrThrow()
        record = record.transitionTo(
            StrategyLifecycle.VALIDATING, at, "evaluated on train, validation and holdout segments"
        ).getOrThrow()
        record = record.transitionTo(
            StrategyLifecycle.PROMISING, at, "passed all discovery gates; historical evidence only, not live-approved"
        ).getOrThrow()

        return CandidateRecord(
            dna = finalDna,
            stage = DiscoveryStage.PROMISING,
            train = train,
            validation = validation,
            holdout = holdout,
            gates = gates,
            score = score,
            evidence = record.evidence,
            confidence = confidence(score, policy, at),
            strategyRecord = record,
            walkForward = walkForward
        )
    }

    /**
     * Confidence on the single ASI-1 scale (0.0..1.0): how strongly the
     * available evidence supports this strategy being PROMISING. It is NOT a
     * probability of profit. It equals the validation score, capped below 1.
     */
    fun confidence(score: CandidateScore, policy: DiscoveryPolicy, at: Instant): ConfidenceReading =
        ConfidenceReading(
            value = minOf(score.value, policy.maxConfidence),
            at = at,
            reason = "evidence-support strength from validation score; not a probability of profit"
        )

    private fun evidence(
        ref: StrategyRef,
        train: SegmentEvaluation,
        validation: SegmentEvaluation,
        holdout: SegmentEvaluation,
        runKey: String,
        at: Instant,
        walkForward: WalkForwardEvaluation?
    ): List<EvidenceItem> {
        fun id(tag: String) = EvidenceRef("${ref.id.value}.v${ref.version}.$tag")
        fun source(type: EvidenceSourceType, phase: String) =
            EvidenceSource(type, "$runKey/${ref.id.value}/$phase")

        val items = ArrayList<EvidenceItem>()
        items += EvidenceItem(
            id = id("train_edge"), strategy = ref, kind = EvidenceKind.HISTORICAL_EDGE,
            sample = EvidenceSample.IN_SAMPLE,
            summary = "Training segment: ${train.trades} trades, return ${train.returnPercent}% (used to find the candidate).",
            source = source(EvidenceSourceType.BACKTEST_RUN, "train"), recordedAt = at,
            value = train.returnPercent, unit = EvidenceUnit.PERCENT, observations = train.trades
        )
        items += EvidenceItem(
            id = id("train_consistency"), strategy = ref, kind = EvidenceKind.CONSISTENCY,
            sample = EvidenceSample.IN_SAMPLE,
            summary = "Training segment: ${train.periodConsistency} of equal time periods were profitable.",
            source = source(EvidenceSourceType.BACKTEST_RUN, "train"), recordedAt = at,
            value = train.periodConsistency, unit = EvidenceUnit.RATIO
        )
        validation.profitFactor?.let {
            items += EvidenceItem(
                id = id("validation_pf"), strategy = ref, kind = EvidenceKind.PROFIT_FACTOR,
                sample = EvidenceSample.VALIDATION,
                summary = "Validation segment (unseen when generated): profit factor $it over ${validation.trades} trades.",
                source = source(EvidenceSourceType.VALIDATION_RUN, "validation"), recordedAt = at,
                value = it, unit = EvidenceUnit.RATIO, observations = validation.trades
            )
        }
        items += EvidenceItem(
            id = id("validation_drawdown"), strategy = ref, kind = EvidenceKind.DRAWDOWN,
            sample = EvidenceSample.VALIDATION,
            summary = "Validation segment: max drawdown ${validation.maxDrawdownPercent}% of initial capital.",
            source = source(EvidenceSourceType.VALIDATION_RUN, "validation"), recordedAt = at,
            value = validation.maxDrawdownPercent, unit = EvidenceUnit.PERCENT
        )
        items += EvidenceItem(
            id = id("holdout_performance"), strategy = ref, kind = EvidenceKind.OUT_OF_SAMPLE_PERFORMANCE,
            sample = EvidenceSample.OUT_OF_SAMPLE,
            summary = "Untouched holdout: ${holdout.trades} trades, return ${holdout.returnPercent}%.",
            source = source(EvidenceSourceType.VALIDATION_RUN, "holdout"), recordedAt = at,
            value = holdout.returnPercent, unit = EvidenceUnit.PERCENT, observations = holdout.trades
        )

        walkForward?.folds?.forEach { fold ->
            val prefix = "wf_fold_${fold.foldIndex}"
            items += EvidenceItem(
                id = id("${prefix}_validation"),
                strategy = ref,
                kind = EvidenceKind.HISTORICAL_EDGE,
                sample = EvidenceSample.VALIDATION,
                summary = "Walk-forward fold ${fold.foldIndex}: validation ${fold.validation.trades} trades, return ${fold.validation.returnPercent}%.",
                source = source(EvidenceSourceType.VALIDATION_RUN, prefix),
                recordedAt = at,
                value = fold.validation.returnPercent,
                unit = EvidenceUnit.PERCENT,
                observations = fold.validation.trades
            )
            items += EvidenceItem(
                id = id("${prefix}_consistency"),
                strategy = ref,
                kind = EvidenceKind.CONSISTENCY,
                sample = EvidenceSample.VALIDATION,
                summary = "Walk-forward fold ${fold.foldIndex}: validation consistency ${fold.validation.periodConsistency}.",
                source = source(EvidenceSourceType.VALIDATION_RUN, prefix),
                recordedAt = at,
                value = fold.validation.periodConsistency,
                unit = EvidenceUnit.RATIO
            )
        }

        return items
    }
}
