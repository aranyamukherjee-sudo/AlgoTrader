package com.algotrader.intelligence.setup

import com.algotrader.domain.Candle
import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.pattern.PatternType
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.PriceActionStructure
import com.algotrader.intelligence.structure.PriceZone
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.SupportResistance
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind

/**
 * Extracts deterministic breakout levels from confirmed structure and pattern information.
 *
 * This is ASI-3.3A.2 only: level extraction. It does not detect breakouts,
 * qualify them, or create setup/signal lifecycle events.
 */
object LevelExtractor {

    fun extract(
        candles: List<Candle>,
        structure: StructureAnalysis,
        patterns: PatternAnalysis,
        config: SetupConfig = SetupConfig(),
        asOfIndex: Int
    ): List<BreakoutLevel> {
        require(asOfIndex >= 0) { "asOfIndex must be non-negative" }
        require(asOfIndex < candles.size) {
            "asOfIndex must be within candle series"
        }
        require(structure.candleCount == candles.size) {
            "structure analysis candleCount must match candles"
        }
        require(patterns.candleCount == candles.size) {
            "pattern analysis candleCount must match candles"
        }

        if (structure.status != AnalysisStatus.OK ||
            patterns.status != AnalysisStatus.OK
        ) {
            return emptyList()
        }

        val levels = linkedMapOf<LevelKey, MutableList<LevelOrigin>>()

        if (LevelSource.PIVOT in config.levelSources) {
            val pivots = structure.pivots
                .asSequence()
                .map { it.pivot }
                .filter { it.confirmedIndex <= asOfIndex }

            pivots.forEach { pivot ->
                addOrigin(
                    levels,
                    LevelKey(pivotSide(pivot), pivot.price),
                    LevelOrigin.Pivot(pivot)
                )
            }
        }

        if (LevelSource.ZONE in config.levelSources) {
            val pivots = structure.pivots
                .asSequence()
                .map { it.pivot }
                .filter { it.confirmedIndex <= asOfIndex }
                .toList()

            SupportResistance
                .zones(pivots, config.pattern.structure)
                .filter { it.lastConfirmedIndex <= asOfIndex }
                .forEach { zone ->
                    addOrigin(
                        levels,
                        LevelKey(zone.kind, zonePrice(zone)),
                        LevelOrigin.Zone(zone)
                    )
                }
        }

        if (LevelSource.PATTERN_BOUNDARY in config.levelSources) {
            patterns.patterns
                .asSequence()
                .filter { it.confirmedIndex <= asOfIndex }
                .flatMap { pattern ->
                    patternBoundaries(pattern).asSequence()
                }
                .forEach { boundary ->
                    addOrigin(
                        levels,
                        LevelKey(boundary.side, boundary.price),
                        LevelOrigin.PatternBoundary(
                            boundary.pattern,
                            boundary.role
                        )
                    )
                }
        }

        return levels
            .map { (key, origins) ->
                BreakoutLevel(
                    side = key.side,
                    price = key.price,
                    origins = origins.sortedWith(originComparator)
                )
            }
            .sortedWith(levelComparator)
    }

    private data class LevelKey(
        val side: ZoneKind,
        val price: Double
    )

    private data class PatternBoundary(
        val pattern: DetectedPattern,
        val role: PatternBoundaryRole,
        val side: ZoneKind,
        val price: Double
    )

    private fun addOrigin(
        levels: MutableMap<LevelKey, MutableList<LevelOrigin>>,
        key: LevelKey,
        origin: LevelOrigin
    ) {
        levels.getOrPut(key) { mutableListOf() }
            .also { origins ->
                if (origin !in origins) {
                    origins += origin
                }
            }
    }

    private fun pivotSide(pivot: SwingPivot): ZoneKind =
        when (pivot.type) {
            com.algotrader.intelligence.structure.PivotType.HIGH ->
                ZoneKind.RESISTANCE

            com.algotrader.intelligence.structure.PivotType.LOW ->
                ZoneKind.SUPPORT
        }

    /**
     * Breakout price for a zone is its outer edge:
     * resistance -> high, support -> low.
     */
    private fun zonePrice(zone: PriceZone): Double =
        when (zone.kind) {
            ZoneKind.RESISTANCE -> zone.high
            ZoneKind.SUPPORT -> zone.low
        }

