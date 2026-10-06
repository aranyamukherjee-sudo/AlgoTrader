package com.algotrader.discovery.rules

import com.algotrader.discovery.features.FeatureRegistry
import com.algotrader.domain.Candle
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.StrategyDna
import com.algotrader.intelligence.dna.TradeSide
import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.Signal
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.StrategyContext
import com.algotrader.strategy.StrategyMetadata

object RuleEvaluator {

    fun evaluate(condition: Condition, registry: FeatureRegistry, candles: List<Candle>): Boolean =
        when (condition) {
            is Condition.Leaf -> registry.evaluate(condition.feature, candles)
            is Condition.AllOf -> condition.conditions.all { evaluate(it, registry, candles) }
            is Condition.AnyOf -> condition.conditions.any { evaluate(it, registry, candles) }
        }

    fun describe(condition: Condition): String = when (condition) {
        is Condition.Leaf -> condition.description
        is Condition.AllOf -> "all of (" + condition.conditions.joinToString("; ") { describe(it) } + ")"
        is Condition.AnyOf -> "any of (" + condition.conditions.joinToString("; ") { describe(it) } + ")"
    }
}

/**
 * Runs a [StrategyDna] through the EXISTING backtest engine by presenting it
 * as an engine [Strategy]. No trade or P&L logic is duplicated.
 *
 * Signals (evaluated on candles up to and including the current bar; the
 * engine executes at the next bar's open):
 * - exit or invalidation condition true -> SELL (closes a long; no-op when flat)
 * - else required context AND entry AND confirmation true -> BUY
 * - else no signal
 * An exit therefore always wins over an entry on the same bar.
 *
 * Limitations (this increment): LONG only; the engine has no stop-loss /
 * take-profit exits, so DNA risk percentages are not simulated and exits are
 * rule-based only. Signal.confidence is left at its default (unscored); the
 * discovery confidence comes from the validation evidence, not from here.
 */
class RuleStrategy(
    val dna: StrategyDna,
    private val registry: FeatureRegistry
) : Strategy {

    init {
        require(dna.sides == setOf(TradeSide.LONG)) {
            "RuleStrategy supports LONG-only DNA in this increment (was ${dna.sides})"
        }
        val unknown = registry.unknownFeatures(dna)
        require(unknown.isEmpty()) { "DNA uses features this registry cannot evaluate: $unknown" }
    }

    override val name: String get() = dna.name

    override val metadata: StrategyMetadata = StrategyMetadata(
        description = dna.description.ifBlank { dna.name },
        requiredIndicators = emptyList(),
        parameters = emptyList(),
        direction = PositionDirection.LONG_ONLY,
        entryRule = RuleEvaluator.describe(dna.entry),
        exitRule = RuleEvaluator.describe(dna.exit)
    )

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles
        if (candles.isEmpty()) return emptyList()
        val last = candles[candles.size - 1]

        val exitFired = RuleEvaluator.evaluate(dna.exit, registry, candles)
        val invalidated = dna.invalidation?.let { RuleEvaluator.evaluate(it, registry, candles) } ?: false
        if (exitFired || invalidated) {
            val why = if (exitFired) "exit condition met" else "invalidation condition met"
            return listOf(Signal(last.instrument, SignalType.SELL, last.timestamp, reason = why))
        }

        val contextOk = dna.requiredContext?.let { RuleEvaluator.evaluate(it, registry, candles) } ?: true
        val confirmed = dna.confirmation?.let { RuleEvaluator.evaluate(it, registry, candles) } ?: true
        if (contextOk && confirmed && RuleEvaluator.evaluate(dna.entry, registry, candles)) {
            return listOf(Signal(last.instrument, SignalType.BUY, last.timestamp, reason = "entry conditions met"))
        }
        return emptyList()
    }
}
