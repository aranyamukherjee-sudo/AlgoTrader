package com.algotrader.intelligence.dna

import com.algotrader.domain.Timeframe
import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.Strategy

/**
 * Describes an existing engine [Strategy] (the seven built-ins, or any future
 * Strategy implementation) as Strategy DNA. This shows the DNA model is not
 * limited to those strategies; it does not execute or change them.
 *
 * Entry/exit rules are ENGINE_RULE leaves: the real logic stays in the engine
 * class and the text comes from the strategy's own metadata.
 */
object BuiltInStrategyDna {

    fun from(
        engineStrategyId: String,
        strategy: Strategy,
        market: MarketScope,
        timeframe: Timeframe
    ): StrategyDna {
        val metadata = strategy.metadata
        return StrategyDna(
            id = StrategyId("builtin.$engineStrategyId"),
            version = 1,
            name = strategy.name,
            origin = StrategyOrigin.BUILT_IN,
            market = market,
            timeframe = timeframe,
            sides = when (metadata.direction) {
                PositionDirection.LONG_ONLY -> setOf(TradeSide.LONG)
                PositionDirection.SHORT_ONLY -> setOf(TradeSide.SHORT)
                PositionDirection.LONG_AND_SHORT -> setOf(TradeSide.LONG, TradeSide.SHORT)
            },
            indicators = metadata.requiredIndicators.map { parseIndicator(it) },
            entry = Condition.Leaf(
                id = "entry",
                feature = FeatureRef(FeatureDomain.ENGINE_RULE, "$engineStrategyId.entry"),
                description = metadata.entryRule
            ),
            exit = Condition.Leaf(
                id = "exit",
                feature = FeatureRef(FeatureDomain.ENGINE_RULE, "$engineStrategyId.exit"),
                description = metadata.exitRule
            ),
            risk = RiskSpec(
                stopLossPercent = metadata.stopLossPercent,
                takeProfitPercent = metadata.takeProfitPercent
            ),
            engineBinding = EngineBinding(
                engineStrategyId = engineStrategyId,
                parameters = metadata.parameters.associate { it.name to it.value }
            ),
            description = metadata.description
        )
    }

    /** "RSI(14)" -> INDICATOR "RSI" {arg0=14}; "CPR(daily)" -> {arg0="daily"}. */
    internal fun parseIndicator(spec: String): FeatureRef {
        val open = spec.indexOf('(')
        if (open < 0 || !spec.endsWith(")")) {
            return FeatureRef(FeatureDomain.INDICATOR, spec.trim())
        }
        val type = spec.substring(0, open).trim()
        val args = spec.substring(open + 1, spec.length - 1)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val params = LinkedHashMap<String, ParamValue>()
        args.forEachIndexed { index, arg ->
            val number = arg.toDoubleOrNull()
            val value: ParamValue =
                if (number != null && number.isFinite()) ParamValue.Num(number) else ParamValue.Text(arg)
            params["arg$index"] = value
        }
        return FeatureRef(FeatureDomain.INDICATOR, type, params)
    }
}
