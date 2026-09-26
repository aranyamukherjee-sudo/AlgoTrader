package com.algotrader.strategy

/** Which side(s) of the market a strategy is designed to trade. */
enum class PositionDirection {
    LONG_ONLY,
    SHORT_ONLY,
    LONG_AND_SHORT
}
