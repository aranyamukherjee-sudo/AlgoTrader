package com.algotrader.intelligence.setup

import com.algotrader.intelligence.pattern.DetectedPattern
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PriceZone
import com.algotrader.intelligence.structure.SwingPivot
import com.algotrader.intelligence.structure.ZoneKind
import java.time.Instant

enum class BreakoutDirection { UP, DOWN }

enum class LevelSource { PIVOT, ZONE, PATTERN_BOUNDARY }

enum class PatternBoundaryRole {
    NECKLINE,
    RANGE_CEILING,
    RANGE_FLOOR,
    TRIANGLE_FLAT_TOP,
    TRIANGLE_FLAT_BOTTOM
}

private val levelOriginComparator: Comparator<LevelOrigin> =
    Comparator { a, b ->
        fun sourceRank(source: LevelSource): Int = source.ordinal

        fun compareDouble(x: Double, y: Double): Int =
            x.compareTo(y)

        val sourceCompare = sourceRank(a.source).compareTo(sourceRank(b.source))
        if (sourceCompare != 0) return@Comparator sourceCompare

        val knownCompare = a.knownAtIndex.compareTo(b.knownAtIndex)
        if (knownCompare != 0) return@Comparator knownCompare

        when {
            a is LevelOrigin.Pivot && b is LevelOrigin.Pivot -> {
                val indexCompare = a.pivot.index.compareTo(b.pivot.index)
                if (indexCompare != 0) return@Comparator indexCompare

                val typeCompare = a.pivot.type.ordinal.compareTo(b.pivot.type.ordinal)
                if (typeCompare != 0) return@Comparator typeCompare

                compareDouble(a.pivot.price, b.pivot.price)
            }

            a is LevelOrigin.Zone && b is LevelOrigin.Zone -> {
                val kindCompare = a.zone.kind.ordinal.compareTo(b.zone.kind.ordinal)
                if (kindCompare != 0) return@Comparator kindCompare

                val lowCompare = compareDouble(a.zone.low, b.zone.low)
                if (lowCompare != 0) return@Comparator lowCompare

                val highCompare = compareDouble(a.zone.high, b.zone.high)
                if (highCompare != 0) return@Comparator highCompare

                a.zone.lastIndex.compareTo(b.zone.lastIndex)
            }

            a is LevelOrigin.PatternBoundary &&
                b is LevelOrigin.PatternBoundary -> {
                val startCompare =
                    a.pattern.startIndex.compareTo(b.pattern.startIndex)
                if (startCompare != 0) return@Comparator startCompare

                val typeCompare =
                    a.pattern.type.ordinal.compareTo(b.pattern.type.ordinal)
                if (typeCompare != 0) return@Comparator typeCompare

                val roleCompare =
                    a.role.ordinal.compareTo(b.role.ordinal)
                if (roleCompare != 0) return@Comparator roleCompare

                a.pattern.endIndex.compareTo(b.pattern.endIndex)
            }

            else -> {
                // Source rank already provides the deterministic cross-source order.
                0
            }
        }
    }

sealed interface LevelOrigin {
    val source: LevelSource
    val knownAtIndex: Int

    data class Pivot(val pivot: SwingPivot) : LevelOrigin {
        override val source: LevelSource = LevelSource.PIVOT
        override val knownAtIndex: Int = pivot.confirmedIndex
    }

    data class Zone(val zone: PriceZone) : LevelOrigin {
        override val source: LevelSource = LevelSource.ZONE
        override val knownAtIndex: Int = zone.lastConfirmedIndex
    }

    data class PatternBoundary(
        val pattern: DetectedPattern,
        val role: PatternBoundaryRole
    ) : LevelOrigin {
        override val source: LevelSource = LevelSource.PATTERN_BOUNDARY
        override val knownAtIndex: Int = pattern.confirmedIndex
    }
}

