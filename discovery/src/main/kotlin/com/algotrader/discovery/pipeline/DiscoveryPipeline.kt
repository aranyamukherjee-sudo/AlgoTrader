package com.algotrader.discovery.pipeline

import com.algotrader.backtest.BacktestConfig
import com.algotrader.discovery.candidate.CandidateGenerator
import com.algotrader.discovery.evaluation.SegmentEvaluation
import com.algotrader.discovery.evaluation.SegmentEvaluator
import com.algotrader.discovery.features.FeatureRegistry
import com.algotrader.discovery.reproducibility.DnaCanonical
import com.algotrader.discovery.reproducibility.Hashing
import com.algotrader.discovery.rules.RuleStrategy
import com.algotrader.discovery.scoring.CandidateScore
import com.algotrader.discovery.scoring.DiscoveryGates
import com.algotrader.discovery.scoring.DiscoveryPolicy
import com.algotrader.discovery.scoring.DiscoveryScoring
import com.algotrader.discovery.scoring.Gate
import com.algotrader.discovery.split.DataSplit
import com.algotrader.discovery.split.SegmentRole
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.confidence.ConfidenceReading
import com.algotrader.intelligence.dna.MarketScope
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.lifecycle.StrategyLifecycle
import com.algotrader.intelligence.lifecycle.StrategyRecord
import java.time.Instant

data class DiscoveryRequest(
    val market: MarketScope,
    val timeframe: Timeframe,
    val split: DataSplit,
    /** The timestamp stamped on evidence and lifecycle changes (injected so runs are reproducible). */
    val at: Instant,
    val policy: DiscoveryPolicy = DiscoveryPolicy(),
    val backtestConfig: BacktestConfig = BacktestConfig(),
    val registry: FeatureRegistry = FeatureRegistry.standard(),
    /** Optional ASI-2 walk-forward validation over the research region. */
    val walkForward: WalkForwardConfig? = null
)

/** How far a candidate got. Anything other than PROMISING is a rejected research result. */
enum class DiscoveryStage { REJECTED_TRAIN, REJECTED_VALIDATION, REJECTED_HOLDOUT, PROMISING }

/** An audit entry: which segment a candidate was evaluated on. Written by the pipeline itself. */
data class PhaseRun(
    val phase: SegmentRole,
    val candidate: StrategyId,
    val firstTimestamp: Instant,
    val lastTimestamp: Instant,
    val bars: Int,
    val foldIndex: Int? = null
)

/**
 * The research outcome for one candidate.
 *
 * Only PROMISING candidates carry evidence, confidence and a [StrategyRecord]
 * (at lifecycle PROMISING, never beyond). Rejected candidates stay plain
 * research results: evaluations and the failed gates, nothing else.
 */
data class CandidateRecord(
    val dna: StrategyDna,
    val stage: DiscoveryStage,
    val train: SegmentEvaluation,
    val validation: SegmentEvaluation?,
    val holdout: SegmentEvaluation?,
    val gates: List<Gate>,
    val score: CandidateScore?,
    val evidence: EvidenceLedger,
    val confidence: ConfidenceReading?,
    val strategyRecord: StrategyRecord?,
    val walkForward: WalkForwardEvaluation? = null
) {
    val isPromising: Boolean get() = stage == DiscoveryStage.PROMISING

    val rejectionReasons: List<String>
        get() = gates.filter { !it.passed }.map { "${it.phase}/${it.name}: ${it.detail}" }

    init {
        when (stage) {
            DiscoveryStage.REJECTED_TRAIN ->
                require(validation == null && holdout == null) { "rejected at train: later segments must be unused" }
            DiscoveryStage.REJECTED_VALIDATION ->
                require(validation != null && holdout == null) { "rejected at validation: holdout must be unused" }
            DiscoveryStage.REJECTED_HOLDOUT, DiscoveryStage.PROMISING ->
                require(validation != null && holdout != null) { "this stage requires validation and holdout results" }
        }
        if (stage == DiscoveryStage.PROMISING) {
            require(gates.all { it.passed }) { "a promising candidate passed every gate" }
            require(score != null && confidence != null && !evidence.isEmpty) {
                "a promising candidate carries score, confidence and evidence"
            }
            require(strategyRecord != null && strategyRecord.lifecycle == StrategyLifecycle.PROMISING) {
                "a promising candidate enters the lifecycle at PROMISING and no further"
            }
        } else {
            require(gates.any { !it.passed }) { "a rejected candidate must have a failed gate" }
            require(strategyRecord == null && confidence == null && evidence.isEmpty) {
                "rejected candidates are research results only"
            }
        }
    }
}

