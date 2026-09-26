package com.algotrader.backtest

import java.time.Instant

/**
 * Mark-to-market account equity at one candle: initial capital, plus
 * realized P&L from all trades closed so far, plus the unrealized P&L of
 * any position still open at this candle's close (zero if flat).
 */
data class EquityPoint(
    val index: Int,
    val timestamp: Instant,
    val equity: Double
)
