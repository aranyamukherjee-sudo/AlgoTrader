package com.algotrader.app.ui.chart

import com.algotrader.app.Candle
import com.algotrader.app.theme.AltrixaColors
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Locale

fun formatVolume(v: Float): String {
    return when {
        v >= 1_000_000f -> String.format(Locale.US, "%.2fM", v / 1_000_000f)
        v >= 1_000f -> String.format(Locale.US, "%.2fK", v / 1_000f)
        else -> String.format(Locale.US, "%.0f", v)
    }
}

class VolumeChartView(context: android.content.Context) : View(context) {

    private var candles: List<Candle> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.chartAxisText
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }

    fun setData(candles: List<Candle>) {
        this.candles = candles
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(AltrixaColors.chartBackground)
        if (candles.isEmpty()) return

        val rightMargin = 96f
        val left = 8f
        val right = width - rightMargin
        val top = 6f
        val bottom = height - 6f

        val maxVolume = candles.maxOf { it.volume }.coerceAtLeast(1f)
        val slot = (right - left) / candles.size
        val barWidth = (slot * 0.6f).coerceAtLeast(2f)

        candles.forEachIndexed { index, candle ->
            val x = left + slot * index + slot / 2f
            val barHeight = (candle.volume / maxVolume) * (bottom - top)
            val bullish = candle.close >= candle.open

            paint.color = if (bullish) AltrixaColors.bullish else AltrixaColors.bearish
            paint.style = Paint.Style.FILL
            canvas.drawRect(
                x - barWidth / 2f,
                bottom - barHeight,
                x + barWidth / 2f,
                bottom,
                paint
            )
        }

        val latestVolume = candles.last().volume
        canvas.drawText(formatVolume(latestVolume), right + 8f, top + 20f, textPaint)
    }
}
