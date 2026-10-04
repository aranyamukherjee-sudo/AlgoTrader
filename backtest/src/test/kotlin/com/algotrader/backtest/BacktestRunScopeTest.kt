package com.algotrader.backtest

import kotlin.test.Test
import kotlin.test.assertEquals

class BacktestRunScopeTest {

    private data class J(val id: String, val runId: String?)

    private fun members(all: List<J>, target: J) =
        BacktestRunScope.members(all, target, { it.id }, { it.runId }).map { it.id }.toSet()

    @Test
    fun allJobsOfOneRunAreMembers() {
        val a1 = J("a-is", "A"); val a2 = J("a-oos", "A")
        val b1 = J("b-is", "B")
        assertEquals(setOf("a-is", "a-oos"), members(listOf(a1, a2, b1), a2))
    }

    @Test
    fun otherRunsAreNotMembers() {
        val a = J("a", "A"); val b = J("b", "B")
        assertEquals(setOf("b"), members(listOf(a, b), b))
    }

    @Test
    fun legacyJobWithoutRunIdIsItsOwnGroup() {
        val l1 = J("l1", null); val l2 = J("l2", null)
        assertEquals(setOf("l1"), members(listOf(l1, l2), l1))
    }

    @Test
    fun targetMissingFromListIsStillIncluded() {
        val a = J("a", "A"); val ghost = J("ghost", "A")
        assertEquals(setOf("a", "ghost"), members(listOf(a), ghost))
    }
}
