package com.algotrader.app.ui.chart

import com.algotrader.app.Candle
import com.algotrader.app.theme.AltrixaColors
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TradingChartView(context: android.content.Context) : View(context) {

    private var candles: List<Candle> = emptyList()
    private var ema20: List<Float> = emptyList()
    private var ema50: List<Float> = emptyList()
    private var isDaily = false
    private var latestPrice: Float? = null

    private var visibleCount = 80
    private var endIndex = 0
    private var crosshairIndex = -1
    private var lastTouchX = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.chartAxisText
        textSize = 26f
    }

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AltrixaColors.chartAxisText
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 7f), 0f)
    }

    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE151A21.toInt()
        style = Paint.Style.FILL
    }

    private val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 19f
        textAlign = Paint.Align.LEFT
    }

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    visibleCount =
                        (visibleCount / detector.scaleFactor)
                            .toInt()
                            .coerceIn(
                                20,
                                candles.size.coerceAtLeast(20)
                            )

                    endIndex =
                        endIndex.coerceIn(
                            visibleCount - 1,
                            candles.lastIndex
                        )

                    invalidate()
                    return true
                }
            }
        )

    private val intradayFormat =
        SimpleDateFormat("dd MMM HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }

    private val dailyFormat =
        SimpleDateFormat("dd MMM yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }

    fun setData(
        candles: List<Candle>,
        ema20: List<Float>,
        ema50: List<Float>,
        isDaily: Boolean
    ) {
        this.candles = candles
        this.ema20 = ema20
        this.ema50 = ema50
        this.isDaily = isDaily

        visibleCount =
            minOf(80, candles.size.coerceAtLeast(1))

        endIndex = candles.lastIndex
        crosshairIndex = -1

        invalidate()
    }

    fun setLatestPrice(price: Float) {
        latestPrice = price
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (candles.isEmpty()) return true

        val left = 8f
        val right = width - 96f
        val chartWidth = (right - left).coerceAtLeast(1f)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                crosshairIndex = indexForX(event.x, left, chartWidth)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1) {
                    return true
                }

                val dx = event.x - lastTouchX

                if (kotlin.math.abs(dx) >= 4f) {
                    val slot = chartWidth / visibleCount.toFloat()

                    if (slot > 0f) {
                        val candleShift = (-dx / slot).toInt()

                        if (candleShift != 0) {
                            endIndex = (
                                endIndex + candleShift
                            ).coerceIn(
                                visibleCount - 1,
                                candles.lastIndex
                            )

                            lastTouchX += candleShift * -slot
                        }
                    }

                    crosshairIndex = indexForX(event.x, left, chartWidth)
                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                crosshairIndex = indexForX(event.x, left, chartWidth)
                invalidate()
                return true
            }
        }

        return true
    }

    private fun indexForX(
        x: Float,
        left: Float,
        chartWidth: Float
    ): Int {
        if (candles.isEmpty()) return -1

        val count =
            visibleCount.coerceIn(
                1,
                candles.size
            )

        val startIndex =
            (endIndex - count + 1)
                .coerceAtLeast(0)

        val slot =
            chartWidth / count.toFloat()

        val localIndex =
            ((x - left) / slot)
                .toInt()
                .coerceIn(
                    0,
                    count - 1
                )

        return (
            startIndex + localIndex
        ).coerceIn(
            0,
            candles.lastIndex
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(AltrixaColors.chartBackground)

        if (candles.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "Loading chart…",
                width / 2f,
                height / 2f,
                textPaint
            )
            return
        }

        val rightMargin = 96f
        val bottomMargin = 34f
        val left = 8f
        val right = width - rightMargin
        val top = 12f
        val bottom = height - bottomMargin

        val count =
            visibleCount.coerceIn(
                1,
                candles.size
            )

        val startIndex =
            (endIndex - count + 1)
                .coerceAtLeast(0)

        val endVisible =
            (startIndex + count - 1)
                .coerceAtMost(candles.lastIndex)

        val visibleCandles =
            candles.subList(
                startIndex,
                endVisible + 1
            )

        var minPrice =
            visibleCandles.minOf { it.low }

        var maxPrice =
            visibleCandles.maxOf { it.high }

        ema20
            .drop(startIndex)
            .take(visibleCandles.size)
            .forEach { value ->
                if (value > 0f) {
                    minPrice = minOf(minPrice, value)
                    maxPrice = maxOf(maxPrice, value)
                }
            }

        ema50
            .drop(startIndex)
            .take(visibleCandles.size)
            .forEach { value ->
                if (value > 0f) {
                    minPrice = minOf(minPrice, value)
                    maxPrice = maxOf(maxPrice, value)
                }
            }

        latestPrice?.let {
            minPrice = minOf(minPrice, it)
            maxPrice = maxOf(maxPrice, it)
        }

        val padding =
            ((maxPrice - minPrice) * 0.08f)
                .coerceAtLeast(0.5f)

        minPrice -= padding
        maxPrice += padding

        val range =
            (maxPrice - minPrice)
                .coerceAtLeast(0.01f)

        fun priceY(price: Float): Float =
            bottom -
                (price - minPrice) /
                range *
                (bottom - top)

        // Grid.
        paint.style = Paint.Style.STROKE
        paint.color = AltrixaColors.chartGrid
        paint.strokeWidth = 1f

        textPaint.color = AltrixaColors.chartAxisText
        textPaint.textAlign = Paint.Align.LEFT

        for (i in 0..4) {
            val y =
                top +
                (bottom - top) *
                i / 4f

            canvas.drawLine(
                left,
                y,
                right,
                y,
                paint
            )

            val price =
                maxPrice -
                (maxPrice - minPrice) *
                i / 4f

            canvas.drawText(
                String.format(
                    Locale.US,
                    "%,.2f",
                    price
                ),
                right + 8f,
                y + 9f,
                textPaint
            )
        }

        val slot =
            (right - left) /
            visibleCandles.size.toFloat()

        val bodyWidth =
            (slot * 0.6f)
                .coerceAtLeast(2f)

        // Candles.
        visibleCandles.forEachIndexed { index, candle ->

            val x =
                left +
                slot * index +
                slot / 2f

            val bullish =
                candle.close >= candle.open

            paint.color =
                if (bullish)
                    AltrixaColors.bullish
                else
                    AltrixaColors.bearish

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f

            canvas.drawLine(
                x,
                priceY(candle.high),
                x,
                priceY(candle.low),
                paint
            )

            paint.style = Paint.Style.FILL

            val bodyTop =
                priceY(
                    maxOf(
                        candle.open,
                        candle.close
                    )
                )

            val bodyBottom =
                priceY(
                    minOf(
                        candle.open,
                        candle.close
                    )
                )

            canvas.drawRect(
                x - bodyWidth / 2f,
                bodyTop,
                x + bodyWidth / 2f,
                maxOf(
                    bodyBottom,
                    bodyTop + 2f
                ),
                paint
            )
        }

        // EMA20.
        drawEmaLine(
            canvas,
            ema20.drop(startIndex).take(visibleCandles.size),
            slot,
            left,
            ::priceY,
            AltrixaColors.chartEma20
        )

        // EMA50.
        drawEmaLine(
            canvas,
            ema50.drop(startIndex).take(visibleCandles.size),
            slot,
            left,
            ::priceY,
            AltrixaColors.chartEma50
        )

        // Live price.
        latestPrice?.let { price ->

            val y = priceY(price)

            dashedPaint.color =
                AltrixaColors.chartPriceLine

            canvas.drawLine(
                left,
                y,
                right,
                y,
                dashedPaint
            )

            paint.style = Paint.Style.FILL
            paint.color =
                AltrixaColors.chartPriceLine

            canvas.drawRect(
                right,
                y - 16f,
                width.toFloat(),
                y + 16f,
                paint
            )

            textPaint.color = Color.BLACK
            textPaint.textAlign =
                Paint.Align.LEFT

            canvas.drawText(
                String.format(
                    Locale.US,
                    "%,.2f",
                    price
                ),
                right + 8f,
                y + 9f,
                textPaint
            )

            textPaint.color =
                AltrixaColors.chartAxisText
        }

        // Time labels.
        textPaint.textAlign =
            Paint.Align.CENTER

        val labelCount =
            minOf(5, visibleCandles.size)

        for (i in 0 until labelCount) {

            val localIndex =
                if (labelCount == 1)
                    0
                else
                    i *
                    (visibleCandles.size - 1) /
                    (labelCount - 1)

            val x =
                left +
                slot * localIndex +
                slot / 2f

            val timestamp =
                visibleCandles[localIndex]
                    .timestamp * 1000L

            val label =
                if (isDaily)
                    dailyFormat.format(Date(timestamp))
                else
                    intradayFormat.format(Date(timestamp))

            canvas.drawText(
                label,
                x,
                height - 8f,
                textPaint
            )
        }

        // Crosshair.
        if (
            crosshairIndex in
            startIndex..endVisible
        ) {
            val localIndex =
                crosshairIndex - startIndex

            val candle =
                candles[crosshairIndex]

            val x =
                left +
                slot * localIndex +
                slot / 2f

            val y =
                priceY(candle.close)

            crosshairPaint.color =
                AltrixaColors.chartAxisText

            canvas.drawLine(
                x,
                top,
                x,
                bottom,
                crosshairPaint
            )

            canvas.drawLine(
                left,
                y,
                right,
                y,
                crosshairPaint
            )

            // Crosshair price tag.
            paint.style = Paint.Style.FILL
            paint.color =
                AltrixaColors.chartAxisText

            canvas.drawRect(
                right,
                y - 16f,
                width.toFloat(),
                y + 16f,
                paint
            )

            textPaint.color = Color.BLACK
            textPaint.textAlign =
                Paint.Align.LEFT

            canvas.drawText(
                String.format(
                    Locale.US,
                    "%.2f",
                    candle.close
                ),
                right + 8f,
                y + 7f,
                textPaint
            )

            // OHLC information.
            val timestamp =
                candle.timestamp * 1000L

            val time =
                if (isDaily)
                    dailyFormat.format(Date(timestamp))
                else
                    intradayFormat.format(Date(timestamp))

            val info =
                "$time  O ${price(candle.open)}  " +
                "H ${price(candle.high)}  " +
                "L ${price(candle.low)}  " +
                "C ${price(candle.close)}"

            val boxLeft = 12f
            val boxTop = top + 8f
            val boxRight =
                minOf(
                    right - 4f,
                    boxLeft + 510f
                )
            val boxBottom =
                boxTop + 40f

            infoPaint.color =
                0xEE151A21.toInt()

            canvas.drawRoundRect(
                boxLeft,
                boxTop,
                boxRight,
                boxBottom,
                8f,
                8f,
                infoPaint
            )

            infoTextPaint.color =
                Color.WHITE

            canvas.drawText(
                info,
                boxLeft + 10f,
                boxTop + 26f,
                infoTextPaint
            )
        }
    }

    private fun price(value: Float): String =
        String.format(
            Locale.US,
            "%.2f",
            value
        )

    private fun drawEmaLine(
        canvas: Canvas,
        values: List<Float>,
        slot: Float,
        left: Float,
        priceY: (Float) -> Float,
        color: Int
    ) {
        if (values.size < 2) return

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = color

        val path = Path()

        values.forEachIndexed { index, value ->

            if (value <= 0f) return@forEachIndexed

            val x =
                left +
                slot * index +
                slot / 2f

            val y = priceY(value)

            if (index == 0)
                path.moveTo(x, y)
            else
                path.lineTo(x, y)
        }

        canvas.drawPath(path, paint)
    }
}
