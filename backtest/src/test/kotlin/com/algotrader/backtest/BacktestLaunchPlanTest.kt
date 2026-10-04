package com.algotrader.backtest

import com.algotrader.domain.Candle
import com.algotrader.strategyengine.StrategyConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BacktestLaunchPlanTest {

    private fun series(n: Int): List<Candle> = (0 until n).map { testCandle(it, close = 100.0 + it) }

    private val configs = listOf("moving_average_crossover", "rsi", "macd").map { StrategyConfiguration(it) }

    private fun planned(n: Int, oos: Boolean): List<BacktestWorkUnit> {
        val outcome = BacktestLaunchPlan.plan(configs, series(n), oos)
        return assertIs<BacktestLaunchPlan.Outcome.Planned>(outcome).units
    }

    @Test
    fun `standard mode makes one FULL unit per strategy on all candles`() {
        val input = series(120)
        val units = assertIs<BacktestLaunchPlan.Outcome.Planned>(
            BacktestLaunchPlan.plan(configs, input, outOfSample = false)
        ).units
        assertEquals(3, units.size)
        assertTrue(units.all { it.sample == BacktestSample.FULL })
        assertTrue(units.all { it.candles == input })
        assertEquals(configs, units.map { it.configuration })
    }

    @Test
    fun `standard mode is not subject to the out-of-sample minimum`() {
        assertEquals(3, planned(60, oos = false).size)
    }

    @Test
    fun `out-of-sample makes two units per strategy`() {
        val units = planned(200, oos = true)
        assertEquals(6, units.size)
        for (c in configs) {
            val mine = units.filter { it.configuration == c }
            assertEquals(
                setOf(BacktestSample.IN_SAMPLE, BacktestSample.OUT_OF_SAMPLE),
                mine.map { it.sample }.toSet()
            )
            assertEquals(2, mine.size)
        }
        assertTrue(units.none { it.sample == BacktestSample.FULL })
    }

    @Test
    fun `in-sample units never receive out-of-sample candles and vice versa`() {
        val input = series(200)
        val units = assertIs<BacktestLaunchPlan.Outcome.Planned>(
            BacktestLaunchPlan.plan(configs, input, outOfSample = true)
        ).units
        val isTimes = units.filter { it.sample == BacktestSample.IN_SAMPLE }
            .flatMap { u -> u.candles.map { it.timestamp } }.toSet()
        val oosTimes = units.filter { it.sample == BacktestSample.OUT_OF_SAMPLE }
            .flatMap { u -> u.candles.map { it.timestamp } }.toSet()
        assertTrue(isTimes.intersect(oosTimes).isEmpty())
        assertEquals(input.map { it.timestamp }.toSet(), isTimes + oosTimes)
        for (u in units) {
            when (u.sample) {
                BacktestSample.IN_SAMPLE -> assertEquals(input.subList(0, 140), u.candles)
                BacktestSample.OUT_OF_SAMPLE -> assertEquals(input.subList(140, 200), u.candles)
                BacktestSample.FULL -> error("unexpected FULL unit")
            }
        }
    }

    @Test
    fun `rejected split plans nothing`() {
        assertIs<BacktestLaunchPlan.Outcome.Rejected>(BacktestLaunchPlan.plan(configs, series(166), true))
        val unordered = series(200).reversed()
        assertIs<BacktestLaunchPlan.Outcome.Rejected>(BacktestLaunchPlan.plan(configs, unordered, true))
    }

    @Test
    fun `each segment can be backtested independently with no shared state`() {
        val input = (0 until 200).map { testCandle(it, close = 100.0 + 10.0 * Math.sin(it / 4.0)) }
        val units = assertIs<BacktestLaunchPlan.Outcome.Planned>(
            BacktestLaunchPlan.plan(listOf(configs.first()), input, true)
        ).units
        val runner = StrategyBacktestRunner()
        val isResult = runner.run(units[0].configuration, units[0].candles).copy(sample = units[0].sample)
        val oosResult = runner.run(units[1].configuration, units[1].candles).copy(sample = units[1].sample)
        // The OOS run equals a fresh run on the OOS candles alone (no carried state).
        val freshOos = runner.run(units[1].configuration, input.subList(140, 200))
        assertEquals(freshOos.finalEquity, oosResult.finalEquity)
        assertEquals(freshOos.trades, oosResult.trades)
        assertEquals(BacktestSample.IN_SAMPLE, isResult.sample)
        assertEquals(BacktestSample.OUT_OF_SAMPLE, oosResult.sample)
        assertTrue(freshOos.trades.isNotEmpty(), "test series should trade")
        assertEquals(60, oosResult.equityCurve.size)
    }
}