    private fun patternBoundaries(
        pattern: DetectedPattern
    ): List<PatternBoundary> =
        when (pattern.type) {
            PatternType.DOUBLE_TOP,
            PatternType.M_TOP ->
                listOfNotNull(
                    pattern.neckline?.let { neckline ->
                        PatternBoundary(
                            pattern = pattern,
                            role = PatternBoundaryRole.NECKLINE,
                            side = ZoneKind.SUPPORT,
                            price = neckline
                        )
                    }
                )

            PatternType.DOUBLE_BOTTOM,
            PatternType.W_BOTTOM ->
                listOfNotNull(
                    pattern.neckline?.let { neckline ->
                        PatternBoundary(
                            pattern = pattern,
                            role = PatternBoundaryRole.NECKLINE,
                            side = ZoneKind.RESISTANCE,
                            price = neckline
                        )
                    }
                )

            PatternType.RECTANGLE ->
                listOf(
                    PatternBoundary(
                        pattern = pattern,
                        role = PatternBoundaryRole.RANGE_CEILING,
                        side = ZoneKind.RESISTANCE,
                        price = pattern.high
                    ),
                    PatternBoundary(
                        pattern = pattern,
                        role = PatternBoundaryRole.RANGE_FLOOR,
                        side = ZoneKind.SUPPORT,
                        price = pattern.low
                    )
                )

            PatternType.ASCENDING_TRIANGLE ->
                listOf(
                    PatternBoundary(
                        pattern = pattern,
                        role = PatternBoundaryRole.TRIANGLE_FLAT_TOP,
                        side = ZoneKind.RESISTANCE,
                        price = pattern.high
                    )
                )

            PatternType.DESCENDING_TRIANGLE ->
                listOf(
                    PatternBoundary(
                        pattern = pattern,
                        role = PatternBoundaryRole.TRIANGLE_FLAT_BOTTOM,
                        side = ZoneKind.SUPPORT,
                        price = pattern.low
                    )
                )

            /*
             * ASI-3.3A intentionally does not derive sloped geometry.
             * Symmetrical triangles and flags therefore contribute no
             * pattern-boundary level at this stage.
             */
            PatternType.SYMMETRICAL_TRIANGLE,
            PatternType.BULL_FLAG,
            PatternType.BEAR_FLAG ->
                emptyList()
        }

    private val levelComparator: Comparator<BreakoutLevel> =
        Comparator { a, b ->
            val side = a.side.ordinal.compareTo(b.side.ordinal)
            if (side != 0) {
                side
            } else {
                a.price.compareTo(b.price)
            }
        }

    /**
     * Stable origin ordering:
     * source -> known-at index -> source-specific deterministic identity.
     */
    private val originComparator: Comparator<LevelOrigin> =
        Comparator { a, b ->
            val source = a.source.ordinal.compareTo(b.source.ordinal)
            if (source != 0) {
                return@Comparator source
            }

            val known = a.knownAtIndex.compareTo(b.knownAtIndex)
            if (known != 0) {
                return@Comparator known
            }

            when {
                a is LevelOrigin.Pivot && b is LevelOrigin.Pivot -> {
                    val index = a.pivot.index.compareTo(b.pivot.index)
                    if (index != 0) {
                        index
                    } else {
                        val type = a.pivot.type.ordinal.compareTo(b.pivot.type.ordinal)
                        if (type != 0) {
                            type
                        } else {
                            a.pivot.price.compareTo(b.pivot.price)
                        }
                    }
                }

                a is LevelOrigin.Zone && b is LevelOrigin.Zone -> {
                    val kind = a.zone.kind.ordinal.compareTo(b.zone.kind.ordinal)
                    if (kind != 0) {
                        kind
                    } else {
                        val low = a.zone.low.compareTo(b.zone.low)
                        if (low != 0) {
                            low
                        } else {
                            val high = a.zone.high.compareTo(b.zone.high)
                            if (high != 0) {
                                high
                            } else {
                                a.zone.lastIndex.compareTo(b.zone.lastIndex)
                            }
                        }
                    }
                }

                a is LevelOrigin.PatternBoundary &&
                    b is LevelOrigin.PatternBoundary -> {
                    val start = a.pattern.startIndex.compareTo(b.pattern.startIndex)
                    if (start != 0) {
                        start
                    } else {
                        val end = a.pattern.endIndex.compareTo(b.pattern.endIndex)
                        if (end != 0) {
                            end
                        } else {
                            val type = a.pattern.type.ordinal.compareTo(b.pattern.type.ordinal)
                            if (type != 0) {
                                type
                            } else {
                                a.role.ordinal.compareTo(b.role.ordinal)
                            }
                        }
                    }
                }

                else -> {
                    /*
                     * Different LevelOrigin implementations are not expected
                     * beyond the sealed variants above. Keep ordering total
                     * and deterministic if another variant is added later.
                     */
                    a.toString().compareTo(b.toString())
                }
            }
        }
}
