package com.algotrader.marketdata

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import java.time.Instant

class InMemoryMarketDataSource(
    private val repository: CandleRepository
) : MarketDataSource {

    override fun candles(
        instrument: Instrument,
        timeframe: Timeframe,
        from: Instant,
        to: Instant
    ): List<Candle> {
        return repository.find(instrument, timeframe)
            .filter { candle ->
                candle.timestamp >= from &&
                candle.timestamp <= to
            }
            .sortedBy { it.timestamp }
    }
}
