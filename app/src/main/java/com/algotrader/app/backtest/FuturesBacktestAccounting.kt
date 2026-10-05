package com.algotrader.app.backtest

import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.TradeDirection
import com.algotrader.domain.fno.Direction
import com.algotrader.domain.fno.FuturesCalculator
import com.algotrader.domain.fno.FuturesInputs
import com.algotrader.domain.fno.Input
import com.algotrader.domain.fno.ResultKind
import com.algotrader.domain.fno.ValuationBasis
import com.algotrader.domain.fno.ValuationPrice
import java.math.BigDecimal

/**
 * Phase 3 Patch 10 — pure adapter between persisted futures backtest results
 * and the authoritative FuturesCalculator.
 *
 * No F&O arithmetic is implemented here. The adapter only:
 * - validates persisted contract identity/lot size
 * - converts engine values to calculator inputs
 * - derives whole lots without rounding
 * - calls FuturesCalculator
 * - aggregates calculator outputs
 * - compares calculator gross P&L with the engine's own per-trade gross P&L
 *
 * Leverage, margin, charges, net P&L and break-even remain NOT_MODELLED.
 */
object FuturesBacktestAccounting {

    const val SCHEMA_VERSION = 1
    const val LOT_SIZE_EVIDENCE = "SUPPLIED"
    const val NOTIONAL_BASIS =
        "ENTRY_PRICE × quantity per trade, summed over trades"
    const val NOT_MODELLED = "NOT_MODELLED"

    enum class Status {
        COMPUTED,
        NOT_COMPUTED,
        INVALID_INPUT
    }

    /** Exact BigDecimal.compareTo comparison. No tolerance is used. */
    enum class GrossPnlComparison {
        MATCH,
        MISMATCH,
        NOT_COMPARABLE,
        INVALID_INPUT
    }

    data class Block(
        val schemaVersion: Int = SCHEMA_VERSION,
        val contractId: String?,
        val lotSize: Int?,
        val lotSizeSource: String?,
        /**
         * SUPPLIED only when a valid persisted lot size exists.
         * Null when lot size is unavailable/invalid.
         */
        val lotSizeEvidence: String?,
        val expiryEpochSeconds: Long?,
        val notionalBasis: String = NOTIONAL_BASIS,
        val contractNotional: BigDecimal?,
        val tradeCount: Int,
        val computedTradeCount: Int,
        val calculatorGrossPnl: BigDecimal?,
        val engineGrossPnl: BigDecimal?,
        val grossPnlDifference: BigDecimal?,
        val grossPnlComparison: GrossPnlComparison,
        val status: Status,
        val reason: String?,
        val leverage: String = NOT_MODELLED,
        val charges: String = NOT_MODELLED,
        val netPnl: String = NOT_MODELLED,
        val breakEven: String = NOT_MODELLED
    )

    fun forJob(
        job: BacktestJobStore.Job,
        results: List<BacktestResult>
    ): List<Block?> =
        results.map { compute(job, it) }

    /**
     * Produces a diagnostic INVALID_INPUT block if the accounting adapter
     * itself fails unexpectedly. This must never prevent successful engine
     * results from being persisted.
     */
    fun failureForJob(
        job: BacktestJobStore.Job,
        results: List<BacktestResult>,
        error: Throwable
    ): List<Block?> {
        if (job.instrumentType != BacktestInstrumentType.FUTURES) {
            return results.map { null }
        }

        val contract = job.futuresContract
        val lotSize = contract?.lotSize
        val evidence = lotSize
            ?.takeIf { it >= 1 }
            ?.let { LOT_SIZE_EVIDENCE }

        return results.map { result ->
            Block(
                contractId = contract?.contractId,
                lotSize = lotSize,
                lotSizeSource = contract?.lotSizeSource,
                lotSizeEvidence = evidence,
                expiryEpochSeconds = contract?.expiryEpochSeconds,
                contractNotional = null,
                tradeCount = result.trades.size,
                computedTradeCount = 0,
                calculatorGrossPnl = null,
                engineGrossPnl = null,
                grossPnlDifference = null,
                grossPnlComparison = GrossPnlComparison.INVALID_INPUT,
                status = Status.INVALID_INPUT,
                reason = "futures accounting failed: ${error.javaClass.simpleName}"
            )
        }
    }

