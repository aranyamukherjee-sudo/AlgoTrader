package com.algotrader.app.asi2

import com.algotrader.discovery.candidate.BreakoutCandidateGenerator
import com.algotrader.discovery.pipeline.DiscoveryPipeline
import com.algotrader.discovery.pipeline.DiscoveryRequest
import com.algotrader.discovery.pipeline.WalkForwardConfig
import com.algotrader.discovery.scoring.DiscoveryPolicy
import com.algotrader.discovery.split.DataSplit
import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.InstrumentKind
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.MarketScope
import java.time.Instant

object Asi2DeviceHarness {

    data class Result(
        val runKey: String,
        val candidates: Int,
        val promising: Int,
        val walkForwardCandidates: Int,
        val simpleFoldCount: Int,
        val simpleAllPassed: Boolean,
        val simpleAggregateScore: String,
        val foldDiagnostics: List<String>
    )

    private val instrument = Instrument("FIXTURE-FUT", "NSE")

    private val index = InstrumentRef(
        Instrument("FIXTURE-IDX", "NSE"),
        InstrumentKind.INDEX
    )

    private val future = InstrumentRef(
        instrument,
        InstrumentKind.FUTURES,
        underlying = "FIXTURE",
        contractId = "FIXTURE-FUT-1"
    )

    private val market = MarketScope(
        signalSource = index,
        tradeTarget = future
    )

    private fun at(i: Int): Instant =
        Instant.parse("2026-01-05T04:00:00Z")
            .plusSeconds(15L * 60L * i)

    private fun candle(
        i: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double
    ) = Candle(
        instrument = instrument,
        timeframe = Timeframe.MINUTE_15,
        timestamp = at(i),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1000.0
    )

    private fun fromCloses(closes: List<Double>): List<Candle> {
        var previous = closes.first()

        return closes.mapIndexed { i, close ->
            val open = previous
            previous = close

            candle(
                i = i,
                open = open,
                high = maxOf(open, close) + 0.1,
                low = minOf(open, close) - 0.1,
                close = close
            )
        }
    }

    private val flat =
        List(20) { if (it % 2 == 0) 100.0 else 100.5 }

    private val strongCycle =
        flat +
            listOf(
                102.0, 103.0, 104.0, 105.0, 106.0,
                107.0, 108.0, 109.0, 110.0, 111.0
            ) +
            listOf(
                109.0, 107.0, 105.0, 103.0, 101.0,
                100.0, 100.0, 100.5, 100.0, 100.5
            )

    private fun strongCandles(): List<Candle> =
        fromCloses(
            buildList {
                repeat(30) {
                    addAll(strongCycle)
                }
            }
        )

    fun run(): Result {
        val request = DiscoveryRequest(
            market = market,
            timeframe = Timeframe.MINUTE_15,
            split = DataSplit.chronological(strongCandles()),
            at = Instant.parse("2026-10-06T04:00:00Z"),
            policy = DiscoveryPolicy(
                minTrainTrades = 3,
                minValidationTrades = 2,
                minHoldoutTrades = 3
            ),
            walkForward = WalkForwardConfig(
                trainBars = 120,
                validationBars = 60,
                stepBars = 40,
                embargoBars = 5,
                minFolds = 2
            )
        )

        val result = DiscoveryPipeline(
            BreakoutCandidateGenerator()
        ).run(request)

        val simple = result.candidates.first {
            it.dna.name == "Breakout 10 / exit 5"
        }

        val wf = requireNotNull(simple.walkForward) {
            "ASI-2 walk-forward result was not produced"
        }

        val diagnostics = wf.folds.map { fold ->
            "Fold ${fold.foldIndex}: " +
                "trainTrades=${fold.train.trades}, " +
                "trainNet=${fold.train.costAdjustedNetProfit}, " +
                "validationTrades=${fold.validation.trades}, " +
                "validationNet=${fold.validation.costAdjustedNetProfit}, " +
                "passed=${fold.gates.all { it.passed }}"
        }

        return Result(
            runKey = result.runInfo.runKey,
            candidates = result.runInfo.candidatesGenerated,
            promising = result.runInfo.promising,
            walkForwardCandidates = result.candidates.count {
                it.walkForward != null
            },
            simpleFoldCount = wf.foldCount,
            simpleAllPassed = wf.allPassed,
            simpleAggregateScore = wf.aggregateScore?.toString() ?: "null",
            foldDiagnostics = diagnostics
        )
    }
}
