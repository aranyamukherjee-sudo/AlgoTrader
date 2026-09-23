package com.algotrader.strategy

import com.algotrader.domain.Instrument
import java.time.Instant

data class Signal(
    val instrument: Instrument,
    val type: SignalType,
    val timestamp: Instant,
    val confidence: Double = 0.0,
    val reason: String = ""
)
