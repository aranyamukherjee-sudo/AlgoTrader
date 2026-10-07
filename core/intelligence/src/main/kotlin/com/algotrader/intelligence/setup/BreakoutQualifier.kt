package com.algotrader.intelligence.setup

import com.algotrader.domain.Candle
import com.algotrader.intelligence.structure.MarketStructure
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.Volatility

/**
 * Evaluates the evidence available at a confirmed breakout.
 *
 * All calculations are as-of the breakout confirmation bar. No future
 * candles, future pivots, or final-series structure state are used.
 */
object BreakoutQualifier {

    fun qualify(
        candles: List<Candle>,
        structure: com.algotrader.intelligence.structure.StructureAnalysis,
        breakout: Breakout,
        config: QualificationConfig
    ): BreakoutQualification {
        require(candles.isNotEmpty()) { "candles must not be empty" }
        require(structure.candleCount == candles.size) {
            "structure candle count must match candles"
        }
        require(breakout.confirmedIndex in candles.indices) {
            "breakout confirmedIndex must be within candles"
        }

        val index = breakout.confirmedIndex
        val atrSeries = Volatility.atr(
            candles,
            config.atrPeriod
        )

        val atr = atrSeries.getOrNull(index)
        val trend = trendAsOf(structure, index)

        val checks = listOf(
            trendCheck(
                breakout = breakout,
                trend = trend,
                required = config.requireTrendAlignment
            ),
            breakDistanceCheck(
                candles = candles,
                breakout = breakout,
                atr = atr,
                rule = config.minBreakDistance
            ),
            closeLocationCheck(
                candle = candles[index],
                direction = breakout.direction,
                minimum = config.minCloseLocation
            ),
            rangeExpansionCheck(
                candle = candles[index],
                atr = atr,
                minimum = config.minRangeAtr
            ),
            volumeCheck(
                candles = candles,
                index = index,
                rule = config.volume
            )
        )

        return BreakoutQualification(
            status = if (checks.filter { it.required }.all {
                it.status == CheckStatus.PASS
            }) {
                QualificationStatus.QUALIFIED
            } else {
                QualificationStatus.UNQUALIFIED
            },
            checks = checks
        )
    }

    private fun trendAsOf(
        structure: com.algotrader.intelligence.structure.StructureAnalysis,
        index: Int
    ): MarketStructureState {
        val pivots = structure.pivots
            .filter { it.pivot.confirmedIndex <= index }

        return MarketStructure.state(pivots)
    }

    private fun trendCheck(
        breakout: Breakout,
        trend: MarketStructureState,
        required: Boolean
    ): QualificationCheck {
        val status = when (trend) {
            MarketStructureState.RANGE -> CheckStatus.PASS
            MarketStructureState.UPTREND ->
                if (breakout.direction == BreakoutDirection.UP) {
                    CheckStatus.PASS
                } else {
                    CheckStatus.FAIL
                }
            MarketStructureState.DOWNTREND ->
                if (breakout.direction == BreakoutDirection.DOWN) {
                    CheckStatus.PASS
                } else {
                    CheckStatus.FAIL
                }
            MarketStructureState.UNDEFINED -> CheckStatus.UNAVAILABLE
        }

        return QualificationCheck(
            criterion = QualificationCriterion.TREND_ALIGNMENT,
            required = required,
            status = status,
            observed = null,
            requiredValue = null,
            trendState = trend
        )
    }

    private fun breakDistanceCheck(
        candles: List<Candle>,
        breakout: Breakout,
        atr: Double?,
        rule: BufferRule?
    ): QualificationCheck {
        if (rule == null) {
            return unavailable(QualificationCriterion.BREAK_DISTANCE, false)
        }

        val distance = kotlin.math.abs(
            candles[breakout.confirmedIndex].close - breakout.level.price
        )
        val threshold = rule.resolve(atr)

        if (threshold == null) {
            return unavailable(
                QualificationCriterion.BREAK_DISTANCE,
                true,
                observed = distance
            )
        }

        return QualificationCheck(
            criterion = QualificationCriterion.BREAK_DISTANCE,
            required = true,
            status = if (distance >= threshold) CheckStatus.PASS else CheckStatus.FAIL,
            observed = distance,
            requiredValue = threshold,
            trendState = null
        )
    }

    private fun closeLocationCheck(
        candle: Candle,
        direction: BreakoutDirection,
        minimum: Double?
    ): QualificationCheck {
        if (minimum == null) {
            return unavailable(QualificationCriterion.CLOSE_LOCATION, false)
        }

        val range = candle.high - candle.low
        if (!range.isFinite() || range <= 0.0) {
            return unavailable(QualificationCriterion.CLOSE_LOCATION, true)
        }

        val location = when (direction) {
            BreakoutDirection.UP ->
                (candle.close - candle.low) / range
            BreakoutDirection.DOWN ->
                (candle.high - candle.close) / range
        }

        return QualificationCheck(
            criterion = QualificationCriterion.CLOSE_LOCATION,
            required = true,
            status = if (location >= minimum) CheckStatus.PASS else CheckStatus.FAIL,
            observed = location,
            requiredValue = minimum,
            trendState = null
        )
    }

    private fun rangeExpansionCheck(
        candle: Candle,
        atr: Double?,
        minimum: Double?
    ): QualificationCheck {
        if (minimum == null) {
            return unavailable(QualificationCriterion.RANGE_EXPANSION, false)
        }

        if (atr == null || !atr.isFinite() || atr <= 0.0) {
            return unavailable(QualificationCriterion.RANGE_EXPANSION, true)
        }

        val range = candle.high - candle.low
        if (!range.isFinite() || range < 0.0) {
            return unavailable(QualificationCriterion.RANGE_EXPANSION, true)
        }

        val ratio = range / atr

        return QualificationCheck(
            criterion = QualificationCriterion.RANGE_EXPANSION,
            required = true,
            status = if (ratio >= minimum) CheckStatus.PASS else CheckStatus.FAIL,
            observed = ratio,
            requiredValue = minimum,
            trendState = null
        )
    }

    private fun volumeCheck(
        candles: List<Candle>,
        index: Int,
        rule: VolumeRule?
    ): QualificationCheck {
        if (rule == null) {
            return unavailable(QualificationCriterion.VOLUME_CONFIRMATION, false)
        }

        if (index < rule.lookback) {
            return unavailable(QualificationCriterion.VOLUME_CONFIRMATION, true)
        }

        val prior = candles.subList(index - rule.lookback, index)
        val average = prior.map { it.volume }.average()

        if (!average.isFinite() || average <= 0.0) {
            return unavailable(QualificationCriterion.VOLUME_CONFIRMATION, true)
        }

        val current = candles[index].volume
        if (!current.isFinite() || current < 0.0) {
            return unavailable(QualificationCriterion.VOLUME_CONFIRMATION, true)
        }

        val ratio = current / average

        return QualificationCheck(
            criterion = QualificationCriterion.VOLUME_CONFIRMATION,
            required = true,
            status = if (ratio >= rule.minRatio) CheckStatus.PASS else CheckStatus.FAIL,
            observed = ratio,
            requiredValue = rule.minRatio,
            trendState = null
        )
    }

    private fun unavailable(
        criterion: QualificationCriterion,
        required: Boolean,
        observed: Double? = null
    ) = QualificationCheck(
        criterion = criterion,
        required = required,
        status = CheckStatus.UNAVAILABLE,
        observed = observed,
        requiredValue = null,
        trendState = null
    )
}
