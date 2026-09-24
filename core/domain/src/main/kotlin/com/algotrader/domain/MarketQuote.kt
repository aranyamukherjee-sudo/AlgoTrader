package com.algotrader.domain

import java.time.Instant

data class MarketQuote(
    val instrument: Instrument,
    val timestamp: Instant,
    val lastPrice: Double,
    val change: Double? = null,
    val changePercent: Double? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val previousClose: Double? = null,
    val volume: Double? = null
)
