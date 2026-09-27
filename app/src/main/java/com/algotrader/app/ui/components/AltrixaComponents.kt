package com.algotrader.app.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.theme.dpToPxF

enum class AltrixaTone { NEUTRAL, POSITIVE, NEGATIVE, WARNING, ACCENT }

private fun toneColor(tone: AltrixaTone): Int = when (tone) {
    AltrixaTone.NEUTRAL -> AltrixaColors.textSecondary
    AltrixaTone.POSITIVE -> AltrixaColors.positive
    AltrixaTone.NEGATIVE -> AltrixaColors.negative
    AltrixaTone.WARNING -> AltrixaColors.warning
    AltrixaTone.ACCENT -> AltrixaColors.accent
}

private fun roundedBackground(
    context: Context,
    fill: Int,
    radius: Float,
    strokeColor: Int? = null,
    strokeWidth: Int = 1
): GradientDrawable {
    return GradientDrawable().apply {
        setColor(fill)
        cornerRadius = context.dpToPxF(radius)
        if (strokeColor != null) {
            setStroke(context.dpToPx(strokeWidth), strokeColor)
        }
    }
}

fun altrixaTitle(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = AltrixaDimens.textTitle
    setTextColor(AltrixaColors.textPrimary)
    setTypeface(typeface, Typeface.BOLD)
    letterSpacing = 0.01f
    setPadding(0, 0, 0, context.dpToPx(AltrixaDimens.spaceMd))
}

fun altrixaSectionHeader(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = AltrixaDimens.textSection
    setTextColor(AltrixaColors.textPrimary)
    setTypeface(typeface, Typeface.BOLD)
    letterSpacing = 0.02f
    setPadding(
        0,
        context.dpToPx(AltrixaDimens.spaceMd),
        0,
        context.dpToPx(AltrixaDimens.spaceSm)
    )
}

fun altrixaLabel(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = AltrixaDimens.textBody
    setTextColor(AltrixaColors.textLabel)
    setPadding(
        0,
        context.dpToPx(AltrixaDimens.spaceXs),
        0,
        context.dpToPx(AltrixaDimens.spaceXs)
    )
}

fun altrixaCard(context: Context): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(
            context,
            AltrixaColors.surface,
            AltrixaDimens.radiusLg,
            AltrixaColors.border,
            1
        )
        setPadding(
            context.dpToPx(AltrixaDimens.spaceLg),
            context.dpToPx(AltrixaDimens.spaceLg),
            context.dpToPx(AltrixaDimens.spaceLg),
            context.dpToPx(AltrixaDimens.spaceLg)
        )
        elevation = context.dpToPxF(AltrixaDimens.cardElevation)
        clipToOutline = true
    }
}

fun altrixaMetricCard(
    context: Context,
    label: String,
    value: String,
    valueTone: AltrixaTone = AltrixaTone.NEUTRAL
): LinearLayout {
    val card = altrixaCard(context)

    card.addView(
        TextView(context).apply {
            text = label.uppercase()
            textSize = AltrixaDimens.textCaption
            setTextColor(AltrixaColors.textFaint)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        }
    )

    card.addView(
        TextView(context).apply {
            text = value
            textSize = AltrixaDimens.textLarge
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(toneColor(valueTone))
            setPadding(
                0,
                context.dpToPx(AltrixaDimens.spaceXs),
                0,
                0
            )
        }
    )

    return card
}

fun altrixaStatusBadge(
    context: Context,
    text: String,
    tone: AltrixaTone = AltrixaTone.NEUTRAL
): TextView {
    val color = toneColor(tone)

    return TextView(context).apply {
        this.text = text.uppercase()
        textSize = AltrixaDimens.textCaption
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f

        background = roundedBackground(
            context,
            Color.argb(
                32,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            ),
            AltrixaDimens.radiusPill,
            Color.argb(
                70,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            ),
            1
        )

        setPadding(
            context.dpToPx(AltrixaDimens.spaceMd),
            context.dpToPx(AltrixaDimens.spaceXs),
            context.dpToPx(AltrixaDimens.spaceMd),
            context.dpToPx(AltrixaDimens.spaceXs)
        )

        gravity = Gravity.CENTER
        minHeight = context.dpToPx(28)
    }
}

