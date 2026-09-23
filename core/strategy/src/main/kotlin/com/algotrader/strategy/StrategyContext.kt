package com.algotrader.strategy

import com.algotrader.domain.Candle
import com.algotrader.domain.Portfolio

data class StrategyContext(
    val candles: List<Candle>,
    val portfolio: Portfolio
)
