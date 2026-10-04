package com.algotrader.backtest

/**
 * Why a backtest trade was closed. Only reasons the engine genuinely
 * produces are listed — there is no stop-loss / target / time exit in the
 * engine today, so none are represented here.
 */
enum class ExitReason {
    /**
     * Closed because the strategy emitted an opposing signal: SELL closes a
     * long, BUY closes a short. Executed at the next bar's open.
     */
    STRATEGY_SIGNAL,

    /** Still open after the last bar; force-closed at the final bar's close. */
    END_OF_DATA
}