data class RunInfo(
    val runKey: String,
    val datasetFingerprint: String,
    val candidatesGenerated: Int,
    val trainSurvivors: Int,
    val validationSurvivors: Int,
    val promising: Int,
    val policy: DiscoveryPolicy
)

data class DiscoveryResult(
    val runInfo: RunInfo,
    /** Promising candidates first (best validation score first), then rejected ones in generation order. */
    val candidates: List<CandidateRecord>,
    val phaseLog: List<PhaseRun>
) {
    val promising: List<CandidateRecord> get() = candidates.filter { it.isPromising }
    val rejected: List<CandidateRecord> get() = candidates.filter { !it.isPromising }
}

/**
 * Candidate generation -> train filter -> validation filter -> untouched
 * holdout -> evidence/confidence -> ASI-1 lifecycle (PROMISING at most).
 *
 * Data discipline:
 * - every candidate is evaluated on TRAIN;
 * - only train survivors are ever evaluated on VALIDATION;
 * - only validation survivors are ever evaluated on HOLDOUT, once each.
 * Results of later segments never influence earlier ones, and the pipeline
 * records every evaluation in [DiscoveryResult.phaseLog].
 *
 * Known limitation: no multiple-testing correction. The candidate cap, the
 * validation stage and the holdout limit false positives; they do not remove
 * them. Passing means "promising", not "proven".
 */
class DiscoveryPipeline(private val generator: CandidateGenerator) {

