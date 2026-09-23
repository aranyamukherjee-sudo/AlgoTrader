package com.algotrader.marketdata

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe

interface CandleRepository {

    fun save(candle: Candle)

    fun saveAll(candles: List<Candle>)

    fun find(
        instrument: Instrument,
        timeframe: Timeframe
    ): List<Candle>

    fun clear()
}
