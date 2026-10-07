package com.algotrader.discovery.pipeline

import com.algotrader.discovery.candidate.BreakoutCandidateGenerator
import com.algotrader.discovery.fixtures.DiscoveryFixtures
import com.algotrader.discovery.fixtures.DiscoveryFixtures.SIMPLE
import com.algotrader.discovery.fixtures.DiscoveryFixtures.named
import com.algotrader.discovery.fixtures.SyntheticCandles
import com.algotrader.discovery.split.SegmentRole
import com.algotrader.discovery.scoring.DiscoveryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkForwardPipelineTest {

    private fun enabledRequest() =
        DiscoveryFixtures.request(
            SyntheticCandles.allStrong
        ).copy(
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

    @Test
    fun `walk-forward is opt-in`() {
        val request = DiscoveryFixtures.request(
            SyntheticCandles.allStrong
        )

        val result = DiscoveryPipeline(
            BreakoutCandidateGenerator()
        ).run(request)

        assertTrue(result.candidates.isNotEmpty())
        assertTrue(result.phaseLog.none { it.foldIndex != null })
        assertTrue(result.candidates.all { it.walkForward == null })
    }

    @Test
    fun `walk-forward configuration changes deterministic run key`() {
        val base = DiscoveryFixtures.request(
            SyntheticCandles.allStrong
        )

        val off = DiscoveryPipeline(
            BreakoutCandidateGenerator()
        ).run(base)

        val on = DiscoveryPipeline(
            BreakoutCandidateGenerator()
        ).run(
            base.copy(
                walkForward = WalkForwardConfig(
                    trainBars = 120,
                    validationBars = 60,
                    stepBars = 60,
                    embargoBars = 5,
                    minFolds = 2
                )
            )
        )

        assertTrue(off.runInfo.runKey != on.runInfo.runKey)
        assertTrue(on.runInfo.runKey.isNotBlank())
    }

    @Test
    fun `enabled walk-forward evaluates actual folds and records the fold evaluation`() {
        val result = DiscoveryPipeline(
            BreakoutCandidateGenerator()
        ).run(enabledRequest())

        val simple = result.candidates.named(SIMPLE)
        val walkForward = simple.walkForward

        assertNotNull(walkForward)
        assertTrue(
            walkForward!!.foldCount >= 2,
            "WF fold diagnostics: " + walkForward.folds.joinToString(" | ") { fold ->
                "fold=${fold.foldIndex}, " +
                    "trainTrades=${fold.train.trades}, " +
                    "trainNet=${fold.train.costAdjustedNetProfit}, " +
                    "trainConsistency=${fold.train.periodConsistency}, " +
                    "valTrades=${fold.validation.trades}, " +
                    "valNet=${fold.validation.costAdjustedNetProfit}, " +
                    "valConsistency=${fold.validation.periodConsistency}, " +
                    "gates=${fold.gates.joinToString(";") { gate ->
                        "${gate.phase}/${gate.name}=${gate.passed}[${gate.detail}]"
                    }}"
            }
        )
        assertTrue(walkForward.allPassed)
        assertNotNull(walkForward.aggregateScore)

        val foldRuns = result.phaseLog
            .filter { it.foldIndex != null }
            .filter { it.candidate == simple.dna.id }

        assertTrue(foldRuns.isNotEmpty())

        val trainRuns = foldRuns.filter { it.phase == SegmentRole.TRAIN }
        val validationRuns = foldRuns.filter { it.phase == SegmentRole.VALIDATION }

        assertEquals(walkForward.foldCount, trainRuns.size)
        assertEquals(walkForward.foldCount, validationRuns.size)

        assertEquals(
            (0 until walkForward.foldCount).toList(),
            trainRuns.map { it.foldIndex }
        )
        assertEquals(
            (0 until walkForward.foldCount).toList(),
            validationRuns.map { it.foldIndex }
        )

        assertTrue(trainRuns.all { it.bars == 120 })
        assertTrue(validationRuns.all { it.bars == 60 })

        assertTrue(
            walkForward.folds.all { fold ->
                fold.train.role == SegmentRole.TRAIN &&
                    fold.validation.role == SegmentRole.VALIDATION &&
                    fold.gates.isNotEmpty() &&
                    fold.score != null &&
                    fold.passed
            }
        )
    }
}
