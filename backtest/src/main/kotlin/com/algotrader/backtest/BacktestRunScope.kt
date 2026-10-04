package com.algotrader.backtest

/**
 * Which persisted jobs belong to the same logical backtest launch.
 *
 * Every job of one launch shares a runId (a standard launch: one job per
 * strategy; an out-of-sample launch: one In-Sample and one Out-of-Sample job
 * per strategy). Cancelling or deleting any member acts on the whole launch.
 * Legacy jobs have no runId and are their own one-job launch.
 *
 * Generic and pure so the grouping rule is unit-tested without Android.
 */
object BacktestRunScope {

    fun <T> members(
        all: List<T>,
        target: T,
        idOf: (T) -> String,
        runIdOf: (T) -> String?
    ): List<T> {
        val runId = runIdOf(target) ?: return listOf(target)
        val group = all.filter { runIdOf(it) == runId }
        return if (group.any { idOf(it) == idOf(target) }) group else group + target
    }
}
