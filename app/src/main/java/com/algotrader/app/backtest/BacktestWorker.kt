package com.algotrader.app.backtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.algotrader.app.MainActivity
import com.algotrader.backtest.BacktestConfig
import com.algotrader.backtest.StrategyBacktestRunner
import com.algotrader.strategyengine.StrategyFactory

/**
 * Owns the lifecycle of a persisted backtest job.
 *
 * It deliberately has no dependency on Activity/View state.
 */
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

        return try {
            update(jobId, BacktestJobStore.Status.PREPARING, 5, "Preparing historical data")

            val candles = store.getCandles(jobId)
            require(candles.size >= 50) {
                "Not enough persisted candles for the backtest (${candles.size})"
            }

            update(jobId, BacktestJobStore.Status.RUNNING, 10, "Loading strategies")

            val factory = StrategyFactory()
            val runner = StrategyBacktestRunner(factory)

            val config = BacktestConfig(
                initialCapital = job.initialCapital,
                positionSizing = job.positionSizing
            )

            val results = mutableListOf<com.algotrader.backtest.BacktestResult>()
            val total = job.strategies.size

            job.strategies.forEachIndexed { index, strategyConfiguration ->
                if (isStopped) {
                    store.cancel(jobId)
                    return Result.failure(
                        workDataOf(KEY_ERROR to "Backtest cancelled")
                    )
                }

                val startPercent = 10 + ((index.toDouble() / total) * 75.0).toInt()

                update(
                    jobId,
                    BacktestJobStore.Status.RUNNING,
                    startPercent,
                    "Processing ${strategyConfiguration.strategyId}"
                )

                val result = runner.run(
                    configuration = strategyConfiguration,
                    candles = candles,
                    backtestConfig = config
                )

                results += result

                val completedPercent =
                    10 + (((index + 1).toDouble() / total) * 75.0).toInt()

                update(
                    jobId,
                    BacktestJobStore.Status.RUNNING,
                    completedPercent,
                    "Completed ${strategyConfiguration.strategyId}"
                )
            }

            update(
                jobId,
                BacktestJobStore.Status.CALCULATING,
                90,
                "Calculating performance"
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

            store.saveResults(jobId, results)
            store.complete(jobId)

            setProgressAsync(
                workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_PROGRESS to 100,
                    KEY_STEP to "Complete"
                )
            )

            showCompletionNotification(
                jobId = jobId,
                strategyCount = results.size
            )

            Result.success(
                workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_PROGRESS to 100
                )
            )
        } catch (t: Throwable) {
            val message = t.message ?: t::class.java.simpleName
            store.fail(jobId, message)

            showFailureNotification(jobId, message)

            Result.failure(
                workDataOf(
                    KEY_JOB_ID to jobId,
                    KEY_ERROR to message
                )
            )
        }
    }

    private fun update(
        jobId: String,
        status: BacktestJobStore.Status,
        progress: Int,
        step: String
    ) {
        store.update(
            jobId = jobId,
            status = status,
            progress = progress,
            currentStep = step,
            errorMessage = null
        )

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
        strategyCount: Int
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

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            android.app.Notification.Builder(applicationContext)
        }
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("ALTRIXA backtest complete")
            .setContentText("$strategyCount strategy result(s) are ready.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(
            COMPLETION_NOTIFICATION_BASE + (jobId.hashCode() and 0x0FFFFFFF),
            notification
        )
    }

    private fun showFailureNotification(
        jobId: String,
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

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            android.app.Notification.Builder(applicationContext)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("ALTRIXA backtest failed")
            .setContentText(message.take(120))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(
            FAILURE_NOTIFICATION_BASE + (jobId.hashCode() and 0x0FFFFFFF),
            notification
        )
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
