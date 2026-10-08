package com.algotrader.intelligence.setup.context

import com.algotrader.intelligence.setup.SetupEvent

import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.setup.BreakoutDirection
import com.algotrader.intelligence.setup.BreakoutSetup
import com.algotrader.intelligence.setup.LevelOrigin
import com.algotrader.intelligence.setup.SetupStage
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.ZoneKind
import kotlin.math.abs

/**
 * ASI-3.5 deterministic context extraction.
 *
 * This component consumes already-created ASI-3.1/3.2/3.3 setup facts.
 * It does not redetect structure, patterns, breakouts, or lifecycle events.
 *
 * The supplied structure/pattern analyses MUST represent the same as-of prefix:
 * candleCount == asOfIndex + 1. This is deliberately strict so a full-series
 * structure state cannot leak future information into a historical context.
 */
object SetupContextExtractor {

    fun extract(
        setup: BreakoutSetup,
        structure: StructureAnalysis,
        patterns: PatternAnalysis,
        asOfIndex: Int,
        policy: SetupContextPolicy = SetupContextPolicy()
    ): SetupContext {
        require(asOfIndex >= setup.breakout.confirmedIndex) {
            "asOfIndex must be at or after breakout confirmation"
        }
        require(structure.candleCount == asOfIndex + 1) {
            "structure analysis must represent the exact as-of prefix"
        }
        require(patterns.candleCount == asOfIndex + 1) {
            "pattern analysis must represent the exact as-of prefix"
        }

        val factors = mutableListOf<SetupContextFactor>()

        if (structure.status == AnalysisStatus.OK) {
            structureFactor(setup.breakout.direction, structure.state)?.let {
                factors += it
            }
        }

        if (patterns.status == AnalysisStatus.OK) {
            patternFactors(setup.breakout.direction, patterns, asOfIndex)
                .forEach { factors += it }
        }

        val levels = if (structure.status == AnalysisStatus.OK) {
            extractRelevantLevels(setup, structure, asOfIndex, policy)
        } else {
            emptyList()
        }

        levelFactors(setup, levels).forEach { factors += it }

        lifecycleFactor(setup, asOfIndex)?.let { factors += it }

        return SetupContext(
            breakoutId = setup.breakout.id,
            direction = setup.breakout.direction,
            asOfIndex = asOfIndex,
            marketStructure =
                if (structure.status == AnalysisStatus.OK) {
                    structure.state
                } else {
                    MarketStructureState.UNDEFINED
                },
            lifecycleStage = latestStage(setup, asOfIndex),
            relevantLevels = levels,
            factors = factors.sortedWith(factorComparator)
        )
    }

    private fun structureFactor(
        direction: BreakoutDirection,
        state: MarketStructureState
    ): SetupContextFactor? {
        val polarity = when (direction) {
            BreakoutDirection.UP -> when (state) {
                MarketStructureState.UPTREND -> SetupContextPolarity.SUPPORTING
                MarketStructureState.DOWNTREND -> SetupContextPolarity.CONFLICTING
                MarketStructureState.RANGE,
                MarketStructureState.UNDEFINED -> SetupContextPolarity.NEUTRAL
            }

            BreakoutDirection.DOWN -> when (state) {
                MarketStructureState.DOWNTREND -> SetupContextPolarity.SUPPORTING
                MarketStructureState.UPTREND -> SetupContextPolarity.CONFLICTING
                MarketStructureState.RANGE,
                MarketStructureState.UNDEFINED -> SetupContextPolarity.NEUTRAL
            }
        }

        if (polarity == SetupContextPolarity.NEUTRAL) return null

        return SetupContextFactor(
            id = "STRUCTURE#${state.name}",
            kind = SetupContextFactorKind.MARKET_STRUCTURE,
            polarity = polarity,
            knownAtIndex = 0,
            summary = "Market structure is ${state.name}"
        )
    }

    private fun patternFactors(
        direction: BreakoutDirection,
        patterns: PatternAnalysis,
        asOfIndex: Int
    ): List<SetupContextFactor> =
        patterns.patterns
            .asSequence()
            .filter { it.confirmedIndex <= asOfIndex }
            .distinctBy {
                "${it.startIndex}:${it.endIndex}:${it.type.name}"
            }
            .sortedWith(
                compareBy(
                    { it.confirmedIndex },
                    { it.startIndex },
                    { it.endIndex },
                    { it.type.ordinal }
                )
            )
            .mapNotNull { pattern ->
                val polarity = patternPolarity(direction, pattern.type)

                if (polarity == SetupContextPolarity.NEUTRAL) {
                    null
                } else {
                    SetupContextFactor(
                        id =
                            "PATTERN#${pattern.startIndex}:" +
                                "${pattern.endIndex}:${pattern.type.name}",
                        kind = SetupContextFactorKind.PATTERN_CONTEXT,
                        polarity = polarity,
                        knownAtIndex = pattern.confirmedIndex,
                        summary =
                            "Pattern ${pattern.type.name} " +
                                "supports/conflicts with breakout direction",
                        sourceIds = listOf(
                            "${pattern.startIndex}:${pattern.endIndex}:${pattern.type.name}"
                        )
                    )
                }
            }
            .toList()

