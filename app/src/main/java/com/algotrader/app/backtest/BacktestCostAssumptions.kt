package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.ResearchCostModel
import java.math.BigDecimal

/**
 * Phase 3 Patch 11 — validation of generic assumed research costs.
 *
 * Pure and Android-free. It does NOT calculate any cost: all arithmetic stays
 * in [ResearchCostModel]. It only decides whether three user-supplied numbers
 * are acceptable inputs to that model, and builds the model.
 *
 * These are generic research assumptions. They are not broker charges and not
 * statutory F&O charges.
 *
 * Units (they match [ResearchCostModel] exactly):
 * - commission: percent of the combined entry and exit notional
 *   and to the exit notional (e.g. 0.03 means 0.03%).
 * - slippage: basis points of the combined entry and exit notional
 *   notional and to the exit notional (1 bp = 0.01%).
 * - fixed cost: rupees charged once per completed round trip.
 *
 * Every value must be finite and non-negative and must not exceed the upper
 * bound below. The bounds are sanity limits against typing mistakes, not
 * claims about real-world rates.
 */
object BacktestCostAssumptions {

    const val MAX_COMMISSION_PERCENT = 5.0
    const val MAX_SLIPPAGE_BPS = 500.0
    const val MAX_FIXED_COST_PER_TRADE = 100_000.0

    enum class Field { COMMISSION_PERCENT, SLIPPAGE_BPS, FIXED_COST_PER_TRADE }

    sealed interface Outcome {
        data class Valid(val model: ResearchCostModel) : Outcome
        data class Invalid(val field: Field, val message: String) : Outcome
    }

    /** True when the model charges nothing (the legacy, gross-only behaviour). */
    fun isZero(model: ResearchCostModel): Boolean =
        model.commissionRatePercent == 0.0 &&
            model.slippageBps == 0.0 &&
            model.fixedCostPerTrade == 0.0

    /**
     * Parses the three setup-screen fields. A blank field means "no cost" (0).
     * Anything else must be a plain decimal number (optional sign, no
     * exponent, no "NaN"/"Infinity", no unit suffix).
     */
    fun parse(
        commissionText: String,
        slippageText: String,
        fixedCostText: String
    ): Outcome {
        val commission = number(commissionText)
            ?: return Outcome.Invalid(Field.COMMISSION_PERCENT, notANumber(Field.COMMISSION_PERCENT))
        val slippage = number(slippageText)
            ?: return Outcome.Invalid(Field.SLIPPAGE_BPS, notANumber(Field.SLIPPAGE_BPS))
        val fixed = number(fixedCostText)
            ?: return Outcome.Invalid(Field.FIXED_COST_PER_TRADE, notANumber(Field.FIXED_COST_PER_TRADE))
        return create(commission, slippage, fixed)
    }

    /** Validates already-numeric values and builds the model, or explains why not. */
    fun create(
        commissionRatePercent: Double,
        slippageBps: Double,
        fixedCostPerTrade: Double
    ): Outcome {
        problem(Field.COMMISSION_PERCENT, commissionRatePercent)
            ?.let { return Outcome.Invalid(Field.COMMISSION_PERCENT, it) }
        problem(Field.SLIPPAGE_BPS, slippageBps)
            ?.let { return Outcome.Invalid(Field.SLIPPAGE_BPS, it) }
        problem(Field.FIXED_COST_PER_TRADE, fixedCostPerTrade)
            ?.let { return Outcome.Invalid(Field.FIXED_COST_PER_TRADE, it) }

        // "+ 0.0" turns a negative zero into 0.0, so equal assumptions are
        // always equal models (data-class equality distinguishes -0.0 from 0.0).
        return Outcome.Valid(
            ResearchCostModel(
                commissionRatePercent = commissionRatePercent + 0.0,
                slippageBps = slippageBps + 0.0,
                fixedCostPerTrade = fixedCostPerTrade + 0.0
            )
        )
    }

    /**
     * True when every checkpointed result was computed under exactly [model].
     * A job is resumed only if this holds, so one job never mixes results
     * produced under different cost assumptions.
     */
    fun checkpointConsistent(model: ResearchCostModel, results: List<BacktestResult>): Boolean =
        results.all { it.config.researchCostModel == model }

    /** Null when [model] satisfies every input rule; otherwise the first problem. */
    fun validationError(model: ResearchCostModel): String? =
        (create(
            model.commissionRatePercent,
            model.slippageBps,
            model.fixedCostPerTrade
        ) as? Outcome.Invalid)?.message

    // -----------------------------------------------------------------

    private val PLAIN_DECIMAL = Regex("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$")

    /** Blank -> 0.0; plain decimal -> value; anything else -> null. */
    private fun number(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0.0
        if (!PLAIN_DECIMAL.matches(trimmed)) return null
        return trimmed.toDouble()
    }

    private fun label(field: Field): String = when (field) {
        Field.COMMISSION_PERCENT -> "Commission"
        Field.SLIPPAGE_BPS -> "Slippage"
        Field.FIXED_COST_PER_TRADE -> "Fixed cost per trade"
    }

    private fun unit(field: Field): String = when (field) {
        Field.COMMISSION_PERCENT -> "% of combined entry + exit notional"
        Field.SLIPPAGE_BPS -> "bps of combined entry + exit notional"
        Field.FIXED_COST_PER_TRADE -> "\u20b9 per round trip"
    }

    private fun max(field: Field): Double = when (field) {
        Field.COMMISSION_PERCENT -> MAX_COMMISSION_PERCENT
        Field.SLIPPAGE_BPS -> MAX_SLIPPAGE_BPS
        Field.FIXED_COST_PER_TRADE -> MAX_FIXED_COST_PER_TRADE
    }

    private fun notANumber(field: Field): String =
        "${label(field)} must be a plain number (${unit(field)})."

    private fun problem(field: Field, value: Double): String? = when {
        !value.isFinite() -> "${label(field)} must be a finite number (${unit(field)})."
        value < 0.0 -> "${label(field)} cannot be negative (${unit(field)})."
        value > max(field) ->
            "${label(field)} cannot exceed ${plain(max(field))} ${unit(field)}."
        else -> null
    }

    /** Deterministic plain-decimal text: 0.03 -> "0.03", 5.0 -> "5", 0.0 -> "0". */
    fun plain(value: Double): String =
        BigDecimal(value.toString()).let {
            if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString()
        }
}
