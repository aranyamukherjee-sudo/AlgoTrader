package com.algotrader.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.algotrader.app.MainActivity
import com.algotrader.app.R
import com.algotrader.app.backtest.BacktestFormat
import com.algotrader.app.backtest.BacktestJobStore
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.backtest.BacktestResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ALTRIXA-branded notification builders.
 *
 * Presentation only: scheduling, WorkManager, persistence and navigation
 * (the PendingIntents passed in) are owned by the callers and unchanged.
 *
 * Branding:
 *  - Status-bar icon: monochrome vector (Android renders it as an alpha mask).
 *  - Large icon: the existing `altrixa_notification` artwork. The asset ships
 *    with a baked-in checkerboard "transparency" pattern, so it is cleaned at
 *    runtime onto the ALTRIXA brand tile; the file itself is not modified.
 *  - Lock screen: figures are hidden behind a neutral public version.
 */
object AltrixaNotifications {

    const val GROUP_BACKTEST = "altrixa_backtest_results"
    private const val SUMMARY_NOTIFICATION_ID = 5399
    private const val LARGE_ICON_PX = 256
    private const val BRAND_TILE = 0xFF070D16.toInt()

    @Volatile
    private var cachedLargeIcon: Bitmap? = null

    // -----------------------------------------------------------------
    // Public builders
    // -----------------------------------------------------------------

    /** The processed ALTRIXA logo tile, or null if it could not be prepared. */
    fun brandLargeIcon(context: Context): Bitmap? {
        cachedLargeIcon?.let { return it }
        return try {
            buildLargeIcon(context.applicationContext)?.also { cachedLargeIcon = it }
        } catch (_: Throwable) {
            null
        }
    }

    /** Premium "backtest complete" notification. Never throws. */
    fun backtestComplete(
        context: Context,
        channelId: String,
        contentIntent: PendingIntent,
        job: BacktestJobStore.Job,
        results: List<BacktestResult>
    ): Notification {
        return try {
            premiumComplete(context, channelId, contentIntent, job, results)
        } catch (_: Throwable) {
            basic(
                context, channelId, contentIntent,
                "ALTRIXA backtest complete",
                "${results.size} strategy result(s) are ready."
            )
        }
    }

