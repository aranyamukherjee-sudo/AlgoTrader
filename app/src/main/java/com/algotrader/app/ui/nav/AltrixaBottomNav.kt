package com.algotrader.app.ui.nav

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.theme.dpToPxF

/** The app's five top-level destinations. */
enum class AltrixaDestination(val label: String) {
    HOME("Home"),
    MARKET_DATA("Market Data"),
    STRATEGIES("Strategies"),
    EXECUTION("Execution"),
    BACKTEST("Backtest")
}

/**
 * Premium ALTRIXA bottom navigation.
 *
 * Navigation behavior remains unchanged:
 * each destination invokes the callback supplied by MainActivity.
 *
 * Phase 2C-B is visual only.
 */
class AltrixaBottomNav(
    context: Context,
    private val onSelect: (AltrixaDestination) -> Unit
) : LinearLayout(context) {

    private val items =
        mutableMapOf<AltrixaDestination, LinearLayout>()

    private val icons =
        mutableMapOf<AltrixaDestination, ImageView>()

    private val labels =
        mutableMapOf<AltrixaDestination, TextView>()

    private var selected = AltrixaDestination.HOME

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(
            context.dpToPx(AltrixaDimens.spaceSm),
            context.dpToPx(AltrixaDimens.spaceSm),
            context.dpToPx(AltrixaDimens.spaceSm),
            context.dpToPx(AltrixaDimens.spaceSm + 4)
        )

        setBackgroundColor(AltrixaColors.surface)

        AltrixaDestination.values().forEach { destination ->
            addDestination(destination)
        }

        applySelectedStyle()
    }

    private fun addDestination(destination: AltrixaDestination) {
        val item = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                context.dpToPx(AltrixaDimens.spaceXs),
                context.dpToPx(AltrixaDimens.spaceXs),
                context.dpToPx(AltrixaDimens.spaceXs),
                context.dpToPx(AltrixaDimens.spaceXs)
            )

            isClickable = true
            isFocusable = true

            setOnClickListener {
                setSelected(destination)
                onSelect(destination)
            }
        }

        val icon = ImageView(context).apply {
            setImageDrawable(createIconDrawable(destination))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = destination.label
        }

        val iconSize = context.dpToPx(22)

        item.addView(
            icon,
            LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        )

        val label = TextView(context).apply {
            text = destination.label
            textSize = AltrixaDimens.textCaption
            gravity = Gravity.CENTER
            maxLines = 1
            setTypeface(typeface, Typeface.NORMAL)
        }

        item.addView(
            label,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(2)
            }
        )

        items[destination] = item
        icons[destination] = icon
        labels[destination] = label

        addView(
            item,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )
    }

    fun setSelected(destination: AltrixaDestination) {
        selected = destination
        applySelectedStyle()
    }

    private fun applySelectedStyle() {
        items.forEach { (destination, item) ->
            val active = destination == selected

            item.background = createItemBackground(active)

            icons[destination]?.setColorFilter(
                if (active) {
                    AltrixaColors.accentBright
                } else {
                    AltrixaColors.textMuted
                }
            )

            labels[destination]?.apply {
                setTextColor(
                    if (active) {
                        AltrixaColors.accentBright
                    } else {
                        AltrixaColors.textSecondary
                    }
                )

                setTypeface(
                    typeface,
                    if (active) Typeface.BOLD else Typeface.NORMAL
                )
            }
        }
    }

    private fun createItemBackground(active: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = context.dpToPxF(AltrixaDimens.radiusMd)

            if (active) {
                setColor(AltrixaColors.surfaceVariant)
                setStroke(
                    context.dpToPx(1),
                    AltrixaColors.borderStrong
                )
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
    }

    /**
     * Lightweight vector-style icon generation using Android Canvas.
     * This keeps the Phase 2C navigation self-contained and avoids
     * external icon dependencies.
     */
    private fun createIconDrawable(
        destination: AltrixaDestination
    ): android.graphics.drawable.Drawable {

        return object : android.graphics.drawable.Drawable() {

            private val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
            ).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = context.dpToPxF(1.8f)
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()

                paint.color = AltrixaColors.textSecondary

                val l = w * 0.18f
                val r = w * 0.82f
                val t = h * 0.18f
                val b = h * 0.82f

                when (destination) {

                    AltrixaDestination.HOME -> {
                        val s = w * 0.22f
                        canvas.drawRect(l, t, l + s, t + s, paint)
                        canvas.drawRect(r - s, t, r, t + s, paint)
                        canvas.drawRect(l, b - s, l + s, b, paint)
                        canvas.drawRect(r - s, b - s, r, b, paint)
                    }

                    AltrixaDestination.MARKET_DATA -> {
                        val barW = w * 0.08f

                        canvas.drawLine(
                            w * 0.25f,
                            h * 0.55f,
                            w * 0.25f,
                            h * 0.78f,
                            paint
                        )
                        canvas.drawLine(
                            w * 0.25f,
                            h * 0.55f,
                            w * 0.25f,
                            h * 0.32f,
                            paint
                        )

                        canvas.drawLine(
                            w * 0.50f,
                            h * 0.40f,
                            w * 0.50f,
                            h * 0.72f,
                            paint
                        )
                        canvas.drawLine(
                            w * 0.50f,
                            h * 0.40f,
                            w * 0.50f,
                            h * 0.20f,
                            paint
                        )

                        canvas.drawLine(
                            w * 0.75f,
                            h * 0.60f,
                            w * 0.75f,
                            h * 0.82f,
                            paint
                        )
                        canvas.drawLine(
                            w * 0.75f,
                            h * 0.60f,
                            w * 0.75f,
                            h * 0.38f,
                            paint
                        )

                        paint.strokeWidth = barW
                        canvas.drawLine(
                            w * 0.18f,
                            h * 0.82f,
                            w * 0.82f,
                            h * 0.82f,
                            paint
                        )
                        paint.strokeWidth = context.dpToPxF(1.8f)
                    }

                    AltrixaDestination.STRATEGIES -> {
                        canvas.drawLine(
                            w * 0.22f,
                            h * 0.30f,
                            w * 0.78f,
                            h * 0.30f,
                            paint
                        )
                        canvas.drawLine(
                            w * 0.22f,
                            h * 0.50f,
                            w * 0.78f,
                            h * 0.50f,
                            paint
                        )
                        canvas.drawLine(
                            w * 0.22f,
                            h * 0.70f,
                            w * 0.78f,
                            h * 0.70f,
                            paint
                        )

                        canvas.drawCircle(w * 0.42f, h * 0.30f, w * 0.07f, paint)
                        canvas.drawCircle(w * 0.65f, h * 0.50f, w * 0.07f, paint)
                        canvas.drawCircle(w * 0.35f, h * 0.70f, w * 0.07f, paint)
                    }

                    AltrixaDestination.EXECUTION -> {
                        val path = android.graphics.Path()
                        path.moveTo(w * 0.55f, h * 0.16f)
                        path.lineTo(w * 0.30f, h * 0.54f)
                        path.lineTo(w * 0.49f, h * 0.54f)
                        path.lineTo(w * 0.43f, h * 0.84f)
                        path.lineTo(w * 0.72f, h * 0.43f)
                        path.lineTo(w * 0.53f, h * 0.43f)
                        path.close()
                        canvas.drawPath(path, paint)
                    }

                    AltrixaDestination.BACKTEST -> {
                        val path = android.graphics.Path()
                        path.moveTo(w * 0.72f, h * 0.28f)
                        path.lineTo(w * 0.72f, h * 0.72f)
                        path.lineTo(w * 0.34f, h * 0.50f)
                        path.close()
                        canvas.drawPath(path, paint)

                        canvas.drawArc(
                            w * 0.16f,
                            h * 0.20f,
                            w * 0.82f,
                            h * 0.80f,
                            120f,
                            220f,
                            false,
                            paint
                        )
                    }
                }
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(
                colorFilter: android.graphics.ColorFilter?
            ) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Android SDK")
            override fun getOpacity(): Int =
                android.graphics.PixelFormat.TRANSLUCENT
        }
    }
}
