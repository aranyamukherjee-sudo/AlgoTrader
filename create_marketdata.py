from pathlib import Path

base = Path("core/marketdata/src/main/kotlin/com/algotrader/marketdata")
base.mkdir(parents=True, exist_ok=True)

files = {
    "MarketDataSource.kt": """package com.algotrader.marketdata

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
""",

    "CandleRepository.kt": """package com.algotrader.marketdata

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
""",

    "InMemoryMarketDataSource.kt": """package com.algotrader.marketdata

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
""",

    "InMemoryCandleRepository.kt": """package com.algotrader.marketdata

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
"""
}

for filename, content in files.items():
    path = base / filename
    path.write_text(content)
    print(f"Created: {path}")

print(f"Created {len(files)} market-data files.")
