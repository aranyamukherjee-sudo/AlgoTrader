package com.algotrader.intelligence.setup

import com.algotrader.domain.Candle
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.Volatility

/**
 * Detects deterministic close-based breakouts from confirmed A.2 levels.
 *
 * ASI-3.3A.3 only:
 * - detects breakout crossing
 * - applies confirmationCloses
 * - resolves the breakout buffer
 * - creates Breakout domain objects
 *
 * Qualification and subsequent setup lifecycle stages are intentionally
 * outside this component.
 */
object BreakoutDetector {

    fun detect(
        candles: List<Candle>,
        structure: StructureAnalysis,
        patterns: PatternAnalysis,
        config: SetupConfig = SetupConfig()
    ): List<Breakout> {
        require(candles.size >= 2) { "at least two candles are required" }
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

        val atr = Volatility.atr(
            candles,
            config.pattern.structure.atrPeriod
        )

        val breakouts = mutableListOf<Breakout>()
        val attempts = mutableMapOf<LevelIdentity, Int>()
        val pending = mutableMapOf<LevelIdentity, Pending>()

        for (index in candles.indices) {
            if (index == 0) continue

            val levels = LevelExtractor.extract(
                candles = candles,
                structure = structure,
                patterns = patterns,
                config = config,
                asOfIndex = index
            )

            val currentClose = candles[index].close
            val previousClose = candles[index - 1].close

            val activeKeys = levels.mapTo(hashSetOf()) { LevelIdentity(it) }
            pending.keys.retainAll(activeKeys)

            /*
             * Pending confirmations are evaluated before new crossings.
             * A close back at/inside the trigger cancels the candidate.
             */
            val pendingSnapshot = pending.toMap()
            for ((identity, candidate) in pendingSnapshot) {
                val level = levels.firstOrNull {
                    LevelIdentity(it) == identity
                } ?: continue

                val trigger = candidate.trigger
                val beyond = isBeyond(
                    direction = level.direction,
                    close = currentClose,
                    trigger = trigger
                )

                if (!beyond) {
                    pending.remove(identity)
                    continue
                }

                val nextCount = candidate.count + 1
                if (nextCount >= config.confirmationCloses) {
                    val buffer = candidate.buffer
                    val attempt = nextAttempt(attempts, identity)

                    breakouts += createBreakout(
                        level = level,
                        attempt = attempt,
                        breakIndex = candidate.startIndex,
                        confirmedIndex = index,
                        candles = candles,
                        buffer = buffer,
                        trigger = trigger,
                        config = config
                    )
                    pending.remove(identity)
                } else {
                    pending[identity] = candidate.copy(count = nextCount)
                }
            }

            /*
             * A level already pending/confirmed at this index cannot create
             * another crossing from the same close transition.
             */
            val alreadyPending = pending.keys.toSet()

            for (level in levels) {
                val identity = LevelIdentity(level)

                if (identity in alreadyPending) continue

                val buffer = atr.getOrNull(index)?.let {
                    config.buffer.resolve(it)
                } ?: config.buffer.resolve(null)

                if (buffer == null) continue

                val trigger = when (level.direction) {
                    BreakoutDirection.UP -> level.price + buffer
                    BreakoutDirection.DOWN -> level.price - buffer
                }

                if (!trigger.isFinite()) continue

                val crossed = crossed(
                    direction = level.direction,
                    previousClose = previousClose,
                    currentClose = currentClose,
                    trigger = trigger
                )

                if (!crossed) continue

                if (config.confirmationCloses == 1) {
                    val attempt = nextAttempt(attempts, identity)

                    breakouts += createBreakout(
                        level = level,
                        attempt = attempt,
                        breakIndex = index,
                        confirmedIndex = index,
                        candles = candles,
                        buffer = buffer,
                        trigger = trigger,
                        config = config
                    )
                } else {
                    pending[identity] = Pending(
                        startIndex = index,
                        count = 1,
                        buffer = buffer,
                        trigger = trigger
                    )
                }
            }
        }

        return breakouts
            .sortedWith(
                compareBy<Breakout>(
                    { it.confirmedIndex },
                    { it.level.side.ordinal },
                    { it.level.price },
                    { it.attempt }
                )
            )
    }

    private data class LevelIdentity(
        val side: com.algotrader.intelligence.structure.ZoneKind,
        val price: Double
    ) {
        constructor(level: BreakoutLevel) : this(level.side, level.price)
    }

    private data class Pending(
        val startIndex: Int,
        val count: Int,
        val buffer: Double,
        val trigger: Double
    )

    private fun nextAttempt(
        attempts: MutableMap<LevelIdentity, Int>,
        identity: LevelIdentity
    ): Int {
        val next = (attempts[identity] ?: 0) + 1
        attempts[identity] = next
        return next
    }

    private fun crossed(
        direction: BreakoutDirection,
        previousClose: Double,
        currentClose: Double,
        trigger: Double
    ): Boolean =
        when (direction) {
            BreakoutDirection.UP ->
                previousClose <= trigger && currentClose > trigger

            BreakoutDirection.DOWN ->
                previousClose >= trigger && currentClose < trigger
        }

    private fun isBeyond(
        direction: BreakoutDirection,
        close: Double,
        trigger: Double
    ): Boolean =
        when (direction) {
            BreakoutDirection.UP -> close > trigger
            BreakoutDirection.DOWN -> close < trigger
        }

    private fun createBreakout(
        level: BreakoutLevel,
        attempt: Int,
        breakIndex: Int,
        confirmedIndex: Int,
        candles: List<Candle>,
        buffer: Double,
        trigger: Double,
        config: SetupConfig
    ): Breakout {
        val direction = level.direction
        val breakClose = candles[confirmedIndex].close

        val id = BreakoutId(
            "${direction.name}:${level.price}:b$attempt"
        )

        val deadlines = LifecycleDeadlines(
            retestUntil = confirmedIndex + config.retestWindow,
            failureUntil = confirmedIndex + config.failureWindow,
            continuationUntil = confirmedIndex + config.continuationWindow,
            expiresAt = confirmedIndex +
                maxOf(
                    config.retestWindow,
                    config.failureWindow,
                    config.continuationWindow
                )
        )

        return Breakout(
            id = id,
            direction = direction,
            level = level,
            attempt = attempt,
            breakIndex = breakIndex,
            breakAt = candles[breakIndex].timestamp,
            breakClose = breakClose,
            confirmedIndex = confirmedIndex,
            confirmedAt = candles[confirmedIndex].timestamp,
            buffer = buffer,
            trigger = trigger,
            retestBand = config.retestBand.resolve(
                Volatility.atr(
                    candles.take(confirmedIndex + 1),
                    config.pattern.structure.atrPeriod
                ).lastOrNull()
            ),
            deadlines = deadlines
        )
    }
}
