package com.algotrader.discovery.scoring

import com.algotrader.backtest.BacktestTrade
import com.algotrader.backtest.TradeDirection
import com.algotrader.discovery.evaluation.SegmentEvaluation
import com.algotrader.discovery.evaluation.SegmentEvaluator
import com.algotrader.discovery.fixtures.SyntheticCandles
import com.algotrader.discovery.split.SegmentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryScoringTest {

    private val policy = DiscoveryPolicy() // defaults: 30/15/10 trades, PF 1.2, DD 25%, consistency 0.5, retention 0.5

    /** A hand-made evaluation (FIXTURE numbers, not market results). */
    private fun eval(
        role: SegmentRole = SegmentRole.TRAIN,
        trades: Int = 40,
        net: Double = 500.0,
        dd: Double = 5.0,
        pf: Double? = 1.8,
        avg: Double = 10.0,
        consistency: Double = 0.75
    ) = SegmentEvaluation(
        role = role, bars = 500, trades = trades, netProfit = net, returnPercent = net / 1000.0,
        maxDrawdownPercent = dd, profitFactor = pf, winRate = 0.55, averageTradePnl = avg,
        periodConsistency = consistency
    )

    private fun failed(gates: List<Gate>) = gates.filter { !it.passed }.map { it.name }

    // ---- gates ----

    @Test
    fun `a healthy training result passes every gate`() {
        assertEquals(emptyList(), failed(DiscoveryGates.train(eval(), policy)))
    }

    @Test
    fun `each training gate fails on its own`() {
        assertEquals(listOf("trade_count"), failed(DiscoveryGates.train(eval(trades = 29), policy)))
        assertEquals(listOf("net_profit", "profit_factor"), failed(DiscoveryGates.train(eval(net = -1.0, pf = 0.9), policy)))
        assertEquals(listOf("profit_factor"), failed(DiscoveryGates.train(eval(pf = 1.1), policy)))
        assertEquals(listOf("drawdown"), failed(DiscoveryGates.train(eval(dd = 25.1), policy)))
        assertEquals(listOf("consistency"), failed(DiscoveryGates.train(eval(consistency = 0.25), policy)))
    }

    @Test
    fun `no losing trades means profit factor is undefined and passes`() {
        assertEquals(emptyList(), failed(DiscoveryGates.train(eval(pf = null), policy)))
    }

    @Test
    fun `total profit alone never passes a candidate`() {
        // huge profit from two trades: still rejected on trade count
        val lucky = eval(trades = 2, net = 1_000_000.0, avg = 500_000.0)
        assertTrue(failed(DiscoveryGates.train(lucky, policy)).contains("trade_count"))
        // huge profit with a deep drawdown: rejected on drawdown
        val reckless = eval(net = 1_000_000.0, dd = 60.0)
        assertTrue(failed(DiscoveryGates.train(reckless, policy)).contains("drawdown"))
    }

    @Test
    fun `validation requires the edge to carry over from training`() {
        val train = eval(avg = 10.0)
        val keeps = eval(SegmentRole.VALIDATION, trades = 20, avg = 6.0)
        val fades = eval(SegmentRole.VALIDATION, trades = 20, avg = 4.0)
        assertEquals(emptyList(), failed(DiscoveryGates.validation(train, keeps, policy)))
        assertEquals(listOf("retention"), failed(DiscoveryGates.validation(train, fades, policy)))
        assertEquals(0.6, DiscoveryGates.retention(train, keeps)!!, 1e-9)
        // retention is undefined when training had no positive average trade
        assertNull(DiscoveryGates.retention(eval(avg = 0.0), keeps))
        assertEquals(listOf("retention"), failed(DiscoveryGates.validation(eval(avg = 0.0), keeps, policy)))
    }

    @Test
    fun `holdout checks trades, net result and drawdown`() {
        val ok = eval(SegmentRole.HOLDOUT, trades = 10, net = 1.0, dd = 5.0)
        assertEquals(emptyList(), failed(DiscoveryGates.holdout(ok, policy)))
        assertEquals(listOf("trade_count"), failed(DiscoveryGates.holdout(ok.copy(trades = 9), policy)))
        assertEquals(listOf("net_profit"), failed(DiscoveryGates.holdout(ok.copy(netProfit = 0.0), policy)))
        assertEquals(listOf("drawdown"), failed(DiscoveryGates.holdout(ok.copy(maxDrawdownPercent = 30.0), policy)))
    }

    @Test
    fun `every gate carries its phase and the numbers behind it`() {
        val gates = DiscoveryGates.train(eval(trades = 3), policy)
        assertTrue(gates.all { it.phase == SegmentRole.TRAIN })
        assertTrue(gates.first { it.name == "trade_count" }.detail.contains("3 trades"))
    }

    // ---- score ----

    @Test
    fun `score is the plain average of five 0 to 1 components`() {
        val train = eval(avg = 10.0)
        val validation = eval(SegmentRole.VALIDATION, trades = 30, pf = 2.0, dd = 0.0, consistency = 1.0, avg = 10.0)
        val score = DiscoveryScoring.score(train, validation, policy)

        assertEquals(setOf("trade_count", "profit_factor", "drawdown", "consistency", "retention"), score.components.keys)
        assertTrue(score.components.values.all { it in 0.0..1.0 })
        assertEquals(score.components.values.average(), score.value, 1e-12)
        assertEquals(1.0, score.value, 1e-12) // every component is at its maximum here
    }

    @Test
    fun `ranking is not by total profit`() {
        val train = eval(avg = 10.0)
        val bigButFragile = eval(SegmentRole.VALIDATION, trades = 30, net = 100_000.0, pf = 1.3, dd = 20.0, consistency = 0.5, avg = 10.0)
        val smallButSolid = eval(SegmentRole.VALIDATION, trades = 30, net = 100.0, pf = 2.5, dd = 1.0, consistency = 1.0, avg = 10.0)
        val a = DiscoveryScoring.score(train, bigButFragile, policy)
        val b = DiscoveryScoring.score(train, smallButSolid, policy)
        assertTrue(b.value > a.value)
    }

    @Test
    fun `retention component is capped and zero when undefined`() {
        val train = eval(avg = 10.0)
        val better = eval(SegmentRole.VALIDATION, trades = 30, avg = 30.0)
        assertEquals(1.0, DiscoveryScoring.score(train, better, policy).components.getValue("retention"))
        assertEquals(0.0, DiscoveryScoring.score(eval(avg = 0.0), better, policy).components.getValue("retention"))
    }

    @Test
    fun `invalid policies and scores are rejected`() {
        assertFailsWith<IllegalArgumentException> { DiscoveryPolicy(minTrainTrades = 0) }
        assertFailsWith<IllegalArgumentException> { DiscoveryPolicy(minProfitFactor = 0.9) }
        assertFailsWith<IllegalArgumentException> { DiscoveryPolicy(maxDrawdownPercent = 0.0) }
        assertFailsWith<IllegalArgumentException> { DiscoveryPolicy(minConsistency = 1.5) }
        assertFailsWith<IllegalArgumentException> { DiscoveryPolicy(maxCandidates = 0) }
        assertFailsWith<IllegalArgumentException> { DiscoveryPolicy(maxConfidence = 1.5) }
        assertFailsWith<IllegalArgumentException> { CandidateScore(1.2, emptyMap()) }
    }

    // ---- period consistency ----

    private fun trade(exitIndex: Int, pnl: Double) = BacktestTrade(
        direction = TradeDirection.LONG,
        entryIndex = 0, entryTimestamp = SyntheticCandles.at(0), entryPrice = 100.0,
        exitIndex = exitIndex, exitTimestamp = SyntheticCandles.at(exitIndex), exitPrice = 100.0 + pnl,
        quantity = 1.0
    )

    @Test
    fun `period consistency counts profitable time buckets`() {
        // 100 bars, 4 buckets of 25: +5 (bar 10), -3 (bar 30), +2 (bar 60), +1 (bar 99)
        val trades = listOf(trade(10, 5.0), trade(30, -3.0), trade(60, 2.0), trade(99, 1.0))
        assertEquals(0.75, SegmentEvaluator.periodConsistency(trades, 100, 4))
        // two trades in one bucket net out
        assertEquals(0.0, SegmentEvaluator.periodConsistency(listOf(trade(10, 5.0), trade(12, -6.0)), 100, 4))
        // empty buckets are not profitable
        assertEquals(0.0, SegmentEvaluator.periodConsistency(emptyList(), 100, 4))
        assertFalse(SegmentEvaluator.periodConsistency(trades, 100, 4) > 0.75)
    }
}
