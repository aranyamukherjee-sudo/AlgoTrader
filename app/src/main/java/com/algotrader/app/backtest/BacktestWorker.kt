package com.algotrader.app.backtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.algotrader.app.MainActivity
import com.algotrader.app.R
import com.algotrader.app.notification.AltrixaNotifications
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.backtest.BacktestResult
import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.StrategyBacktestRunner
import com.algotrader.strategyengine.StrategyFactory

/**
 * Owns the lifecycle of a persisted backtest job.
 *
 * It deliberately has no dependency on Activity/View state.
 */
private const val FOREGROUND_NOTIFICATION_ID = 4101
private const val FOREGROUND_CHANNEL_ID = "altrixa_backtest_running"
private const val FOREGROUND_CHANNEL_NAME = "Backtest Running"

class BacktestWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    private val store = BacktestJobStore(appContext)

    override fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure(
                workDataOf(KEY_ERROR to "Missing backtest job ID")
            )

        val job = store.get(jobId)
            ?: return Result.failure(
                workDataOf(KEY_ERROR to "Backtest job not found: $jobId")
            )

        // The user cancelled this launch while the job was still queued. Do not
        // start it, and leave its persisted CANCELLED state exactly as it is.
        if (job.status == BacktestJobStore.Status.CANCELLED) {
            return Result.failure(workDataOf(KEY_ERROR to "Cancelled"))
        }

        return try {
            setForegroundAsync(
                createForegroundInfo(
                    "Preparing backtest…",
                    0
                )
            ).get()

            update(
                jobId,
                BacktestJobStore.Status.PREPARING,
                5,
                "Preparing historical data"
            )

            val candles = store.getCandles(jobId)
            require(candles.size >= 50) {
                "Not enough persisted candles for the backtest (${candles.size})"
            }

            update(
                jobId,
                BacktestJobStore.Status.RUNNING,
                10,
                "Loading strategies"
            )

            val factory = StrategyFactory()
            val runner = StrategyBacktestRunner(factory)

            val config = BacktestConfig(
                initialCapital = job.initialCapital,
                positionSizing = job.positionSizing,
                lotSize = BacktestInstrumentResolver.engineLotSize(
                    job.instrumentType,
                    job.instrumentSymbol,
                    job.futuresContract
                )
            )

            val checkpoint = store.getCheckpoint(jobId)

            val startedAtMillis =
                checkpoint?.startedAtMillis
                    ?: System.currentTimeMillis()

            val results = checkpoint
                ?.results
                ?.toMutableList()
                ?: mutableListOf()

            val total = job.strategies.size
            val completedCount = results.size

            require(completedCount <= total) {
                "Backtest checkpoint is invalid: $completedCount/$total strategies completed"
            }

            if (completedCount > 0) {
                update(
                    jobId,
                    BacktestJobStore.Status.RUNNING,
                    calculateOverallProgress(
                        completedCount,
                        total,
                        100
                    ),
                    "Resuming · ${completedCount}/${total} strategies complete · ETA ${formatEta(
                        estimateRemainingMillis(
                            startedAtMillis,
                            completedCount,
                            total
                        )
                    )}"
                )
            }

            job.strategies.forEachIndexed { index, strategyConfiguration ->

                // Completed strategies are persisted in the checkpoint.
                // Skip them after a WorkManager retry.
                if (index < completedCount) {
                    return@forEachIndexed
                }

                if (isStopped) {
                    // WorkManager was stopped because the user cancelled/deleted
                    // the launch (not an OS interruption): do not retry it.
                    throwIfCancelled(jobId)
                    store.update(
                        jobId,
                        BacktestJobStore.Status.RUNNING,
                        job.progress,
                        "Interrupted by WorkManager; waiting for retry"
                    )
                    return Result.retry()
                }

                val strategyName =
                    displayStrategyName(strategyConfiguration.strategyId)

                update(
                    jobId,
                    BacktestJobStore.Status.RUNNING,
                    calculateOverallProgress(index, total, 0),
                    "Processing $strategyName · ETA ${formatEta(
                        estimateRemainingMillis(
                            startedAtMillis,
                            index,
                            total
                        )
                    )}"
                )

                val result = runner.run(
                    configuration = strategyConfiguration,
                    candles = candles,
                    backtestConfig = config
                ) { processed, candleTotal ->

                    if (isStopped) {
                        throwIfCancelled(jobId)
                        return@run
                    }

                    val localProgress = if (candleTotal <= 0) {
                        100
                    } else {
                        ((processed.toDouble() / candleTotal.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                    }

                    val overallProgress =
                        calculateOverallProgress(
                            index,
                            total,
                            localProgress
                        )

                    val etaMillis = estimateRemainingMillis(
                        startedAtMillis,
                        index,
                        total,
                        localProgress
                    )

                    update(
                        jobId,
                        BacktestJobStore.Status.RUNNING,
                        overallProgress,
                        "Processing $strategyName · $processed/$candleTotal candles · ETA ${formatEta(etaMillis)}"
                    )
                }

                // Tag the result with this job's segment (FULL for standard jobs).
                results += result.copy(sample = job.sample)

                android.util.Log.i(
                    "ALTRIXA_BACKTEST",
                    "RESULT BEFORE SAVE: strategy=$strategyName " +
                        "trades=${result.trades.size} " +
                        "equityCurve=${result.equityCurve.size} " +
                        "finalEquity=${result.finalEquity} " +
                        "totalTrades=${result.metrics.totalTrades}"
                )

                // Durable checkpoint AFTER the strategy has fully completed.
                // If WorkManager interrupts later, completed strategies will
                // not be rerun.
                store.saveCheckpoint(
                    jobId = jobId,
                    startedAtMillis = startedAtMillis,
                    results = results
                )

                val completedPercent =
                    calculateOverallProgress(
                        index + 1,
                        total,
                        0
                    )

                val remainingEta = estimateRemainingMillis(
                    startedAtMillis,
                    index + 1,
                    total
                )

                update(
                    jobId,
                    BacktestJobStore.Status.RUNNING,
                    completedPercent,
                    "Completed $strategyName · ${index + 1}/$total · ETA ${formatEta(remainingEta)}"
                )
            }

            update(
                jobId,
                BacktestJobStore.Status.CALCULATING,
                90,
                "Calculating performance · ETA <1m"
            )

            require(results.isNotEmpty()) {
                "No backtest results were produced"
            }

            update(
                jobId,
                BacktestJobStore.Status.SAVING,
                95,
                "Saving backtest results"
            )

            update(
                jobId,
                BacktestJobStore.Status.SAVING,
                95,
                "Saving results: starting"
            )
            android.util.Log.i(
                "ALTRIXA_BACKTEST",
                "ALL RESULTS BEFORE SAVE: count=${results.size} " +
                    results.joinToString(" | ") { r ->
                        "${r.strategyName}:trades=${r.trades.size},equity=${r.equityCurve.size}"
                    }
            )

            val futuresAccounting = try {
                FuturesBacktestAccounting.forJob(job, results)
            } catch (e: Exception) {
                // Accounting is diagnostic metadata only. It must never
                // prevent successful engine results from being persisted.
                FuturesBacktestAccounting.failureForJob(
                    job,
                    results,
                    e
                )
            }

            store.saveResults(
                jobId,
                results,
                futuresAccounting
            )

            android.util.Log.i(
                "ALTRIXA_BACKTEST",
                "SAVE RESULTS RETURNED SUCCESSFULLY: jobId=$jobId"
            )

            update(
                jobId,
                BacktestJobStore.Status.SAVING,
                96,
                "Saving results: finished"
            )

            update(
                jobId,
                BacktestJobStore.Status.SAVING,
                97,
                "Clearing checkpoint: starting"
            )

            store.clearCheckpoint(jobId)

            update(
                jobId,
                BacktestJobStore.Status.SAVING,
                98,
                "Clearing checkpoint: finished"
            )

            update(
                jobId,
                BacktestJobStore.Status.SAVING,
                99,
                "Completing backtest"
            )

            val completed = store.update(
                jobId = jobId,
                status = BacktestJobStore.Status.COMPLETED,
                progress = 100,
                currentStep = "Complete",
                errorMessage = null
            )

            // A cancel that landed during the final save must win: no
            // completion notification, no completed state.
            if (completed == null || completed.status == BacktestJobStore.Status.CANCELLED) {
                throw BacktestCancelledException()
            }

            setProgressAsync(
                workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_PROGRESS to 100,
                    KEY_STEP to "Complete"
                )
            )

            showCompletionNotification(
                jobId = jobId,
                job = job,
                results = results
            )

            Result.success(
                workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_PROGRESS to 100
                )
            )
        } catch (e: BacktestCancelledException) {
            // The persisted state is already CANCELLED (or the job was deleted).
            // Drop any partial checkpoint; no notification, no result.
            store.clearCheckpoint(jobId)
            Result.failure(workDataOf(KEY_JOB_ID to jobId, KEY_ERROR to "Cancelled"))
        } catch (t: Throwable) {
            val message = t.message ?: t::class.java.simpleName
            val failed = store.fail(jobId, message)

            if (failed != null && failed.status == BacktestJobStore.Status.FAILED) {
                showFailureNotification(jobId, job, message)
            }

            Result.failure(
                workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_ERROR to message
                )
            )
        }
    }

    private fun displayStrategyName(strategyId: String): String {
        return when (strategyId) {
            "moving_average_crossover" -> "Moving Average Crossover"
            "rsi" -> "RSI"
            "macd" -> "MACD"
            "bollinger_bands" -> "Bollinger Bands"
            "donchian_channel" -> "Donchian Channel"
            "donchian_ema" -> "Donchian EMA"
            "cpr_ema" -> "CPR + EMA"
            else -> strategyId
        }
    }

    private fun calculateOverallProgress(
        completedStrategies: Int,
        totalStrategies: Int,
        localProgress: Int
    ): Int {
        if (totalStrategies <= 0) return 10

        return (
            10.0 +
                (
                    (
                        completedStrategies.toDouble() +
                            (localProgress.coerceIn(0, 100) / 100.0)
                    ) / totalStrategies.toDouble()
                ) * 75.0
            ).toInt().coerceIn(10, 85)
    }

    private fun estimateRemainingMillis(
        startedAtMillis: Long,
        completedStrategies: Int,
        totalStrategies: Int,
        localProgress: Int = 0
    ): Long {
        val elapsed = (System.currentTimeMillis() - startedAtMillis)
            .coerceAtLeast(1L)

        val completedEquivalent =
            completedStrategies.toDouble() +
                localProgress.coerceIn(0, 100) / 100.0

        if (completedEquivalent <= 0.0 || totalStrategies <= 0) {
            return 0L
        }

        val averagePerStrategy =
            elapsed.toDouble() / completedEquivalent

        val remaining =
            (totalStrategies.toDouble() - completedEquivalent)
                .coerceAtLeast(0.0)

        return (averagePerStrategy * remaining)
            .toLong()
            .coerceAtLeast(0L)
    }

    private fun formatEta(milliseconds: Long): String {
        if (milliseconds <= 0L) return "<1m"

        val totalSeconds =
            ((milliseconds + 999L) / 1000L).coerceAtLeast(1L)

        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            hours > 0L ->
                "%dh %02dm".format(hours, minutes)

            minutes > 0L ->
                "%dm %02ds".format(minutes, seconds)

            else ->
                "%ds".format(seconds)
        }
    }

    private fun createForegroundInfo(
        message: String,
        progress: Int
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    FOREGROUND_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active ALTRIXA backtest progress"
                }
            )
        }

        val intent = Intent(
            applicationContext,
            MainActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            FOREGROUND_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlags()
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(
                    applicationContext,
                    FOREGROUND_CHANNEL_ID
                )
            } else {
                android.app.Notification.Builder(applicationContext)
            }

        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_altrixa)
            .setColor(AltrixaColors.accent)
            .setSubText("ALTRIXA \u00b7 Backtest")
            .setCategory(android.app.Notification.CATEGORY_PROGRESS)
            .setContentTitle("ALTRIXA Backtest Running")
            .setContentText(message)
            .setProgress(
                100,
                progress.coerceIn(0, 100),
                false
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification
            )
        }
    }

    /** Thrown when the persisted job was cancelled or deleted while this Worker ran. */
    private class BacktestCancelledException : RuntimeException("Backtest cancelled")

    private fun throwIfCancelled(jobId: String) {
        val current = store.get(jobId)
        if (current == null || current.status == BacktestJobStore.Status.CANCELLED) {
            throw BacktestCancelledException()
        }
    }

    private fun update(
        jobId: String,
        status: BacktestJobStore.Status,
        progress: Int,
        step: String
    ) {
        val written = store.update(
            jobId = jobId,
            status = status,
            progress = progress,
            currentStep = step,
            errorMessage = null
        )

        // store.update never overwrites a CANCELLED job; a cancelled or deleted
        // job stops this Worker instead of continuing to compute.
        if (written == null || written.status == BacktestJobStore.Status.CANCELLED) {
            throw BacktestCancelledException()
        }

        setProgressAsync(
            Data.Builder()
                .putString(KEY_JOB_ID, jobId)
                .putInt(KEY_PROGRESS, progress)
                .putString(KEY_STEP, step)
                .build()
        )
    }

    private fun showCompletionNotification(
        jobId: String,
        job: BacktestJobStore.Job,
        results: List<BacktestResult>
    ) {
        ensureNotificationChannel()

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_BACKTEST_JOB_ID, jobId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            jobId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlags()
        )

        // Presentation is built by AltrixaNotifications (never throws);
        // channel, ids and the PendingIntent above are unchanged.
        val notification = AltrixaNotifications.backtestComplete(
            applicationContext,
            CHANNEL_ID,
            pendingIntent,
            job,
            results
        )

        manager.notify(
            COMPLETION_NOTIFICATION_BASE + (jobId.hashCode() and 0x0FFFFFFF),
            notification
        )

        AltrixaNotifications.postGroupSummary(applicationContext, CHANNEL_ID)
    }

    private fun showFailureNotification(
        jobId: String,
        job: BacktestJobStore.Job?,
        message: String
    ) {
        ensureNotificationChannel()

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_BACKTEST_JOB_ID, jobId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            jobId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlags()
        )

        val notification = AltrixaNotifications.backtestFailed(
            applicationContext,
            CHANNEL_ID,
            pendingIntent,
            job,
            message
        )

        manager.notify(
            FAILURE_NOTIFICATION_BASE + (jobId.hashCode() and 0x0FFFFFFF),
            notification
        )

        AltrixaNotifications.postGroupSummary(applicationContext, CHANNEL_ID)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Backtest results",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Backtest completion and failure notifications"
                }
            )
        }
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    companion object {
        const val KEY_JOB_ID = "backtest_job_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_STEP = "step"
        const val KEY_ERROR = "error"

        const val EXTRA_BACKTEST_JOB_ID = "backtest_job_id"

        private const val CHANNEL_ID = "backtest_results"
        private const val COMPLETION_NOTIFICATION_BASE = 5300
        private const val FAILURE_NOTIFICATION_BASE = 5400
    }
}
