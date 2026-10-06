package com.algotrader.intelligence.evidence

import com.algotrader.intelligence.dna.EvidenceRef
import com.algotrader.intelligence.dna.StrategyRef
import java.time.Instant

/**
 * RESEARCH: "this strategy has historical evidence" (attached to a strategy
 * version). LIVE: "the market is currently forming this setup" (attached to an
 * opportunity). Keeping them apart is enforced by the models that hold them.
 */
enum class EvidenceScope { RESEARCH, LIVE }

enum class EvidenceKind(val scope: EvidenceScope) {
    HISTORICAL_EDGE(EvidenceScope.RESEARCH),
    OCCURRENCE_COUNT(EvidenceScope.RESEARCH),
    PROFIT_FACTOR(EvidenceScope.RESEARCH),
    DRAWDOWN(EvidenceScope.RESEARCH),
    CONSISTENCY(EvidenceScope.RESEARCH),
    OUT_OF_SAMPLE_PERFORMANCE(EvidenceScope.RESEARCH),
    CROSS_CONTRACT_PERFORMANCE(EvidenceScope.RESEARCH),
    COST_SENSITIVITY(EvidenceScope.RESEARCH),
    CUSTOM_RESEARCH(EvidenceScope.RESEARCH),

    CURRENT_PATTERN_MATCH(EvidenceScope.LIVE),
    TREND_ALIGNMENT(EvidenceScope.LIVE),
    VOLATILITY_CONDITION(EvidenceScope.LIVE),
    BREAKOUT_RETEST_CONFIRMATION(EvidenceScope.LIVE),
    CUSTOM_LIVE(EvidenceScope.LIVE)
}

/** Which data the evidence was measured on, so overfitting can be told apart later. */
enum class EvidenceSample { IN_SAMPLE, VALIDATION, OUT_OF_SAMPLE, FULL_PERIOD, LIVE }

enum class EvidencePolarity { SUPPORTING, CONTRADICTING }

enum class EvidenceUnit { NONE, COUNT, PERCENT, RATIO, CURRENCY_INR }

enum class EvidenceSourceType { BACKTEST_RUN, VALIDATION_RUN, LIVE_MARKET_DATA, USER_NOTE }

/** Where a piece of evidence came from. A blank reference is rejected: no unsourced evidence. */
data class EvidenceSource(val type: EvidenceSourceType, val reference: String) {
    init {
        require(reference.isNotBlank()) { "evidence must reference its source (e.g. a backtest job id)" }
    }
}

/**
 * One recorded reason ALTRIXA considers a strategy version or an opportunity
 * promising (or not). It is bound to an exact [strategy] version and must come
 * from a real [source]; it is never invented.
 */
data class EvidenceItem(
    val id: EvidenceRef,
    val strategy: StrategyRef,
    val kind: EvidenceKind,
    val sample: EvidenceSample,
    val summary: String,
    val source: EvidenceSource,
    val recordedAt: Instant,
    val polarity: EvidencePolarity = EvidencePolarity.SUPPORTING,
    val value: Double? = null,
    val unit: EvidenceUnit = EvidenceUnit.NONE,
    val observations: Int? = null
) {
    init {
        require(summary.isNotBlank()) { "evidence summary must not be blank" }
        require(value == null || value.isFinite()) { "evidence value must be finite" }
        require(observations == null || observations >= 0) { "observations must not be negative" }
        if (kind.scope == EvidenceScope.LIVE) {
            require(sample == EvidenceSample.LIVE) { "live evidence must use sample LIVE" }
        } else {
            require(sample != EvidenceSample.LIVE) { "research evidence cannot use sample LIVE" }
        }
    }

    val scope: EvidenceScope get() = kind.scope
}

/** Append-only collection of [EvidenceItem]s. */
data class EvidenceLedger(val items: List<EvidenceItem> = emptyList()) {
    init {
        require(items.map { it.id }.toSet().size == items.size) { "evidence ids must be unique" }
    }

    val isEmpty: Boolean get() = items.isEmpty()

    fun add(item: EvidenceItem): EvidenceLedger = copy(items = items + item)

    fun supporting(): List<EvidenceItem> = items.filter { it.polarity == EvidencePolarity.SUPPORTING }
    fun contradicting(): List<EvidenceItem> = items.filter { it.polarity == EvidencePolarity.CONTRADICTING }
    fun ofKind(kind: EvidenceKind): List<EvidenceItem> = items.filter { it.kind == kind }
    fun hasSample(sample: EvidenceSample): Boolean = items.any { it.sample == sample }
    fun refs(): List<EvidenceRef> = items.map { it.id }
}
