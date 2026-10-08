package com.algotrader.intelligence.setup

import com.algotrader.intelligence.dna.EvidenceRef
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.evidence.EvidenceItem
import com.algotrader.intelligence.evidence.EvidenceKind
import com.algotrader.intelligence.evidence.EvidenceLedger
import com.algotrader.intelligence.evidence.EvidencePolarity
import com.algotrader.intelligence.evidence.EvidenceSample
import com.algotrader.intelligence.evidence.EvidenceSource
import com.algotrader.intelligence.evidence.EvidenceSourceType
import com.algotrader.intelligence.evidence.EvidenceUnit
import java.time.Instant

/**
 * Converts already-established setup facts into deterministic LIVE evidence.
 *
 * ASI-3.4.2 only:
 * - does not detect patterns or breakouts
 * - does not recalculate qualification
 * - does not calculate confidence
 * - does not create or mutate lifecycle events
 *
 * Every fact is constrained by [asOfIndex]. No pattern or setup event that
 * becomes known after that index is included.
 */
object SetupEvidenceExtractor {

    fun extract(
        setup: BreakoutSetup,
        strategy: StrategyRef,
        asOfIndex: Int,
        asOf: Instant
    ): EvidenceLedger {
        require(asOfIndex >= 0) {
            "asOfIndex must be non-negative"
        }
        require(asOfIndex >= setup.breakout.confirmedIndex) {
            "asOfIndex must be at or after breakout confirmation"
        }

        val source = EvidenceSource(
            type = EvidenceSourceType.LIVE_MARKET_DATA,
            reference = "${setup.breakout.id.value}@${asOfIndex}"
        )

        val items = mutableListOf<EvidenceItem>()

        patternEvidence(
            setup = setup,
            strategy = strategy,
            asOfIndex = asOfIndex,
            asOf = asOf,
            source = source
        ).forEach(items::add)

        qualificationEvidence(
            setup = setup,
            strategy = strategy,
            asOf = asOf,
            source = source
        ).forEach(items::add)

        lifecycleEvidence(
            setup = setup,
            strategy = strategy,
            asOfIndex = asOfIndex,
            asOf = asOf,
            source = source
        ).forEach(items::add)

        return EvidenceLedger(
            items = items.sortedBy { it.id.id }
        )
    }

    private fun patternEvidence(
        setup: BreakoutSetup,
        strategy: StrategyRef,
        asOfIndex: Int,
        asOf: Instant,
        source: EvidenceSource
    ): List<EvidenceItem> =
        setup.breakout.level.origins
            .asSequence()
            .filterIsInstance<LevelOrigin.PatternBoundary>()
            .filter { it.pattern.confirmedIndex <= asOfIndex }
            .distinctBy {
                "${it.pattern.startIndex}:${it.pattern.endIndex}:${it.pattern.type.name}:${it.role.name}"
            }
            .sortedWith(
                compareBy(
                    { it.pattern.confirmedIndex },
                    { it.pattern.startIndex },
                    { it.pattern.endIndex },
                    { it.pattern.type.ordinal },
                    { it.role.ordinal }
                )
            )
            .map { origin ->
                val pattern = origin.pattern
                EvidenceItem(
                    id = EvidenceRef(
                        "${setup.breakout.id.value}#CURRENT_PATTERN_MATCH#" +
                            "${pattern.startIndex}:${pattern.endIndex}:" +
                            "${pattern.type.name}:${origin.role.name}"
                    ),
                    strategy = strategy,
                    kind = EvidenceKind.CURRENT_PATTERN_MATCH,
                    sample = EvidenceSample.LIVE,
                    summary =
                        "Pattern ${pattern.type.name} confirmed at " +
                            "${pattern.confirmedIndex}; boundary ${origin.role.name}",
                    source = source,
                    recordedAt = asOf,
                    polarity = EvidencePolarity.SUPPORTING,
                    value = null,
                    unit = EvidenceUnit.NONE
                )
            }
            .toList()

    private fun qualificationEvidence(
        setup: BreakoutSetup,
        strategy: StrategyRef,
        asOf: Instant,
        source: EvidenceSource
    ): List<EvidenceItem> =
        setup.qualification.checks
            .filter {
                (
                    it.criterion == QualificationCriterion.TREND_ALIGNMENT ||
                        it.criterion == QualificationCriterion.RANGE_EXPANSION
                    ) &&
                    it.status != CheckStatus.UNAVAILABLE
            }
            .map { check ->
                val kind = when (check.criterion) {
                    QualificationCriterion.TREND_ALIGNMENT ->
                        EvidenceKind.TREND_ALIGNMENT

                    QualificationCriterion.RANGE_EXPANSION ->
                        EvidenceKind.VOLATILITY_CONDITION

                    else -> error("unsupported qualification criterion")
                }

                val polarity = when (check.status) {
                    CheckStatus.PASS -> EvidencePolarity.SUPPORTING
                    CheckStatus.FAIL -> EvidencePolarity.CONTRADICTING
                    CheckStatus.UNAVAILABLE -> EvidencePolarity.SUPPORTING
                }

                val summary = when (check.criterion) {
                    QualificationCriterion.TREND_ALIGNMENT ->
                        "Breakout trend alignment is ${check.status.name}" +
                            (check.trendState?.let { " (${it.name})" } ?: "")

                    QualificationCriterion.RANGE_EXPANSION ->
                        "Breakout range expansion is ${check.status.name}"

                    else -> error("unsupported qualification criterion")
                }

                EvidenceItem(
                    id = EvidenceRef(
                        "${setup.breakout.id.value}#${kind.name}"
                    ),
                    strategy = strategy,
                    kind = kind,
                    sample = EvidenceSample.LIVE,
                    summary = summary,
                    source = source,
                    recordedAt = asOf,
                    polarity = polarity,
                    value = check.observed,
                    unit = if (check.criterion ==
                        QualificationCriterion.RANGE_EXPANSION
                    ) {
                        EvidenceUnit.RATIO
                    } else {
                        EvidenceUnit.NONE
                    }
                )
            }

    private fun lifecycleEvidence(
        setup: BreakoutSetup,
        strategy: StrategyRef,
        asOfIndex: Int,
        asOf: Instant,
        source: EvidenceSource
    ): List<EvidenceItem> =
        setup.events
            .asSequence()
            .filter { it.index <= asOfIndex }
            .filter { it.stage == SetupStage.RETEST_HELD }
            .sortedWith(compareBy({ it.index }, { it.stage.ordinal }))
            .map { event ->
                EvidenceItem(
                    id = EvidenceRef(
                        "${setup.breakout.id.value}#" +
                            "BREAKOUT_RETEST_CONFIRMATION#" +
                            "${event.stage.name}:${event.index}"
                    ),
                    strategy = strategy,
                    kind = EvidenceKind.BREAKOUT_RETEST_CONFIRMATION,
                    sample = EvidenceSample.LIVE,
                    summary = "Setup retest stage ${event.stage.name} at index ${event.index}",
                    source = source,
                    recordedAt = asOf,
                    polarity = EvidencePolarity.SUPPORTING,
                    value = null,
                    unit = EvidenceUnit.NONE
                )
            }
            .toList()
}