    private fun patternPolarity(
        direction: BreakoutDirection,
        type: PatternType
    ): SetupContextPolarity {
        val patternBias = when (type) {
            PatternType.DOUBLE_BOTTOM,
            PatternType.W_BOTTOM,
            PatternType.ASCENDING_TRIANGLE,
            PatternType.BULL_FLAG -> BreakoutDirection.UP

            PatternType.DOUBLE_TOP,
            PatternType.M_TOP,
            PatternType.DESCENDING_TRIANGLE,
            PatternType.BEAR_FLAG -> BreakoutDirection.DOWN

            PatternType.RECTANGLE,
            PatternType.SYMMETRICAL_TRIANGLE -> null
        }

        return when (patternBias) {
            direction -> SetupContextPolarity.SUPPORTING
            null -> SetupContextPolarity.NEUTRAL
            else -> SetupContextPolarity.CONFLICTING
        }
    }

    private fun extractRelevantLevels(
        setup: BreakoutSetup,
        structure: StructureAnalysis,
        asOfIndex: Int,
        policy: SetupContextPolicy
    ): List<SetupContextLevel> {
        val candidates = mutableListOf<RawLevel>()

        structure.pivots
            .asSequence()
            .filter { it.pivot.confirmedIndex <= asOfIndex }
            .forEach { classified ->
                candidates += RawLevel(
                    side =
                        if (classified.pivot.type == PivotType.HIGH) {
                            ZoneKind.RESISTANCE
                        } else {
                            ZoneKind.SUPPORT
                        },
                    price = classified.pivot.price,
                    knownAtIndex = classified.pivot.confirmedIndex,
                    sourceId =
                        "PIVOT#${classified.pivot.index}:" +
                            "${classified.pivot.type.name}"
                )
            }

        structure.zones
            .asSequence()
            .filter { it.lastConfirmedIndex <= asOfIndex }
            .forEach { zone ->
                candidates += RawLevel(
                    side = zone.kind,
                    price =
                        if (zone.kind == ZoneKind.RESISTANCE) {
                            zone.high
                        } else {
                            zone.low
                        },
                    knownAtIndex = zone.lastConfirmedIndex,
                    sourceId =
                        "ZONE#${zone.kind.name}:" +
                            "${zone.low}:${zone.high}:${zone.lastIndex}"
                )
            }

        val breakoutPrice = setup.breakout.level.price

        // The breakout level is already represented by the setup itself.
        // It is not independent confluence and must not be counted again.
        val independentCandidates = candidates.filterNot {
            it.side == setup.breakout.level.side &&
                relativeDistance(it.price, breakoutPrice) <=
                    policy.levelDedupFraction
        }

        val clusters = cluster(independentCandidates, policy.levelDedupFraction)

        val supporting = clusters
            .filter { isSupportingLevel(setup.breakout.direction, it.side, it.price, breakoutPrice) }
            .minWithOrNull(levelDistanceComparator(breakoutPrice))

        val conflicting = clusters
            .filter { isConflictingLevel(setup.breakout.direction, it.side, it.price, breakoutPrice) }
            .minWithOrNull(levelDistanceComparator(breakoutPrice))

        return listOfNotNull(supporting, conflicting)
            .filter {
                relativeDistance(it.price, breakoutPrice) <=
                    policy.levelProximityFraction
            }
            .sortedWith(levelComparator)
    }

    private fun isSupportingLevel(
        direction: BreakoutDirection,
        side: ZoneKind,
        price: Double,
        breakoutPrice: Double
    ): Boolean =
        when (direction) {
            BreakoutDirection.UP ->
                side == ZoneKind.SUPPORT && price < breakoutPrice
            BreakoutDirection.DOWN ->
                side == ZoneKind.RESISTANCE && price > breakoutPrice
        }

    private fun isConflictingLevel(
        direction: BreakoutDirection,
        side: ZoneKind,
        price: Double,
        breakoutPrice: Double
    ): Boolean =
        when (direction) {
            BreakoutDirection.UP ->
                side == ZoneKind.RESISTANCE && price > breakoutPrice
            BreakoutDirection.DOWN ->
                side == ZoneKind.SUPPORT && price < breakoutPrice
        }

    private fun levelFactors(
        setup: BreakoutSetup,
        levels: List<SetupContextLevel>
    ): List<SetupContextFactor> {
        val breakoutPrice = setup.breakout.level.price

        return levels.map { level ->
            val polarity = when {
                isSupportingLevel(
                    setup.breakout.direction,
                    level.side,
                    level.price,
                    breakoutPrice
                ) -> SetupContextPolarity.SUPPORTING

                isConflictingLevel(
                    setup.breakout.direction,
                    level.side,
                    level.price,
                    breakoutPrice
                ) -> SetupContextPolarity.CONFLICTING

                else -> SetupContextPolarity.NEUTRAL
            }

            SetupContextFactor(
                id =
                    "LEVEL#${level.side.name}:${level.price}:${polarity.name}",
                kind = SetupContextFactorKind.LEVEL_CONTEXT,
                polarity = polarity,
                knownAtIndex = level.knownAtIndex,
                summary =
                    "${level.side.name} level ${level.price} is " +
                        "${polarity.name.lowercase()} context",
                sourceIds = level.sourceIds
            )
        }
    }