data class BreakoutLevel(
    val side: ZoneKind,
    val price: Double,
    val origins: List<LevelOrigin>
) {
    val direction: BreakoutDirection
        get() = when (side) {
            ZoneKind.RESISTANCE -> BreakoutDirection.UP
            ZoneKind.SUPPORT -> BreakoutDirection.DOWN
        }

    val knownAtIndex: Int
        get() = origins.minOf { it.knownAtIndex }

    init {
        require(price.isFinite()) { "level price must be finite" }
        require(origins.isNotEmpty()) { "breakout level needs at least one origin" }
        require(origins.all { it.knownAtIndex >= 0 }) {
            "origin knownAtIndex must be non-negative"
        }
        require(origins == origins.sortedWith(levelOriginComparator)) {
            "origins must use deterministic ordering"
        }
    }
}

@JvmInline
value class BreakoutId(val value: String)

data class LifecycleDeadlines(
    val retestUntil: Int,
    val failureUntil: Int,
    val continuationUntil: Int,
    val expiresAt: Int
) {
    init {
        require(retestUntil >= 0) { "retestUntil must be non-negative" }
        require(failureUntil >= 0) { "failureUntil must be non-negative" }
        require(continuationUntil >= 0) { "continuationUntil must be non-negative" }
        require(expiresAt >= 0) { "expiresAt must be non-negative" }
        require(retestUntil <= expiresAt) { "retestUntil must be <= expiresAt" }
        require(failureUntil <= expiresAt) { "failureUntil must be <= expiresAt" }
        require(continuationUntil <= expiresAt) { "continuationUntil must be <= expiresAt" }
    }
}

data class Breakout(
    val id: BreakoutId,
    val direction: BreakoutDirection,
    val level: BreakoutLevel,
    val attempt: Int,
    val breakIndex: Int,
    val breakAt: Instant,
    val breakClose: Double,
    val confirmedIndex: Int,
    val confirmedAt: Instant,
    val buffer: Double,
    val trigger: Double,
    val retestBand: Double?,
    val deadlines: LifecycleDeadlines
) {
    init {
        require(id.value.isNotBlank()) { "breakout id must not be blank" }
        require(attempt >= 1) { "attempt must be >= 1" }
        require(breakIndex >= 0) { "breakIndex must be non-negative" }
        require(confirmedIndex >= breakIndex) {
            "confirmedIndex must be >= breakIndex"
        }
        require(breakClose.isFinite()) { "breakClose must be finite" }
        require(buffer.isFinite() && buffer >= 0.0) { "buffer must be finite and non-negative" }
        require(trigger.isFinite()) { "trigger must be finite" }
        require(retestBand == null || (retestBand.isFinite() && retestBand >= 0.0)) {
            "retestBand must be null or finite and non-negative"
        }
        require(level.direction == direction) {
            "breakout direction must match level direction"
        }
    }
}

enum class QualificationCriterion {
    TREND_ALIGNMENT,
    BREAK_DISTANCE,
    CLOSE_LOCATION,
    RANGE_EXPANSION,
    VOLUME_CONFIRMATION
}

enum class CheckStatus { PASS, FAIL, UNAVAILABLE }

enum class QualificationStatus { QUALIFIED, UNQUALIFIED }

data class QualificationCheck(
    val criterion: QualificationCriterion,
    val required: Boolean,
    val status: CheckStatus,
    val observed: Double?,
    val requiredValue: Double?,
    val trendState: MarketStructureState?
) {
    init {
        require(observed == null || observed.isFinite()) {
            "observed must be finite when present"
        }
        require(requiredValue == null || requiredValue.isFinite()) {
            "requiredValue must be finite when present"
        }
        require(
            criterion == QualificationCriterion.TREND_ALIGNMENT ||
                trendState == null
        ) {
            "trendState is only valid for TREND_ALIGNMENT"
        }
    }
}

