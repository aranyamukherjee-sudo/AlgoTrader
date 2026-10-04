package com.algotrader.app.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.algotrader.app.backtest.BacktestFormat
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.dpToPxF
import com.algotrader.app.ui.components.altrixaTint
import com.algotrader.backtest.EquityPoint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Equity-curve line chart. Draws only the real [EquityPoint] series passed
 * to [setData]; it never generates or interpolates data of its own.
 *
 * Touch (or drag horizontally) to read the equity value at a point.
 */
class EquityCurveView(context: Context) : View(context) {

    private var points: List<EquityPoint> = emptyList()
    private var initialCapital = 0.0
    private var showTime = true

    private val zone = ZoneId.of("Asia/Kolkata")
    private val axisDate = DateTimeFormatter.ofPattern("dd MMM yy", Locale.US).withZone(zone)
    private val readoutDate = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US).withZone(zone)
    private val readoutDay = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US).withZone(zone)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false
    private var scrubIndex = -1

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(2.2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(1f)
        color = AltrixaColors.chartGrid
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(1f)
        color = AltrixaColors.borderStrong
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.chartAxisText
        textSize = context.dpToPxF(10.5f)
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.textPrimary
        textSize = context.dpToPxF(11.5f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val readoutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.surfaceVariant
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(1f)
        color = AltrixaColors.textMuted
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val linePath = Path()
    private val fillPath = Path()
    private val bgRect = RectF()

    fun setData(points: List<EquityPoint>, initialCapital: Double, intraday: Boolean = true) {
        this.points = points
        this.initialCapital = initialCapital
        this.showTime = intraday
        this.scrubIndex = -1
        invalidate()
    }

    private fun plotLeft() = context.dpToPxF(8f)
    private fun plotRight() = width - context.dpToPxF(8f)
    private fun plotTop() = context.dpToPxF(26f)
    private fun plotBottom() = height - context.dpToPxF(24f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2 || width <= 0 || height <= 0) return

        val left = plotLeft()
        val right = plotRight()
        val top = plotTop()
        val bottom = plotBottom()

        val values = points.map { it.equity }
        val maxV = max(values.max(), initialCapital)
        val minV = min(values.min(), initialCapital)
        val rawRange = maxV - minV
        val pad = if (rawRange > 0.0) rawRange * 0.08 else max(1.0, abs(maxV) * 0.01)
        val hi = maxV + pad
        val lo = minV - pad
        val range = hi - lo

        fun xAt(index: Int): Float = left + (right - left) * index / (points.size - 1)
        fun yAt(v: Double): Float = (bottom - ((v - lo) / range * (bottom - top))).toFloat()

        // Horizontal grid + y labels (high / low of the visible range).
        val gridLines = 3
        for (i in 0..gridLines) {
            val y = top + (bottom - top) * i / gridLines
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(compactMoney(maxV), left, top - context.dpToPxF(8f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(compactMoney(minV), right, top - context.dpToPxF(8f), textPaint)

        // Baseline at initial capital.
        val baseY = yAt(initialCapital)
        canvas.drawLine(left, baseY, right, baseY, baselinePaint)

        val positive = values.last() >= initialCapital
        val color = if (positive) AltrixaColors.positive else AltrixaColors.negative

        linePath.reset()
        fillPath.reset()
        points.forEachIndexed { index, point ->
            val px = xAt(index)
            val py = yAt(point.equity)
            if (index == 0) {
                linePath.moveTo(px, py)
                fillPath.moveTo(px, bottom)
                fillPath.lineTo(px, py)
            } else {
                linePath.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
        }
        fillPath.lineTo(right, bottom)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            altrixaTint(color, 90), altrixaTint(color, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = color
        canvas.drawPath(linePath, linePaint)

        // End-point marker.
        dotPaint.color = color
        canvas.drawCircle(xAt(points.lastIndex), yAt(values.last()), context.dpToPxF(3.5f), dotPaint)

        // X-axis date labels.
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(axisDate.format(points.first().timestamp), left, height - context.dpToPxF(6f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(axisDate.format(points.last().timestamp), right, height - context.dpToPxF(6f), textPaint)

        // Scrub crosshair + readout.
        if (scrubIndex in points.indices) {
            val sx = xAt(scrubIndex)
            val sy = yAt(points[scrubIndex].equity)
            canvas.drawLine(sx, top, sx, bottom, crossPaint)
            dotPaint.color = AltrixaColors.textPrimary
            canvas.drawCircle(sx, sy, context.dpToPxF(4f), dotPaint)

            val point = points[scrubIndex]
            val stamp = (if (showTime) readoutDate else readoutDay).format(point.timestamp)
            val label = "$stamp  \u00b7  ${BacktestFormat.money(point.equity)}"
            val padPx = context.dpToPxF(8f)
            val textW = readoutPaint.measureText(label)
            val boxW = textW + padPx * 2
            val boxH = context.dpToPxF(22f)
            val boxLeft = (sx - boxW / 2f).coerceIn(left, max(left, right - boxW))
            bgRect.set(boxLeft, 2f, boxLeft + boxW, 2f + boxH)
            canvas.drawRoundRect(bgRect, context.dpToPxF(6f), context.dpToPxF(6f), readoutBgPaint)
            readoutPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, boxLeft + padPx, 2f + boxH * 0.68f, readoutPaint)
        }
    }

    private fun compactMoney(value: Double): String {
        val absV = abs(value)
        return when {
            absV >= 10_000_000.0 -> String.format(Locale.US, "\u20b9%.2fCr", value / 10_000_000.0)
            absV >= 100_000.0 -> String.format(Locale.US, "\u20b9%.2fL", value / 100_000.0)
            else -> String.format(Locale.US, "\u20b9%,.0f", value)
        }
    }

    private fun scrubTo(x: Float) {
        if (points.size < 2) return
        val left = plotLeft()
        val right = plotRight()
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        scrubIndex = (fraction * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                scrubbing = false
                scrubTo(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (!scrubbing && dx > touchSlop && dx > dy) {
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (scrubbing) scrubTo(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                scrubIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
