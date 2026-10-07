package com.algotrader.intelligence.setup

import com.algotrader.intelligence.structure.MarketStructureState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SetupModelTest {

    @Test
    fun `buffer rule resolves absolute when ATR is disabled`() {
        assertEquals(2.0, BufferRule(absolute = 2.0).resolve(null))
    }

    @Test
    fun `buffer rule never invents ATR`() {
        assertNull(
            BufferRule(
                absolute = 2.0,
                atrMultiplier = 1.5,
                atrFallback = AtrFallback.UNAVAILABLE
            ).resolve(null)
        )
    }

    @Test
    fun `buffer rule can fall back to absolute`() {
        assertEquals(
            2.0,
            BufferRule(
                absolute = 2.0,
                atrMultiplier = 1.5,
                atrFallback = AtrFallback.USE_ABSOLUTE
            ).resolve(null)
        )
    }

    @Test
    fun `qualification rejects no required criteria`() {
        assertFailsWith<IllegalArgumentException> {
            QualificationConfig(requireTrendAlignment = false)
        }
    }

    @Test
    fun `lifecycle rejects illegal transition`() {
        assertEquals(
            false,
            SetupLifecycle.canTransition(
                SetupStage.BROKEN,
                SetupStage.RETEST_HELD
            )
        )
    }

    @Test
    fun `lifecycle allows terminal transitions`() {
        assertEquals(
            true,
            SetupLifecycle.canTransition(
                SetupStage.RETEST_HELD,
                SetupStage.CONTINUED
            )
        )
        assertEquals(
            true,
            SetupLifecycle.canTransition(
                SetupStage.BROKEN,
                SetupStage.FAILED
            )
        )
    }

    @Test
    fun `qualification status follows required checks`() {
        val checks = listOf(
            QualificationCheck(
                QualificationCriterion.TREND_ALIGNMENT,
                required = true,
                status = CheckStatus.PASS,
                observed = null,
                requiredValue = null,
                trendState = MarketStructureState.RANGE
            )
        )
        assertEquals(
            QualificationStatus.QUALIFIED,
            BreakoutQualification(
                QualificationStatus.QUALIFIED,
                checks
            ).status
        )
    }
}
