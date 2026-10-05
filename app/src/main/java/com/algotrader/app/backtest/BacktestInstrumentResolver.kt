package com.algotrader.app.backtest

import com.algotrader.app.InstrumentInfo

/**
 * Phase 3 Patch 6 — decides, as pure logic, which instrument/candle source and
 * which accounting lot size a backtest launch uses.
 *
 * - INDEX: the selected index, its existing index "/history" candles and its
 *   existing index lot size (behaviour unchanged). A selected futures contract
 *   is never accepted as an index.
 * - FUTURES: the exact FYERS contract discovered by Patch 5 (never constructed
 *   here), its "/futures/history" candles, and ONLY its authoritative
 *   [InstrumentInfo.contractLotSize]. An unknown lot size is refused, never
 *   defaulted and never borrowed from an index.
 */
object BacktestInstrumentResolver {

    /** Which backend history endpoint the candles for a run come from. */
    enum class HistoryPath(val endpoint: String) {
        INDEX_HISTORY("/history"),
        FUTURES_HISTORY("/futures/history")
    }

    sealed interface Resolution {
        data class Resolved(
            val type: BacktestInstrumentType,
            /** The exact instrument whose candles are backtested. */
            val instrument: InstrumentInfo,
            val historyPath: HistoryPath,
            /** Units per lot used by the engine. */
            val lotSize: Int,
            /** Persisted contract metadata; null for INDEX. */
            val futuresContract: FuturesContractConfig?
        ) : Resolution

        data class Rejected(val reason: String) : Resolution
    }

    const val NO_CONTRACT_MESSAGE =
        "No NIFTY futures contract has been discovered yet. " +
            "Open Home and wait for the futures contract to load."

    const val UNKNOWN_LOT_SIZE_MESSAGE =
        "Futures backtesting needs the contract's authoritative lot size, " +
            "which is not available yet. Rupee P&L is not calculated with an " +
            "unverified lot size, and index lot sizes are not used for futures."

    const val FUTURES_SELECTED_FOR_INDEX_MESSAGE =
        "A futures contract is selected. Select an index on Home to run an " +
            "index backtest, or choose FUTURES."

    fun resolve(
        type: BacktestInstrumentType,
        selected: InstrumentInfo,
        discoveredFutures: InstrumentInfo?
    ): Resolution = when (type) {
        BacktestInstrumentType.INDEX -> resolveIndex(selected)
        BacktestInstrumentType.FUTURES -> resolveFutures(discoveredFutures)
    }

    private fun resolveIndex(selected: InstrumentInfo): Resolution {
        if (selected.isFutures) {
            return Resolution.Rejected(FUTURES_SELECTED_FOR_INDEX_MESSAGE)
        }
        return Resolution.Resolved(
            type = BacktestInstrumentType.INDEX,
            instrument = selected,
            historyPath = HistoryPath.INDEX_HISTORY,
            lotSize = selected.lotSize,
            futuresContract = null
        )
    }

    private fun resolveFutures(contract: InstrumentInfo?): Resolution {
        if (contract == null || !contract.isFutures) {
            return Resolution.Rejected(NO_CONTRACT_MESSAGE)
        }
        val lotSize = contract.contractLotSize
        if (lotSize == null || lotSize < 1) {
            return Resolution.Rejected(UNKNOWN_LOT_SIZE_MESSAGE)
        }
        // A lot size without a stated authoritative source is not verified.
        val source = contract.contractLotSizeSource
        if (source.isNullOrBlank()) {
            return Resolution.Rejected(UNKNOWN_LOT_SIZE_MESSAGE)
        }
        return Resolution.Resolved(
            type = BacktestInstrumentType.FUTURES,
            instrument = contract,
            historyPath = HistoryPath.FUTURES_HISTORY,
            lotSize = lotSize,
            futuresContract = FuturesContractConfig(
                underlying = contract.underlyingSymbol,
                // The exact FYERS symbol returned by /futures/chain.
                contractId = contract.backendSymbol,
                lotSize = lotSize,
                lotSizeSource = source,
                // Carried as returned by the chain; null if it was not supplied.
                expiryEpochSeconds = contract.expiryEpochSeconds
            )
        )
    }

    /**
     * Lot size the BacktestWorker must give the engine for a persisted job.
     * INDEX keeps the existing symbol-based lookup. FUTURES uses only the
     * persisted contract lot size and fails the job if it is missing, instead
     * of silently falling back to 1 (which would turn lots into single units
     * and report wrong rupee P&L).
     */
    fun engineLotSize(
        type: BacktestInstrumentType,
        symbol: String,
        futuresContract: FuturesContractConfig?
    ): Int = when (type) {
        BacktestInstrumentType.INDEX -> BacktestFormat.lotSizeFor(symbol)
        BacktestInstrumentType.FUTURES -> {
            val lot = futuresContract?.lotSize
            check(lot != null && lot >= 1) { UNKNOWN_LOT_SIZE_MESSAGE }
            lot
        }
    }
}