    private fun lifecycleFactor(
        setup: BreakoutSetup,
        asOfIndex: Int
    ): SetupContextFactor? {
        val events = setup.events
            .filter { it.index <= asOfIndex }

        val event = events.maxWithOrNull(
            compareBy<SetupEvent>({ it.index }, { it.stage.ordinal })
        ) ?: return null

        val polarity = when (event.stage) {
            SetupStage.RETEST_HELD,
            SetupStage.CONTINUED ->
                SetupContextPolarity.SUPPORTING

            SetupStage.FAILED,
            SetupStage.EXPIRED ->
                SetupContextPolarity.CONFLICTING

            SetupStage.BROKEN,
            SetupStage.RETEST_TOUCHED ->
                SetupContextPolarity.NEUTRAL
        }

        if (polarity == SetupContextPolarity.NEUTRAL) return null

        return SetupContextFactor(
            id = "LIFECYCLE#${event.stage.name}:${event.index}",
            kind = SetupContextFactorKind.LIFECYCLE_CONTEXT,
            polarity = polarity,
            knownAtIndex = event.index,
            summary =
                "Latest setup lifecycle stage is ${event.stage.name}"
        )
    }

    private fun latestStage(
        setup: BreakoutSetup,
        asOfIndex: Int
    ): SetupStage =
        setup.events
            .filter { it.index <= asOfIndex }
            .maxWithOrNull(
                compareBy<SetupEvent>({ it.index }, { it.stage.ordinal })
            )
            ?.stage
            ?: SetupStage.BROKEN

    private data class RawLevel(
        val side: ZoneKind,
        val price: Double,
        val knownAtIndex: Int,
        val sourceId: String
    )

    private data class LevelCluster(
        val side: ZoneKind,
        val price: Double,
        val knownAtIndex: Int,
        val sourceIds: List<String>
    )

    private fun cluster(
        candidates: List<RawLevel>,
        tolerance: Double
    ): List<SetupContextLevel> {
        val result = mutableListOf<LevelCluster>()

        candidates
            .filter { it.price.isFinite() }
            .sortedWith(
                compareBy<RawLevel>(
                    { it.side.ordinal },
                    { it.price },
                    { it.knownAtIndex },
                    { it.sourceId }
                )
            )
            .forEach { candidate ->
                val existing = result.lastOrNull()

                if (
                    existing != null &&
                    existing.side == candidate.side &&
                    relativeDistance(candidate.price, existing.price) <= tolerance
                ) {
                    val mergedSources =
                        (existing.sourceIds + candidate.sourceId)
                            .distinct()
                            .sorted()

                    val merged = existing.copy(
                        knownAtIndex =
                            minOf(existing.knownAtIndex, candidate.knownAtIndex),
                        sourceIds = mergedSources
                    )

                    result[result.lastIndex] = merged
                } else {
                    result += LevelCluster(
                        side = candidate.side,
                        price = candidate.price,
                        knownAtIndex = candidate.knownAtIndex,
                        sourceIds = listOf(candidate.sourceId)
                    )
                }
            }

        return result.map {
            SetupContextLevel(
                side = it.side,
                price = it.price,
                knownAtIndex = it.knownAtIndex,
                sourceIds = it.sourceIds
            )
        }
    }

    private fun relativeDistance(a: Double, b: Double): Double =
        abs(a - b) / maxOf(abs(a), abs(b), 1e-12)

    private fun levelDistanceComparator(
        breakoutPrice: Double
    ): Comparator<SetupContextLevel> =
        compareBy(
            { relativeDistance(it.price, breakoutPrice) },
            { it.side.ordinal },
            { it.price },
            { it.knownAtIndex },
            { it.sourceIds.joinToString("|") }
        )

    private val levelComparator: Comparator<SetupContextLevel> =
        compareBy(
            { it.side.ordinal },
            { it.price },
            { it.knownAtIndex },
            { it.sourceIds.joinToString("|") }
        )

    private val factorComparator: Comparator<SetupContextFactor> =
        compareBy { it.id }
}

/**
 * Explicit defaults for ASI-3.5 level relevance.
 *
 * Fractions are relative price distances, not absolute points, so the same
 * deterministic policy can be applied across instruments.
 */
data class SetupContextPolicy(
    val levelProximityFraction: Double = 0.005,
    val levelDedupFraction: Double = 0.001
) {
    init {
        require(
            levelProximityFraction.isFinite() &&
                levelProximityFraction >= 0.0 &&
                levelProximityFraction < 1.0
        ) { "levelProximityFraction must be in [0, 1)" }

        require(
            levelDedupFraction.isFinite() &&
                levelDedupFraction >= 0.0 &&
                levelDedupFraction < 1.0
        ) { "levelDedupFraction must be in [0, 1)" }
    }
}
