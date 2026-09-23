package com.algotrader.marketdata

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import java.time.Instant

interface MarketDataSource {

    fun candles(
        instrument: Instrument,
        timeframe: Timeframe,
        from: Instant,
        to: Instant
    ): List<Candle>
}
