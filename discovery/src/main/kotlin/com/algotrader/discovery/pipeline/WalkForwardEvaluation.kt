package com.algotrader.discovery.pipeline

import com.algotrader.discovery.evaluation.SegmentEvaluation
import com.algotrader.discovery.scoring.CandidateScore
import com.algotrader.discovery.scoring.Gate
import com.algotrader.discovery.split.SegmentRole

data class WalkForwardConfig(
    val trainBars: Int = 120,
    val validationBars: Int = 60,
    val stepBars: Int = 60,
    val embargoBars: Int = 5,
    val minFolds: Int = 2
) {
    init {
        require(trainBars >= 1) { "trainBars must be at least 1" }
        require(validationBars >= 1) { "validationBars must be at least 1" }
        require(stepBars >= 1) { "stepBars must be at least 1" }
        require(embargoBars >= 0) { "embargoBars must not be negative" }
        require(minFolds >= 1) { "minFolds must be at least 1" }
    }
}

data class WalkForwardFoldEvaluation(
    val foldIndex: Int,
    val train: SegmentEvaluation,
    val validation: SegmentEvaluation,
    val gates: List<Gate>,
    val score: CandidateScore?
) {
    init {
        require(foldIndex >= 0) { "foldIndex must not be negative" }
        require(train.role == SegmentRole.TRAIN) { "fold train must have TRAIN role" }
        require(validation.role == SegmentRole.VALIDATION) {
            "fold validation must have VALIDATION role"
        }
        require(gates.isNotEmpty()) { "fold must contain gates" }
    }

    val passed: Boolean get() = gates.all { it.passed }
}

data class WalkForwardEvaluation(
    val folds: List<WalkForwardFoldEvaluation>,
    val aggregateScore: CandidateScore?,
    val gates: List<Gate>
) {
    init {
        require(folds.isNotEmpty()) { "walk-forward evaluation requires at least one fold" }
        require(
            folds.map { it.foldIndex } == folds.indices.toList()
        ) { "fold indices must be contiguous and ordered" }

        if (folds.all { it.passed }) {
            require(aggregateScore != null) {
                "all passing walk-forward folds require an aggregate score"
            }
        }
    }

    val allPassed: Boolean get() = folds.all { it.passed }
    val foldCount: Int get() = folds.size
}
