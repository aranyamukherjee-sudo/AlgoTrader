package com.algotrader.app.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.theme.dpToPxF
import kotlin.math.max

/**
 * Premium widgets used by the Backtest screen and dialogs. Pure View code —
 * no Compose, no extra dependencies.
 */

fun altrixaTint(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

fun altrixaToneColor(tone: AltrixaTone): Int = when (tone) {
    AltrixaTone.NEUTRAL -> AltrixaColors.textSecondary
    AltrixaTone.POSITIVE -> AltrixaColors.positive
    AltrixaTone.NEGATIVE -> AltrixaColors.negative
    AltrixaTone.WARNING -> AltrixaColors.warning
    AltrixaTone.ACCENT -> AltrixaColors.accentBright
}

fun altrixaRounded(
    context: Context,
    fill: Int,
    radiusDp: Float,
    stroke: Int? = null
): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = context.dpToPxF(radiusDp)
    if (stroke != null) setStroke(context.dpToPx(1), stroke)
}

fun altrixaDivider(context: Context): View = View(context).apply {
    setBackgroundColor(AltrixaColors.border)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        max(1, context.dpToPx(1))
    ).apply {
        topMargin = context.dpToPx(AltrixaDimens.spaceMd)
        bottomMargin = context.dpToPx(AltrixaDimens.spaceMd)
    }
}

/** Small uppercase caption (eyebrow text) used above values and sections. */
fun altrixaCaption(context: Context, text: String, color: Int = AltrixaColors.textFaint): TextView =
    TextView(context).apply {
        this.text = text.uppercase()
        textSize = AltrixaDimens.textCaption
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
    }

/** Inline message banner (info / success / warning / error). */
fun altrixaBanner(context: Context, message: String, tone: AltrixaTone): TextView {
    val color = altrixaToneColor(tone)
    return TextView(context).apply {
        text = message
        textSize = AltrixaDimens.textSmall
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        background = altrixaRounded(context, altrixaTint(color, 30), AltrixaDimens.radiusSm, altrixaTint(color, 80))
        setPadding(
            context.dpToPx(AltrixaDimens.spaceMd),
            context.dpToPx(AltrixaDimens.spaceSm + 2),
            context.dpToPx(AltrixaDimens.spaceMd),
            context.dpToPx(AltrixaDimens.spaceSm + 2)
        )
    }
}

/** Recolours an existing banner created by [altrixaBanner]. */
fun altrixaStyleBanner(banner: TextView, tone: AltrixaTone) {
    val color = altrixaToneColor(tone)
    val context = banner.context
    banner.setTextColor(color)
    banner.background = altrixaRounded(context, altrixaTint(color, 30), AltrixaDimens.radiusSm, altrixaTint(color, 80))
}

/** Styled single-line text input. */
fun altrixaInput(
    context: Context,
    initial: String,
    signed: Boolean = false,
    numeric: Boolean = true
): EditText = EditText(context).apply {
    setText(initial)
    setSingleLine(true)
    inputType = if (numeric) {
        InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
    } else {
        InputType.TYPE_CLASS_TEXT
    }
    textSize = AltrixaDimens.textSubtitle
    setTextColor(AltrixaColors.textPrimary)
    setHintTextColor(AltrixaColors.textFaint)
    setSelectAllOnFocus(true)
    setTypeface(typeface, Typeface.BOLD)
    minHeight = context.dpToPx(46)
    setPadding(
        context.dpToPx(AltrixaDimens.spaceMd),
        context.dpToPx(AltrixaDimens.spaceSm),
        context.dpToPx(AltrixaDimens.spaceMd),
        context.dpToPx(AltrixaDimens.spaceSm)
    )
    altrixaStyleInput(this, hasError = false)
    setOnFocusChangeListener { v, focused ->
        altrixaStyleInput(v as EditText, hasError = false, focused = focused)
    }
}

fun altrixaStyleInput(input: EditText, hasError: Boolean, focused: Boolean = input.hasFocus()) {
    val context = input.context
    val stroke = when {
        hasError -> AltrixaColors.negative
        focused -> AltrixaColors.accent
        else -> AltrixaColors.border
    }
    input.background = altrixaRounded(context, AltrixaColors.background, AltrixaDimens.radiusSm, stroke)
}

/** Segmented control. [onSelect] receives the chosen index. */
fun altrixaSegmented(
    context: Context,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = altrixaRounded(context, AltrixaColors.background, AltrixaDimens.radiusSm, AltrixaColors.border)
        setPadding(context.dpToPx(3), context.dpToPx(3), context.dpToPx(3), context.dpToPx(3))
    }
    val labels = mutableListOf<TextView>()

    fun paint(selected: Int) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            label.setTextColor(if (on) AltrixaColors.textPrimary else AltrixaColors.textSecondary)
            label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            label.background = if (on) {
                altrixaRounded(context, AltrixaColors.accent, AltrixaDimens.radiusSm - 2f)
            } else {
                null
            }
        }
    }

    options.forEachIndexed { index, option ->
        val label = TextView(context).apply {
            text = option
            textSize = AltrixaDimens.textBody
            gravity = Gravity.CENTER
            minHeight = context.dpToPx(38)
            isClickable = true
            setOnClickListener {
                paint(index)
                onSelect(index)
            }
        }
        labels += label
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    paint(selectedIndex)
    return row
}

