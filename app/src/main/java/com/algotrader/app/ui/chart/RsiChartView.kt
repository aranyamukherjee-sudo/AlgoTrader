package com.algotrader.app.ui.chart

import com.algotrader.app.Candle
import com.algotrader.app.theme.AltrixaColors
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale

class RsiChartView(context: android.content.Context) : View(context) {

    private var rsiValues: List<Float> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.chartGrid
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.chartAxisText
        textSize = 22f
        textAlign = Paint.Align.LEFT
    }

    fun setData(rsiValues: List<Float>) {
        this.rsiValues = rsiValues
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(AltrixaColors.chartBackground)
        if (rsiValues.isEmpty()) return

        val rightMargin = 96f
        val left = 8f
        val right = width - rightMargin
        val top = 8f
        val bottom = height - 8f

        fun y(value: Float) = bottom - (value / 100f) * (bottom - top)

        listOf(70f, 50f, 30f).forEach { level ->
            canvas.drawLine(left, y(level), right, y(level), levelPaint)
            canvas.drawText(level.toInt().toString(), right + 8f, y(level) + 8f, textPaint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xFFFF7043.toInt()

        val slot = (right - left) / rsiValues.size
        val path = Path()
        rsiValues.forEachIndexed { index, value ->
            val x = left + slot * index + slot / 2f
            val yy = y(value)
            if (index == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        canvas.drawPath(path, paint)

        val latest = rsiValues.last()
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFF7043.toInt()
        val latestY = y(latest)
        canvas.drawRect(right, latestY - 14f, right + rightMargin, latestY + 14f, paint)
        textPaint.color = Color.BLACK
        canvas.drawText(String.format(Locale.US, "%.2f", latest), right + 8f, latestY + 7f, textPaint)
        textPaint.color = AltrixaColors.chartAxisText
    }
}