    fun compute(
        job: BacktestJobStore.Job,
        result: BacktestResult
    ): Block? {
        if (job.instrumentType != BacktestInstrumentType.FUTURES) {
            return null
        }
        return compute(job.futuresContract, result.trades)
    }

    /**
     * Core adapter. [contract] must be the persisted job.futuresContract.
     */
    fun compute(
        contract: FuturesContractConfig?,
        trades: List<BacktestTrade>
    ): Block {
        val tradeCount = trades.size
        val engineGross = engineGrossPnl(trades)

        fun block(
            status: Status,
            reason: String,
            comparison: GrossPnlComparison
        ) = Block(
            contractId = contract?.contractId,
            lotSize = contract?.lotSize,
            lotSizeSource = contract?.lotSizeSource,
            lotSizeEvidence = contract?.lotSize
                ?.takeIf { it >= 1 }
                ?.let { LOT_SIZE_EVIDENCE },
            expiryEpochSeconds = contract?.expiryEpochSeconds,
            contractNotional = null,
            tradeCount = tradeCount,
            computedTradeCount = 0,
            calculatorGrossPnl = null,
            engineGrossPnl = engineGross,
            grossPnlDifference = null,
            grossPnlComparison = comparison,
            status = status,
            reason = reason
        )

        if (contract == null) {
            return block(
                Status.NOT_COMPUTED,
                "futures contract not persisted for this job",
                GrossPnlComparison.NOT_COMPARABLE
            )
        }

        val contractId = contract.contractId
            ?: return block(
                Status.NOT_COMPUTED,
                "contract id not available",
                GrossPnlComparison.NOT_COMPARABLE
            )

        if (contractId.isBlank()) {
            return block(
                Status.INVALID_INPUT,
                "contract id is blank",
                GrossPnlComparison.INVALID_INPUT
            )
        }

        val lotSize = contract.lotSize
            ?: return block(
                Status.NOT_COMPUTED,
                "lot size not available; no default is used",
                GrossPnlComparison.NOT_COMPARABLE
            )

        if (lotSize < 1) {
            return block(
                Status.INVALID_INPUT,
                "lot size must be >= 1, got $lotSize",
                GrossPnlComparison.INVALID_INPUT
            )
        }

        if (tradeCount == 0) {
            return block(
                Status.NOT_COMPUTED,
                "no trades; accounting not computed",
                GrossPnlComparison.NOT_COMPARABLE
            )
        }

        var gross = BigDecimal.ZERO
        var notional = BigDecimal.ZERO
        var computed = 0
        val problems = ArrayList<String>()
        var anyInvalid = false

        trades.forEachIndexed { index, trade ->
            val inputs = inputsFor(contractId, lotSize, trade)

            val calc = try {
                FuturesCalculator.calculate(inputs)
            } catch (e: RuntimeException) {
                anyInvalid = true
                problems +=
                    "trade $index: calculator rejected inputs (${e.javaClass.simpleName})"
                return@forEachIndexed
            }

            val g = calc.grossPnl
            val n = calc.contractNotional

            if (g.value != null && n.value != null) {
                gross = gross.add(g.value!!)
                notional = notional.add(n.value!!)
                computed++
            } else {
                if (
                    g.kind == ResultKind.INVALID_INPUT ||
                    n.kind == ResultKind.INVALID_INPUT
                ) {
                    anyInvalid = true
                }

                val detail = (g.warnings + n.warnings)
                    .distinct()
                    .joinToString("; ")

                problems +=
                    "trade $index: ${g.kind}" +
                        if (detail.isEmpty()) "" else " ($detail)"
            }
        }

        // Never report a partial total: all trades or none.
        if (computed != tradeCount) {
            return Block(
                contractId = contractId,
                lotSize = lotSize,
                lotSizeSource = contract.lotSizeSource,
                lotSizeEvidence = LOT_SIZE_EVIDENCE,
                expiryEpochSeconds = contract.expiryEpochSeconds,
                contractNotional = null,
                tradeCount = tradeCount,
                computedTradeCount = computed,
                calculatorGrossPnl = null,
                engineGrossPnl = engineGross,
                grossPnlDifference = null,
                grossPnlComparison =
                    if (anyInvalid) {
                        GrossPnlComparison.INVALID_INPUT
                    } else {
                        GrossPnlComparison.NOT_COMPARABLE
                    },
                status =
                    if (anyInvalid) {
                        Status.INVALID_INPUT
                    } else {
                        Status.NOT_COMPUTED
                    },
                reason =
                    "$computed of $tradeCount trades computed: " +
                        problems.joinToString(" | ")
            )
        }

        val comparison: GrossPnlComparison
        val difference: BigDecimal?

        if (engineGross == null) {
            comparison = GrossPnlComparison.NOT_COMPARABLE
            difference = null
        } else {
            difference = gross.subtract(engineGross)
            comparison =
                if (gross.compareTo(engineGross) == 0) {
                    GrossPnlComparison.MATCH
                } else {
                    GrossPnlComparison.MISMATCH
                }
        }

        return Block(
            contractId = contractId,
            lotSize = lotSize,
            lotSizeSource = contract.lotSizeSource,
            lotSizeEvidence = LOT_SIZE_EVIDENCE,
            expiryEpochSeconds = contract.expiryEpochSeconds,
            contractNotional = notional,
            tradeCount = tradeCount,
            computedTradeCount = computed,
            calculatorGrossPnl = gross,
            engineGrossPnl = engineGross,
            grossPnlDifference = difference,
            grossPnlComparison = comparison,
            status = Status.COMPUTED,
            reason =
                if (engineGross == null) {
                    "engine gross P&L is not finite; not comparable"
                } else {
                    null
                }
        )
    }