/** Rounded progress bar with an accent gradient fill. */
class AltrixaProgressBar(context: Context) : View(context) {

    private var progress = 0
    private var fillColor: Int? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AltrixaColors.background }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setProgress(value: Int) {
        progress = value.coerceIn(0, 100)
        invalidate()
    }

    /** Solid colour override; null restores the accent gradient. */
    fun setSolidColor(color: Int?) {
        fillColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        if (progress <= 0) return
        val fillWidth = max(h, w * progress / 100f)
        val solid = fillColor
        fillPaint.shader = if (solid != null) {
            null
        } else {
            LinearGradient(
                0f, 0f, fillWidth, 0f,
                AltrixaColors.accent, AltrixaColors.accentBright,
                Shader.TileMode.CLAMP
            )
        }
        if (solid != null) fillPaint.color = solid
        rect.set(0f, 0f, fillWidth, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }
}

/** Two-part bar showing wins (green) against losses (red). */
class AltrixaSplitBar(context: Context) : View(context) {

    private var wins = 0
    private var losses = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setCounts(wins: Int, losses: Int) {
        this.wins = wins
        this.losses = losses
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f
        val total = wins + losses

        if (total <= 0) {
            paint.color = AltrixaColors.surfaceVariant
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
            return
        }

        val gap = h / 2f
        val winWidth = w * wins / total
        paint.color = AltrixaColors.negative
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (wins > 0) {
            paint.color = AltrixaColors.positive
            rect.set(0f, 0f, if (losses > 0) max(h, winWidth - gap / 2f) else w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}

/** Square check indicator used for strategy selection. */
class AltrixaCheckIndicator(context: Context) : View(context) {

    private var checked = false
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(1.5f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = AltrixaColors.textPrimary
    }
    private val rect = RectF()
    private val tick = Path()

    fun setChecked(value: Boolean) {
        checked = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dpToPx(22)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val inset = strokePaint.strokeWidth / 2f
        rect.set(inset, inset, w - inset, w - inset)
        val radius = context.dpToPxF(6f)

        boxPaint.color = if (checked) AltrixaColors.accent else Color.TRANSPARENT
        strokePaint.color = if (checked) AltrixaColors.accentBright else AltrixaColors.borderStrong
        canvas.drawRoundRect(rect, radius, radius, boxPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        if (checked) {
            tick.reset()
            tick.moveTo(w * 0.27f, w * 0.52f)
            tick.lineTo(w * 0.44f, w * 0.68f)
            tick.lineTo(w * 0.74f, w * 0.34f)
            canvas.drawPath(tick, tickPaint)
        }
    }
}

enum class AltrixaIconKind { CHECK, ALERT, CHART, STOP }

/** Tinted circular status icon drawn with Canvas paths (no icon assets needed). */
class AltrixaIconView(context: Context, private val kind: AltrixaIconKind, private val tint: Int) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = altrixaTint(tint, 34) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(1.5f)
        color = altrixaTint(tint, 110)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpToPxF(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dpToPx(56)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val c = w / 2f
        val r = c - ringPaint.strokeWidth
        canvas.drawCircle(c, c, r, fillPaint)
        canvas.drawCircle(c, c, r, ringPaint)

        path.reset()
        when (kind) {
            AltrixaIconKind.CHECK -> {
                path.moveTo(w * 0.30f, w * 0.52f)
                path.lineTo(w * 0.44f, w * 0.66f)
                path.lineTo(w * 0.71f, w * 0.36f)
                canvas.drawPath(path, glyphPaint)
            }
            AltrixaIconKind.ALERT -> {
                path.moveTo(c, w * 0.28f)
                path.lineTo(c, w * 0.56f)
                canvas.drawPath(path, glyphPaint)
                canvas.drawCircle(c, w * 0.70f, context.dpToPxF(1.8f), dotPaint)
            }
            AltrixaIconKind.CHART -> {
                path.moveTo(w * 0.27f, w * 0.66f)
                path.lineTo(w * 0.41f, w * 0.50f)
                path.lineTo(w * 0.54f, w * 0.58f)
                path.lineTo(w * 0.73f, w * 0.33f)
                canvas.drawPath(path, glyphPaint)
            }
            AltrixaIconKind.STOP -> {
                path.moveTo(w * 0.36f, w * 0.36f)
                path.lineTo(w * 0.64f, w * 0.64f)
                path.moveTo(w * 0.64f, w * 0.36f)
                path.lineTo(w * 0.36f, w * 0.64f)
                canvas.drawPath(path, glyphPaint)
            }
        }
    }
}

/** Centered premium empty/error/status block: icon, title, message. */
fun altrixaStatusBlock(
    context: Context,
    kind: AltrixaIconKind,
    tint: Int,
    title: String,
    message: String
): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(
        context.dpToPx(AltrixaDimens.spaceMd),
        context.dpToPx(AltrixaDimens.spaceLg),
        context.dpToPx(AltrixaDimens.spaceMd),
        context.dpToPx(AltrixaDimens.spaceLg)
    )
    addView(AltrixaIconView(context, kind, tint))
    addView(
        TextView(context).apply {
            text = title
            textSize = AltrixaDimens.textSection + 1f
            setTextColor(AltrixaColors.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        },
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceMd) }
    )
    addView(
        TextView(context).apply {
            text = message
            textSize = AltrixaDimens.textBody
            setTextColor(AltrixaColors.textSecondary)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        },
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceXs + 2) }
    )
}