    fun run(request: DiscoveryRequest): DiscoveryResult {
        val policy = request.policy
        val dnas = generator.generate(request.market, request.timeframe)
        require(dnas.isNotEmpty()) { "the generator produced no candidates" }
        require(dnas.size <= policy.maxCandidates) {
            "${dnas.size} candidates exceed maxCandidates=${policy.maxCandidates}"
        }
        require(dnas.map { it.id }.toSet().size == dnas.size) { "candidate ids must be unique" }
        dnas.forEach {
            val unknown = request.registry.unknownFeatures(it)
            require(unknown.isEmpty()) { "candidate ${it.id} uses unsupported features $unknown" }
        }

        val evaluator = SegmentEvaluator(
            config = request.backtestConfig.copy(researchCostModel = policy.researchCostModel),
            consistencyPeriods = policy.consistencyPeriods,
            researchCostModel = policy.researchCostModel
        )
        val log = ArrayList<PhaseRun>()
        val datasetFingerprint = request.split.fingerprint()
        val runKey = Hashing.short(
            listOf(
                datasetFingerprint,
                policy.toString(),
                request.backtestConfig.toString(),
                request.timeframe.toString(),
                request.walkForward?.toString() ?: "walkForward=off",
                dnas.joinToString(";") { DnaCanonical.key(it) }
            ).joinToString("|")
        )

        fun evaluateOn(
            role: SegmentRole,
            dna: StrategyDna,
            strategy: RuleStrategy,
            candles: List<com.algotrader.domain.Candle>,
            foldIndex: Int? = null
        ): SegmentEvaluation {
            log += PhaseRun(
                role,
                dna.id,
                candles.first().timestamp,
                candles.last().timestamp,
                candles.size,
                foldIndex
            )
            return evaluator.evaluate(role, strategy, candles)
        }

        val records = ArrayList<CandidateRecord>()
        for (dna in dnas) {
            val strategy = RuleStrategy(dna, request.registry)

            val train = evaluateOn(
                SegmentRole.TRAIN,
                dna,
                strategy,
                request.split.train
            )
            val trainGates = DiscoveryGates.train(train, policy)
            if (trainGates.any { !it.passed }) {
                records += rejectedRecord(dna, DiscoveryStage.REJECTED_TRAIN, train, null, null, trainGates, null)
                continue
            }

            val validation = evaluateOn(
                SegmentRole.VALIDATION,
                dna,
                strategy,
                request.split.validation
            )
            val validationGates = DiscoveryGates.validation(train, validation, policy)
            val gatesSoFar = trainGates + validationGates
            if (validationGates.any { !it.passed }) {
                records += rejectedRecord(dna, DiscoveryStage.REJECTED_VALIDATION, train, validation, null, gatesSoFar, null)
                continue
            }
            val baseScore = DiscoveryScoring.score(train, validation, policy)

            val walkForward = request.walkForward?.let { wfConfig ->
                val researchCandles = request.split.train + request.split.validation
                val folds = com.algotrader.discovery.split.WalkForwardSplit.rolling(
                    candles = researchCandles,
                    trainBars = wfConfig.trainBars,
                    validationBars = wfConfig.validationBars,
                    stepBars = wfConfig.stepBars,
                    embargoBars = wfConfig.embargoBars,
                    minFolds = wfConfig.minFolds
                )

                val evaluated = ArrayList<WalkForwardFoldEvaluation>()

                for (fold in folds) {
                    val foldTrain = evaluateOn(
                        SegmentRole.TRAIN,
                        dna,
                        strategy,
                        fold.train,
                        fold.index
                    )
                    val foldValidation = evaluateOn(
                        SegmentRole.VALIDATION,
                        dna,
                        strategy,
                        fold.validation,
                        fold.index
                    )

                    val foldGates =
                        DiscoveryGates.train(foldTrain, policy) +
                        DiscoveryGates.validation(foldTrain, foldValidation, policy)

                    val foldScore =
                        if (foldGates.all { it.passed }) {
                            DiscoveryScoring.score(foldTrain, foldValidation, policy)
                        } else {
                            null
                        }

                    evaluated += WalkForwardFoldEvaluation(
                        foldIndex = fold.index,
                        train = foldTrain,
                        validation = foldValidation,
                        gates = foldGates,
                        score = foldScore
                    )

                    if (foldGates.any { !it.passed }) break
                }

                val wfGates = evaluated.flatMap { it.gates }
                val aggregate =
                    if (evaluated.isNotEmpty() && evaluated.all { it.passed }) {
                        DiscoveryScoring.aggregateWalkForward(
                            evaluated.mapNotNull { it.score }
                        )
                    } else {
                        null
                    }

                WalkForwardEvaluation(
                    folds = evaluated,
                    aggregateScore = aggregate,
                    gates = wfGates
                )
            }

            if (walkForward != null && !walkForward.allPassed) {
                val wfGates = gatesSoFar + walkForward.gates
                records += rejectedRecord(
                    dna,
                    DiscoveryStage.REJECTED_VALIDATION,
                    train,
                    validation,
                    null,
                    wfGates,
                    baseScore,
                    walkForward
                )
                continue
            }

            val score = walkForward?.aggregateScore ?: baseScore

            val holdout = evaluateOn(
                SegmentRole.HOLDOUT,
                dna,
                strategy,
                request.split.holdout
            )
            val holdoutGates = DiscoveryGates.holdout(holdout, policy)
            val allGates =
                gatesSoFar +
                (walkForward?.gates ?: emptyList()) +
                holdoutGates

            if (holdoutGates.any { !it.passed }) {
                records += rejectedRecord(
                    dna,
                    DiscoveryStage.REJECTED_HOLDOUT,
                    train,
                    validation,
                    holdout,
                    allGates,
                    score,
                    walkForward
                )
                continue
            }

            records += DiscoveryPromotion.promote(
                dna,
                train,
                validation,
                holdout,
                allGates,
                score,
                policy,
                runKey,
                request.at,
                walkForward
            )
        }

        val promising = records.filter { it.isPromising }
            .sortedWith(compareByDescending<CandidateRecord> { it.score?.value ?: 0.0 }.thenBy { it.dna.id.value })
        val rejected = records.filter { !it.isPromising }
        val info = RunInfo(
            runKey = runKey,
            datasetFingerprint = datasetFingerprint,
            candidatesGenerated = dnas.size,
            trainSurvivors = records.count { it.stage != DiscoveryStage.REJECTED_TRAIN },
            validationSurvivors = records.count {
                it.stage == DiscoveryStage.REJECTED_HOLDOUT || it.stage == DiscoveryStage.PROMISING
            },
            promising = promising.size,
            policy = policy
        )
        return DiscoveryResult(info, promising + rejected, log)
    }

    private fun rejectedRecord(
        dna: StrategyDna,
        stage: DiscoveryStage,
        train: SegmentEvaluation,
        validation: SegmentEvaluation?,
        holdout: SegmentEvaluation?,
        gates: List<Gate>,
        score: CandidateScore?,
        walkForward: WalkForwardEvaluation? = null
    ) = CandidateRecord(
        dna,
        stage,
        train,
        validation,
        holdout,
        gates,
        score,
        EvidenceLedger(),
        null,
        null,
        walkForward
    )
}
