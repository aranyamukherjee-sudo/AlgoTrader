package com.algotrader.discovery.scoring

import com.algotrader.backtest.ResearchCostModel
import com.algotrader.discovery.evaluation.SegmentEvaluation
import com.algotrader.discovery.split.SegmentRole

/**
 * Every threshold in one place. These are conservative, documented policy
 * choices, NOT statistically derived constants; change them per research
 * need. They are part of the reproducible run definition.
 */
data class DiscoveryPolicy(
    val minTrainTrades: Int = 30,
    val minValidationTrades: Int = 15,
    val minHoldoutTrades: Int = 10,
    /** Applied to train and validation when there are losing trades. */
    val minProfitFactor: Double = 1.2,
    /** Max drawdown as a percent of initial capital. */
    val maxDrawdownPercent: Double = 25.0,
    /** Min share of equal time buckets with positive P&L (train and validation). */
    val minConsistency: Double = 0.5,
    /** Validation average trade P&L must keep at least this share of the train average. */
    val minRetention: Double = 0.5,
    val consistencyPeriods: Int = 4,
    /** Hard cap on candidates per run (limits the multiple-testing problem). */
    val maxCandidates: Int = 500,
    /** Confidence is never reported above this: evidence is never certainty. */
    val maxConfidence: Double = 0.85,
    /** Generic deterministic execution-friction assumptions for robustness research. */
    val researchCostModel: ResearchCostModel = ResearchCostModel()
) {
    init {
        require(minTrainTrades >= 1 && minValidationTrades >= 1 && minHoldoutTrades >= 1) { "min trades must be at least 1" }
        require(minProfitFactor >= 1.0) { "minProfitFactor must be at least 1.0" }
        require(maxDrawdownPercent > 0.0) { "maxDrawdownPercent must be positive" }
        require(minConsistency in 0.0..1.0) { "minConsistency must be within 0..1" }
        require(minRetention in 0.0..1.0) { "minRetention must be within 0..1" }
        require(consistencyPeriods >= 1) { "consistencyPeriods must be at least 1" }
        require(maxCandidates >= 1) { "maxCandidates must be at least 1" }
        require(maxConfidence > 0.0 && maxConfidence <= 1.0) { "maxConfidence must be within (0, 1]" }
    }
}

/** One named pass/fail check with the numbers behind it, so rejections are explainable. */
data class Gate(
    val phase: SegmentRole,
    val name: String,
    val passed: Boolean,
    val detail: String
)

object DiscoveryGates {

    fun train(e: SegmentEvaluation, p: DiscoveryPolicy): List<Gate> =
        common(SegmentRole.TRAIN, e, p.minTrainTrades, p) + consistency(SegmentRole.TRAIN, e, p)

    fun validation(train: SegmentEvaluation, e: SegmentEvaluation, p: DiscoveryPolicy): List<Gate> {
        val retention = retention(train, e)
        val retentionGate = Gate(
            SegmentRole.VALIDATION, "retention",
            retention != null && retention >= p.minRetention,
            "validation avg trade ${fmt(e.averageTradePnl)} vs train ${fmt(train.averageTradePnl)} " +
                "(retained ${retention?.let { fmt(it) } ?: "n/a"}, need >= ${p.minRetention})"
        )
        return common(SegmentRole.VALIDATION, e, p.minValidationTrades, p) +
            consistency(SegmentRole.VALIDATION, e, p) + retentionGate
    }

    /** Final check: trade count, net result and drawdown only (the holdout sample is the smallest). */
    fun holdout(e: SegmentEvaluation, p: DiscoveryPolicy): List<Gate> = listOf(
        tradeCount(SegmentRole.HOLDOUT, e, p.minHoldoutTrades),
        netProfit(SegmentRole.HOLDOUT, e),
        drawdown(SegmentRole.HOLDOUT, e, p)
    )

    /** Validation average trade P&L as a share of train's; null unless train's average is positive. */
    fun retention(train: SegmentEvaluation, validation: SegmentEvaluation): Double? =
        if (train.averageTradePnl > 0.0) validation.averageTradePnl / train.averageTradePnl else null

    private fun common(role: SegmentRole, e: SegmentEvaluation, minTrades: Int, p: DiscoveryPolicy): List<Gate> = listOf(
        tradeCount(role, e, minTrades),
        netProfit(role, e),
        Gate(
            role, "profit_factor",
            e.profitFactor == null || e.profitFactor >= p.minProfitFactor,
            "profit factor ${e.profitFactor?.let { fmt(it) } ?: "n/a (no losing trades)"}, need >= ${p.minProfitFactor}"
        ),
        drawdown(role, e, p)
    )

    private fun tradeCount(role: SegmentRole, e: SegmentEvaluation, minTrades: Int) =
        Gate(role, "trade_count", e.trades >= minTrades, "${e.trades} trades, need >= $minTrades")

    private fun netProfit(role: SegmentRole, e: SegmentEvaluation) =
        Gate(
            role,
            "net_profit",
            e.costAdjustedNetProfit > 0.0,
            "cost-adjusted net profit ${fmt(e.costAdjustedNetProfit)}, need > 0"
        )

    private fun drawdown(role: SegmentRole, e: SegmentEvaluation, p: DiscoveryPolicy) =
        Gate(role, "drawdown", e.maxDrawdownPercent <= p.maxDrawdownPercent,
            "max drawdown ${fmt(e.maxDrawdownPercent)}%, need <= ${p.maxDrawdownPercent}%")

    private fun consistency(role: SegmentRole, e: SegmentEvaluation, p: DiscoveryPolicy) = listOf(
        Gate(role, "consistency", e.periodConsistency >= p.minConsistency,
            "${fmt(e.periodConsistency)} of periods profitable, need >= ${p.minConsistency}")
    )

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.4f", v)
}

/**
 * A transparent 0..1 ranking aid for candidates that passed validation: the
 * plain average of five components. It orders candidates; it is NOT a
 * probability and has no precision beyond the ordering.
 */
data class CandidateScore(val value: Double, val components: Map<String, Double>) {
    init {
        require(value in 0.0..1.0) { "score must be within 0..1" }
        require(components.values.all { it in 0.0..1.0 }) { "score components must be within 0..1" }
    }
}

object DiscoveryScoring {

    fun score(train: SegmentEvaluation, validation: SegmentEvaluation, p: DiscoveryPolicy): CandidateScore {
        val components = linkedMapOf(
            // enough trades to mean something: full marks at twice the minimum
            "trade_count" to clamp(validation.trades.toDouble() / (2.0 * p.minValidationTrades)),
            // 1.0 once profit factor reaches 2.0; no losing trades counts as full marks
            "profit_factor" to (validation.profitFactor?.let { clamp(it - 1.0) } ?: 1.0),
            "drawdown" to clamp(1.0 - validation.maxDrawdownPercent / p.maxDrawdownPercent),
            "consistency" to clamp(validation.periodConsistency),
            "retention" to clamp(DiscoveryGates.retention(train, validation) ?: 0.0)
        )
        return CandidateScore(components.values.average(), components)
    }

    private fun clamp(v: Double): Double = if (v.isNaN()) 0.0 else minOf(1.0, maxOf(0.0, v))
}