    private fun engineGrossPnl(
        trades: List<BacktestTrade>
    ): BigDecimal? {
        if (trades.isEmpty()) return null

        var sum = BigDecimal.ZERO

        for (trade in trades) {
            val gross = trade.grossPnl
            if (!gross.isFinite()) return null
            sum = sum.add(BigDecimal.valueOf(gross))
        }

        return sum
    }

    private fun inputsFor(
        contractId: String,
        lotSize: Int,
        trade: BacktestTrade
    ): FuturesInputs {
        val entry = decimal(trade.entryPrice, "entry price")
        val exit = decimal(trade.exitPrice, "exit price")

        return FuturesInputs(
            contractId = Input.Known(contractId),
            lotSize = Input.Known(lotSize.toLong()),
            lots = lotsOf(trade.quantity, lotSize),
            entryPrice = entry,
            exitPrice = exit,
            direction = Input.Known(
                when (trade.direction) {
                    TradeDirection.LONG -> Direction.LONG
                    TradeDirection.SHORT -> Direction.SHORT
                }
            ),
            valuationPrice =
                (entry as? Input.Known)
                    ?.let {
                        Input.Known(
                            ValuationPrice(
                                it.value,
                                ValuationBasis.ENTRY_PRICE
                            )
                        )
                    }
                    ?: Input.Invalid(
                        "valuation price (entry) unavailable"
                    )
        )
    }

    private fun decimal(
        value: Double,
        name: String
    ): Input<BigDecimal> =
        if (value.isFinite()) {
            Input.Known(BigDecimal.valueOf(value))
        } else {
            Input.Invalid("$name is not a finite number")
        }

    /**
     * lots = quantity / lotSize exactly.
     * Non-whole/non-multiple quantities are Invalid; never rounded.
     */
    private fun lotsOf(
        quantity: Double,
        lotSize: Int
    ): Input<BigDecimal> {
        if (!quantity.isFinite()) {
            return Input.Invalid("quantity is not a finite number")
        }

        val q = BigDecimal.valueOf(quantity)

        if (q.stripTrailingZeros().scale() > 0) {
            return Input.Invalid(
                "quantity must be a whole number, got $q"
            )
        }

        val size = BigDecimal(lotSize)

        if (q.remainder(size).signum() != 0) {
            return Input.Invalid(
                "quantity $q is not a whole number of lots of $lotSize"
            )
        }

        return Input.Known(q.divideToIntegralValue(size))
    }
}
