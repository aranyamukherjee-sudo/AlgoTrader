package com.algotrader.backtest

import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.SignalType

/**
 * Translates a strategy signal plus the current position into the *target*
 * position for the next bar. This is where "repeated BUY signals while
 * already LONG" is turned into "remain long" instead of a new entry, and
 * where reversal behaviour is decided.
 *
 * Policy:
 * - HOLD never changes the current position.
 * - BUY: closing an existing SHORT is always allowed (BUY means "flat a
 *   short" per the existing [com.algotrader.strategy.Strategy] signal
 *   convention). Opening a *new* LONG — from flat, or right after
 *   flattening a short — only happens if the strategy's [PositionDirection]
 *   allows going long. An existing LONG is left alone: a repeated BUY does
 *   not pyramid.
 * - SELL is the mirror of BUY for closing a LONG / opening a SHORT.
 *
 * Returns the target direction, or null for flat.
 */
internal fun resolveTargetDirection(
    signal: SignalType,
    current: TradeDirection?,
    direction: PositionDirection
): TradeDirection? {
    val canGoLong = direction == PositionDirection.LONG_ONLY || direction == PositionDirection.LONG_AND_SHORT
    val canGoShort = direction == PositionDirection.SHORT_ONLY || direction == PositionDirection.LONG_AND_SHORT

    return when (signal) {
        SignalType.HOLD -> current

        SignalType.BUY -> when (current) {
            TradeDirection.LONG -> TradeDirection.LONG
            TradeDirection.SHORT -> if (canGoLong) TradeDirection.LONG else null
            null -> if (canGoLong) TradeDirection.LONG else null
        }

        SignalType.SELL -> when (current) {
            TradeDirection.SHORT -> TradeDirection.SHORT
            TradeDirection.LONG -> if (canGoShort) TradeDirection.SHORT else null
            null -> if (canGoShort) TradeDirection.SHORT else null
        }
    }
}