data class BreakoutQualification(
    val status: QualificationStatus,
    val checks: List<QualificationCheck>
) {
    val failed: List<QualificationCriterion>
        get() = checks.filter { it.status == CheckStatus.FAIL }.map { it.criterion }

    val unavailable: List<QualificationCriterion>
        get() = checks.filter { it.status == CheckStatus.UNAVAILABLE }.map { it.criterion }

    init {
        require(
            checks.map { it.criterion } ==
                checks.map { it.criterion }.distinct()
        ) { "qualification criteria must be unique" }

        require(
            checks.map { it.criterion.ordinal } ==
                checks.map { it.criterion.ordinal }.sorted()
        ) { "qualification checks must use fixed criterion order" }

        val required = checks.filter { it.required }
        require(required.isNotEmpty()) {
            "at least one qualification criterion must be required"
        }

        val qualifies = required.all { it.status == CheckStatus.PASS }
        require(
            status == if (qualifies) QualificationStatus.QUALIFIED
            else QualificationStatus.UNQUALIFIED
        ) { "qualification status contradicts required checks" }
    }
}

enum class SetupStage {
    BROKEN,
    RETEST_TOUCHED,
    RETEST_HELD,
    CONTINUED,
    FAILED,
    EXPIRED
}

sealed interface SetupEvent {
    val stage: SetupStage
    val index: Int
    val at: Instant
}

data class BrokenEvent(
    override val index: Int,
    override val at: Instant,
    val close: Double
) : SetupEvent {
    override val stage: SetupStage = SetupStage.BROKEN
}

data class RetestTouchedEvent(
    override val index: Int,
    override val at: Instant,
    val extreme: Double
) : SetupEvent {
    override val stage: SetupStage = SetupStage.RETEST_TOUCHED
}

data class RetestHeldEvent(
    override val index: Int,
    override val at: Instant,
    val close: Double
) : SetupEvent {
    override val stage: SetupStage = SetupStage.RETEST_HELD
}

data class ContinuedEvent(
    override val index: Int,
    override val at: Instant,
    val close: Double,
    val extremeBeaten: Double,
    val viaRetest: Boolean
) : SetupEvent {
    override val stage: SetupStage = SetupStage.CONTINUED
}

data class FailedEvent(
    override val index: Int,
    override val at: Instant,
    val close: Double,
    val retestTouched: Boolean,
    val retestHeld: Boolean
) : SetupEvent {
    override val stage: SetupStage = SetupStage.FAILED
}

data class ExpiredEvent(
    override val index: Int,
    override val at: Instant,
    val lastStage: SetupStage
) : SetupEvent {
    override val stage: SetupStage = SetupStage.EXPIRED
}

object SetupLifecycle {
    fun allowedFrom(s: SetupStage): Set<SetupStage> = when (s) {
        SetupStage.BROKEN -> setOf(
            SetupStage.RETEST_TOUCHED,
            SetupStage.CONTINUED,
            SetupStage.FAILED,
            SetupStage.EXPIRED
        )
        SetupStage.RETEST_TOUCHED -> setOf(
            SetupStage.RETEST_HELD,
            SetupStage.CONTINUED,
            SetupStage.FAILED,
            SetupStage.EXPIRED
        )
        SetupStage.RETEST_HELD -> setOf(
            SetupStage.CONTINUED,
            SetupStage.FAILED,
            SetupStage.EXPIRED
        )
        SetupStage.CONTINUED,
        SetupStage.FAILED,
        SetupStage.EXPIRED -> emptySet()
    }

    fun canTransition(a: SetupStage, b: SetupStage): Boolean =
        b in allowedFrom(a)
}

