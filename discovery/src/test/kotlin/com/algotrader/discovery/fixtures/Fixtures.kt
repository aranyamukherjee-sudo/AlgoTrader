package com.algotrader.discovery.fixtures

import com.algotrader.discovery.pipeline.CandidateRecord
import com.algotrader.discovery.pipeline.DiscoveryRequest
import com.algotrader.discovery.scoring.DiscoveryPolicy
import com.algotrader.discovery.split.DataSplit
import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.InstrumentKind
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.MarketScope
import java.time.Instant

/**
 * FIXTURE ONLY. Everything here is synthetic, deterministic test data built
 * from fixed arithmetic. None of it is, or resembles, real market data, and no
 * result on it says anything about real markets.
 */
object SyntheticCandles {
    val instrument = Instrument("FIXTURE-FUT", "NSE")
    val start: Instant = Instant.parse("2026-01-05T04:00:00Z")

    fun at(i: Int): Instant = start.plusSeconds(15L * 60L * i)

    fun candle(i: Int, open: Double, high: Double, low: Double, close: Double) =
        Candle(instrument, Timeframe.MINUTE_15, at(i), open, high, low, close, 1000.0)

    /** open = previous close; high/low pad the body by 0.1. */
    fun fromCloses(closes: List<Double>): List<Candle> {
        var prev = closes.first()
        return closes.mapIndexed { i, c ->
            val o = prev
            prev = c
            candle(i, o, maxOf(o, c) + 0.1, minOf(o, c) - 0.1, c)
        }
    }

    /** Bars whose open = close = 100 and whose high-low range is exactly the given value. */
    fun rangeBars(ranges: List<Double>): List<Candle> =
        ranges.mapIndexed { i, r -> candle(i, 100.0, 100.0 + r / 2.0, 100.0 - r / 2.0, 100.0) }

    private val flat: List<Double> = List(20) { if (it % 2 == 0) 100.0 else 100.5 }

    /** 40 bars: quiet range, a breakout rally that a breakout rule profits from, then a fade back to 100. */
    val strongCycle: List<Double> = flat +
        listOf(102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 108.0, 109.0, 110.0, 111.0) +
        listOf(109.0, 107.0, 105.0, 103.0, 101.0, 100.0, 100.0, 100.5, 100.0, 100.5)

    /** 40 bars: the same quiet range, but the breakout immediately fails: every breakout trade loses. */
    val trapCycle: List<Double> = flat +
        listOf(102.0, 98.0, 98.0, 98.0, 98.5, 99.0, 99.5, 100.0, 100.0, 100.5) +
        listOf(100.0, 100.5, 100.0, 100.5, 100.0, 100.5, 100.0, 100.5, 100.0, 100.5)

    fun series(vararg blocks: Pair<List<Double>, Int>): List<Candle> =
        fromCloses(blocks.flatMap { (cycle, times) -> List(times) { cycle }.flatten() })

    /** 1200 bars where the breakout edge exists everywhere. */
    val allStrong: List<Candle> by lazy { series(strongCycle to 30) }

    /** 1200 bars where the breakout never works. */
    val allTrap: List<Candle> by lazy { series(trapCycle to 30) }

    /** The edge holds in the training segment, then disappears (validation and holdout are traps). */
    val edgeVanishesInValidation: List<Candle> by lazy { series(strongCycle to 18, trapCycle to 12) }

    /** The edge holds in training and validation, and disappears only in the holdout. */
    val edgeVanishesInHoldout: List<Candle> by lazy { series(strongCycle to 24, trapCycle to 6) }
}

object DiscoveryFixtures {
    val index = InstrumentRef(Instrument("FIXTURE-IDX", "NSE"), InstrumentKind.INDEX)
    val future = InstrumentRef(
        SyntheticCandles.instrument, InstrumentKind.FUTURES, underlying = "FIXTURE", contractId = "FIXTURE-FUT-1"
    )
    val futuresMarket = MarketScope(signalSource = index, tradeTarget = future)
    val indexOnlyMarket = MarketScope(signalSource = index)

    /** Small trade-count minimums so short synthetic series are usable; every other threshold is the default. */
    val relaxedPolicy = DiscoveryPolicy(minTrainTrades = 5, minValidationTrades = 3, minHoldoutTrades = 3)

    val at: Instant = Instant.parse("2026-10-06T04:00:00Z")

    fun request(
        candles: List<Candle>,
        market: MarketScope = futuresMarket,
        policy: DiscoveryPolicy = relaxedPolicy
    ) = DiscoveryRequest(
        market = market,
        timeframe = Timeframe.MINUTE_15,
        split = DataSplit.chronological(candles),
        at = at,
        policy = policy
    )

    fun List<CandidateRecord>.named(name: String): CandidateRecord = first { it.dna.name == name }

    const val SIMPLE = "Breakout 10 / exit 5"
}
