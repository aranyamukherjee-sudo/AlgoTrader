package com.algotrader.domain

import java.time.Instant

data class Candle(
    val instrument: Instrument,
    val timeframe: Timeframe,
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)