fun altrixaChip(
    context: Context,
    text: String,
    selected: Boolean,
    onClick: () -> Unit
): Button {
    return Button(context).apply {
        this.text = text
        textSize = AltrixaDimens.textCaption
        isAllCaps = false
        stateListAnimator = null
        minHeight = context.dpToPx(34)
        minWidth = context.dpToPx(52)

        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = 0.02f

        setPadding(
            context.dpToPx(AltrixaDimens.spaceMd),
            0,
            context.dpToPx(AltrixaDimens.spaceMd),
            0
        )

        setTextColor(
            if (selected) {
                AltrixaColors.textPrimary
            } else {
                AltrixaColors.textSecondary
            }
        )

        background = roundedBackground(
            context,
            if (selected) {
                AltrixaColors.accent
            } else {
                AltrixaColors.surfaceVariant
            },
            AltrixaDimens.radiusPill,
            if (selected) {
                AltrixaColors.accentBright
            } else {
                AltrixaColors.border
            },
            1
        )

        elevation = if (selected) context.dpToPxF(2f) else 0f

        setOnClickListener { onClick() }
    }
}

fun altrixaPrimaryButton(
    context: Context,
    text: String,
    backgroundColor: Int = AltrixaColors.accent,
    onClick: () -> Unit
): Button {
    return Button(context).apply {
        this.text = text
        textSize = AltrixaDimens.textSubtitle
        isAllCaps = false
        stateListAnimator = null
        minHeight = context.dpToPx(46)

        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.02f
        setTextColor(AltrixaColors.textPrimary)

        background = roundedBackground(
            context,
            backgroundColor,
            AltrixaDimens.radiusSm,
            Color.argb(90, 255, 255, 255),
            1
        )

        elevation = context.dpToPxF(2f)
        setOnClickListener { onClick() }
    }
}

fun altrixaSecondaryButton(
    context: Context,
    text: String,
    onClick: () -> Unit
): Button {
    return Button(context).apply {
        this.text = text
        textSize = AltrixaDimens.textSubtitle
        isAllCaps = false
        stateListAnimator = null
        minHeight = context.dpToPx(44)

        setTypeface(typeface, Typeface.BOLD)
        setTextColor(AltrixaColors.textPrimary)

        background = roundedBackground(
            context,
            AltrixaColors.surfaceVariant,
            AltrixaDimens.radiusSm,
            AltrixaColors.borderStrong,
            1
        )

        setOnClickListener { onClick() }
    }
}

fun altrixaLoadingState(
    context: Context,
    message: String
): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            0,
            context.dpToPx(AltrixaDimens.spaceXl),
            0,
            context.dpToPx(AltrixaDimens.spaceXl)
        )

        addView(ProgressBar(context))

        addView(
            TextView(context).apply {
                text = message
                textSize = AltrixaDimens.textBody
                setTextColor(AltrixaColors.textFaint)
                gravity = Gravity.CENTER
                setPadding(
                    0,
                    context.dpToPx(AltrixaDimens.spaceSm),
                    0,
                    0
                )
            }
        )
    }
}

fun altrixaEmptyState(
    context: Context,
    message: String
): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            0,
            context.dpToPx(AltrixaDimens.spaceXl),
            0,
            context.dpToPx(AltrixaDimens.spaceXl)
        )

        addView(
            TextView(context).apply {
                text = message
                textSize = AltrixaDimens.textBody
                setTextColor(AltrixaColors.textFaint)
                gravity = Gravity.CENTER
            }
        )
    }
}

fun altrixaErrorState(
    context: Context,
    message: String,
    onRetry: (() -> Unit)? = null
): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            0,
            context.dpToPx(AltrixaDimens.spaceXl),
            0,
            context.dpToPx(AltrixaDimens.spaceXl)
        )

        addView(
            TextView(context).apply {
                text = message
                textSize = AltrixaDimens.textBody
                setTextColor(AltrixaColors.negative)
                gravity = Gravity.CENTER
            }
        )

        if (onRetry != null) {
            addView(
                altrixaSecondaryButton(context, "Retry") { onRetry() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = context.dpToPx(AltrixaDimens.spaceMd)
                    gravity = Gravity.CENTER
                }
            )
        }
    }
}
