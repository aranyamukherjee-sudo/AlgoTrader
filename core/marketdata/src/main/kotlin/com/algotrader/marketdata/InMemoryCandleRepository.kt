package com.algotrader.marketdata

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe

class InMemoryCandleRepository : CandleRepository {

    private val candles = mutableListOf<Candle>()

    override fun save(candle: Candle) {
        candles.add(candle)
    }

    override fun saveAll(candles: List<Candle>) {
        this.candles.addAll(candles)
    }

    override fun find(
        instrument: Instrument,
        timeframe: Timeframe
    ): List<Candle> {
        return candles.filter {
            it.instrument == instrument &&
            it.timeframe == timeframe
        }
    }

    override fun clear() {
        candles.clear()
    }
}