data class BreakoutSetup(
    val breakout: Breakout,
    val qualification: BreakoutQualification,
    val events: List<SetupEvent>
) {
    val stage: SetupStage
        get() = events.last().stage

    val isTerminal: Boolean
        get() = stage == SetupStage.CONTINUED ||
            stage == SetupStage.FAILED ||
            stage == SetupStage.EXPIRED

    init {
        require(events.isNotEmpty()) { "setup needs at least one event" }
        val first = events.first()
        require(first is BrokenEvent) { "first event must be BrokenEvent" }
        require(first.index == breakout.confirmedIndex) {
            "first BrokenEvent must be at confirmedIndex"
        }

        events.zipWithNext().forEach { (a, b) ->
            require(b.index >= a.index) {
                "setup event indices must be non-decreasing"
            }
            require(SetupLifecycle.canTransition(a.stage, b.stage)) {
                "illegal setup lifecycle transition: ${a.stage} -> ${b.stage}"
            }
        }

        require(
            events.dropLast(1).none {
                it.stage == SetupStage.CONTINUED ||
                    it.stage == SetupStage.FAILED ||
                    it.stage == SetupStage.EXPIRED
            }
        ) { "terminal stage must be the final event" }
    }
}

enum class SetupSignalKind {
    BREAKOUT_CONFIRMED,
    RETEST_CONFIRMED,
    CONTINUATION_CONFIRMED,
    FAILED_BREAKOUT
}

enum class SetupBias { BULLISH, BEARISH }

data class SetupSignal(
    val id: String,
    val kind: SetupSignalKind,
    val breakoutId: BreakoutId,
    val breakoutDirection: BreakoutDirection,
    val bias: SetupBias,
    val levelPrice: Double,
    val invalidationPrice: Double?,
    val qualification: QualificationStatus,
    val confirmedIndex: Int,
    val confirmedAt: Instant
) {
    init {
        require(id == "${breakoutId.value}#${kind.name}") {
            "signal id must be derived from breakout id and kind"
        }
        require(levelPrice.isFinite()) { "levelPrice must be finite" }
        require(invalidationPrice == null || invalidationPrice.isFinite()) {
            "invalidationPrice must be finite when present"
        }
        val expectedBias = if (kind == SetupSignalKind.FAILED_BREAKOUT) {
            when (breakoutDirection) {
                BreakoutDirection.UP -> SetupBias.BEARISH
                BreakoutDirection.DOWN -> SetupBias.BULLISH
            }
        } else {
            when (breakoutDirection) {
                BreakoutDirection.UP -> SetupBias.BULLISH
                BreakoutDirection.DOWN -> SetupBias.BEARISH
            }
        }
        require(bias == expectedBias) {
            "signal bias does not match signal kind and breakout direction"
        }
        require(
            if (kind == SetupSignalKind.FAILED_BREAKOUT) invalidationPrice == null
            else invalidationPrice == levelPrice
        ) {
            "invalidation price violates signal kind invariant"
        }
        require(confirmedIndex >= 0) { "confirmedIndex must be non-negative" }
    }
}

enum class AtrFallback { USE_ABSOLUTE, UNAVAILABLE }

data class BufferRule(
    val absolute: Double = 0.0,
    val atrMultiplier: Double = 0.0,
    val atrFallback: AtrFallback = AtrFallback.UNAVAILABLE
) {
    init {
        require(absolute.isFinite() && absolute >= 0.0) {
            "absolute must be finite and non-negative"
        }
        require(atrMultiplier.isFinite() && atrMultiplier >= 0.0) {
            "atrMultiplier must be finite and non-negative"
        }
    }

    fun resolve(atr: Double?): Double? {
        if (atrMultiplier == 0.0) return absolute
        if (atr == null) {
            return when (atrFallback) {
                AtrFallback.USE_ABSOLUTE -> absolute
                AtrFallback.UNAVAILABLE -> null
            }
        }
        require(atr.isFinite() && atr >= 0.0) {
            "ATR must be finite and non-negative"
        }
        return maxOf(absolute, atrMultiplier * atr)
    }
}

data class VolumeRule(
    val lookback: Int,
    val minRatio: Double
) {
    init {
        require(lookback >= 1) { "volume lookback must be >= 1" }
        require(minRatio.isFinite() && minRatio >= 0.0) {
            "volume minRatio must be finite and non-negative"
        }
    }
}
