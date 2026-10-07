package com.algotrader.discovery.pipeline

import com.algotrader.discovery.scoring.CandidateScore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WalkForwardScoringTest {
    @Test
    fun `aggregate score is conservative minimum across folds`() {
        val a = CandidateScore(
            value = 0.80,
            components = mapOf(
                "trade_count" to 0.90,
                "profit_factor" to 0.80,
                "drawdown" to 0.70
            )
        )
        val b = CandidateScore(
            value = 0.60,
            components = mapOf(
                "trade_count" to 0.70,
                "profit_factor" to 0.60,
                "drawdown" to 0.80
            )
        )

        val result =
            com.algotrader.discovery.scoring.DiscoveryScoring.aggregateWalkForward(listOf(a, b))

        assertEquals(0.60, result.value, 1e-9)
        assertEquals(0.70, result.components["trade_count"]!!, 1e-9)
        assertEquals(0.60, result.components["profit_factor"]!!, 1e-9)
        assertEquals(0.70, result.components["drawdown"]!!, 1e-9)
    }
}