    /** Premium "backtest failed" notification. Never throws. */
    fun backtestFailed(
        context: Context,
        channelId: String,
        contentIntent: PendingIntent,
        job: BacktestJobStore.Job?,
        message: String
    ): Notification {
        return try {
            val where = job?.let {
                "${strategyLabel(it)} \u00b7 ${BacktestFormat.instrumentName(it.instrumentSymbol)} " +
                    BacktestFormat.timeframeLabel(it.timeframe)
            }
            val body = message.take(160)

            Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_altrixa)
                .setColor(AltrixaColors.negative)
                .setLargeIcon(brandLargeIcon(context))
                .setContentTitle("Backtest failed")
                .setContentText(body)
                .setSubText("ALTRIXA \u00b7 Backtest")
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(if (where != null) "$where\n$body" else body)
                )
                .setCategory(Notification.CATEGORY_ERROR)
                .setGroup(GROUP_BACKTEST)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, channelId, "Backtest failed"))
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
        } catch (_: Throwable) {
            basic(context, channelId, contentIntent, "ALTRIXA backtest failed", message.take(120))
        }
    }

    /**
     * Posts the silent group summary so several finished backtests collapse
     * into one ALTRIXA bundle. Children keep alerting individually.
     */
    fun postGroupSummary(context: Context, channelId: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val open = PendingIntent.getActivity(
                context,
                SUMMARY_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val summary = Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_altrixa)
                .setColor(AltrixaColors.accent)
                .setContentTitle("ALTRIXA backtests")
                .setContentText("Backtest results are ready")
                .setStyle(Notification.InboxStyle().setSummaryText("ALTRIXA \u00b7 Backtest results"))
                .setGroup(GROUP_BACKTEST)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()

            manager.notify(SUMMARY_NOTIFICATION_ID, summary)
        } catch (_: Throwable) {
            // A missing summary only means no bundling; individual
            // notifications are unaffected.
        }
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun premiumComplete(
        context: Context,
        channelId: String,
        contentIntent: PendingIntent,
        job: BacktestJobStore.Job,
        results: List<BacktestResult>
    ): Notification {
        val instrument =
            "${BacktestFormat.instrumentName(job.instrumentSymbol)} \u00b7 ${BacktestFormat.timeframeLabel(job.timeframe)}"

        val title = "Backtest complete"
        val shortText: String
        val bigText: String

        if (results.size == 1) {
            val result = results.first()
            val m = result.metrics
            val headline = "${BacktestFormat.signedMoney(m.netProfit)} (${BacktestFormat.signedPercent(m.totalReturnPercent)})"
            shortText = "${result.strategyName} \u00b7 $headline"
            bigText = listOf(
                "${result.strategyName} \u00b7 $instrument",
                "Net P&L  $headline",
                "${m.totalTrades} trades \u00b7 Win rate ${BacktestFormat.percent(m.winRate * 100.0)} \u00b7 " +
                    "Max DD ${BacktestFormat.percent(m.maxDrawdownPercent)}",
                "Final equity ${BacktestFormat.money(result.finalEquity)}"
            ).joinToString("\n")
        } else {
            val top = results.maxByOrNull { it.metrics.netProfit }
            shortText = "${results.size} strategies \u00b7 $instrument"
            bigText = buildString {
                append("${results.size} strategies \u00b7 $instrument")
                if (top != null) {
                    append("\nTop: ${top.strategyName}  ${BacktestFormat.signedMoney(top.metrics.netProfit)}")
                }
            }
        }

        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_altrixa)
            .setColor(AltrixaColors.accent)
            .setLargeIcon(brandLargeIcon(context))
            .setContentTitle(title)
            .setContentText(shortText)
            .setSubText("ALTRIXA \u00b7 Backtest")
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setCategory(Notification.CATEGORY_STATUS)
            .setGroup(GROUP_BACKTEST)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, channelId, "Backtest complete"))
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        return notification
    }

    private fun publicVersion(context: Context, channelId: String, title: String): Notification =
        Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_altrixa)
            .setColor(AltrixaColors.accent)
            .setContentTitle("ALTRIXA")
            .setContentText(title)
            .build()

    private fun basic(
        context: Context,
        channelId: String,
        contentIntent: PendingIntent,
        title: String,
        text: String
    ): Notification =
        Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_altrixa)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

    private fun strategyLabel(job: BacktestJobStore.Job): String {
        val names = job.strategies.map { BacktestFormat.strategyName(it.strategyId) }
        return when {
            names.isEmpty() -> "Backtest"
            names.size == 1 -> names.first()
            else -> "${names.size} strategies"
        }
    }

    // -----------------------------------------------------------------
    // Large icon: remove the baked-in checkerboard and set on the brand tile
    // -----------------------------------------------------------------

    private fun buildLargeIcon(context: Context): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 4
            inScaled = false
        }
        val source = BitmapFactory.decodeResource(context.resources, R.drawable.altrixa_notification, options)
            ?: return null

        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        source.recycle()

        // 1. Classify checkerboard pixels (neutral mid-greys) as background.
        val foreground = BooleanArray(w * h)
        var backgroundCount = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val neutral = abs(r - g) <= 7 && abs(g - b) <= 7
            val isBackground = neutral && r in 66..118
            foreground[i] = !isBackground
            if (isBackground) backgroundCount++
        }

        // If the artwork has no checkerboard (e.g. it was replaced with a
        // true transparent logo), just scale it as-is.
        if (backgroundCount < pixels.size / 5) {
            return Bitmap.createScaledBitmap(
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888),
                LARGE_ICON_PX, LARGE_ICON_PX, true
            )
        }

        // 2. Remove JPEG speckle / checker edges, keep only the large artwork.
        val opened = dilate(erode(foreground, w, h), w, h)
        val keep = largeComponents(opened, w, h, minPixels = (3000L * w * h / (512L * 512L)).toInt())

        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (keep[y * w + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null

        // 3. Composite artwork onto the ALTRIXA brand tile.
        val out = IntArray(w * h)
        for (i in out.indices) {
            out[i] = if (keep[i]) (pixels[i] or 0xFF000000.toInt()) else BRAND_TILE
        }
        val full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)

        // 4. Square crop around the artwork with a little breathing room.
        val side = min(min(w, h), (max(maxX - minX, maxY - minY) * 1.12f).toInt().coerceAtLeast(1))
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        val left = (centerX - side / 2).coerceIn(0, w - side)
        val top = (centerY - side / 2).coerceIn(0, h - side)
        val cropped = Bitmap.createBitmap(full, left, top, side, side)

        return Bitmap.createScaledBitmap(cropped, LARGE_ICON_PX, LARGE_ICON_PX, true)
    }

    private fun erode(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val dst = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var all = true
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (!src[(y + dy) * w + (x + dx)]) {
                            all = false
                            break@loop
                        }
                    }
                }
                dst[y * w + x] = all
            }
        }
        return dst
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val dst = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var any = false
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        if (src[yy * w + xx]) {
                            any = true
                            break@loop
                        }
                    }
                }
                dst[y * w + x] = any
            }
        }
        return dst
    }

    /** 4-connected components of [mask] with at least [minPixels] pixels. */
    private fun largeComponents(mask: BooleanArray, w: Int, h: Int, minPixels: Int): BooleanArray {
        val visited = BooleanArray(w * h)
        val keep = BooleanArray(w * h)
        val stack = IntArray(w * h)

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var top = 0
            var size = 0
            stack[top++] = start
            visited[start] = true
            val members = ArrayList<Int>()

            while (top > 0) {
                val index = stack[--top]
                members.add(index)
                size++
                val x = index % w
                val y = index / w
                if (x > 0 && mask[index - 1] && !visited[index - 1]) {
                    visited[index - 1] = true; stack[top++] = index - 1
                }
                if (x < w - 1 && mask[index + 1] && !visited[index + 1]) {
                    visited[index + 1] = true; stack[top++] = index + 1
                }
                if (y > 0 && mask[index - w] && !visited[index - w]) {
                    visited[index - w] = true; stack[top++] = index - w
                }
                if (y < h - 1 && mask[index + w] && !visited[index + w]) {
                    visited[index + w] = true; stack[top++] = index + w
                }
            }

            if (size >= minPixels) {
                for (member in members) keep[member] = true
            }
        }
        return keep
    }
}
