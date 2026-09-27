package com.algotrader.app.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.theme.dpToPxF

/**
 * ALTRIXA reusable View components (Part 1 — Foundation).
 *
 * These are plain programmatic-View factory functions, matching the app's
 * existing architecture (no Compose introduced here). Every screen should
 * reach for one of these instead of inlining its own colors/paddings/radii.
 */

/** Semantic tone used by [altrixaStatusBadge] and [altrixaChip]. */
enum class AltrixaTone { NEUTRAL, POSITIVE, NEGATIVE, WARNING, ACCENT }

private fun toneColor(tone: AltrixaTone): Int = when (tone) {
    AltrixaTone.NEUTRAL -> AltrixaColors.textSecondary
    AltrixaTone.POSITIVE -> AltrixaColors.positive
    AltrixaTone.NEGATIVE -> AltrixaColors.negative
    AltrixaTone.WARNING -> AltrixaColors.warning
    AltrixaTone.ACCENT -> AltrixaColors.accent
}

// ---------------------------------------------------------------------------
// Typography — canonical replacements for MainActivity's old title()/section()/
// label() private helpers. Visual output is unchanged; only the source of the
// color/size values moved here.
// ---------------------------------------------------------------------------

fun altrixaTitle(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = AltrixaDimens.textTitle
    setTextColor(AltrixaColors.textPrimary)
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, 0, 0, context.dpToPx(AltrixaDimens.spaceMd))
}

fun altrixaSectionHeader(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = AltrixaDimens.textSection
    setTextColor(AltrixaColors.textPrimary)
    setPadding(0, context.dpToPx(14), 0, context.dpToPx(6))
}

fun altrixaLabel(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    textSize = AltrixaDimens.textBody
    setTextColor(AltrixaColors.textLabel)
    setPadding(0, context.dpToPx(3), 0, context.dpToPx(3))
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/** A rounded, subtly-bordered elevated surface — the base ALTRIXA container. */
fun altrixaCard(context: Context): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(AltrixaColors.surface)
            cornerRadius = context.dpToPxF(AltrixaDimens.radiusMd)
            setStroke(1, AltrixaColors.border)
        }
        setPadding(
            context.dpToPx(AltrixaDimens.spaceLg),
            context.dpToPx(AltrixaDimens.spaceMd),
            context.dpToPx(AltrixaDimens.spaceLg),
            context.dpToPx(AltrixaDimens.spaceMd)
        )
    }
}

/** A label/value pair inside a card — e.g. one backtest metric (win rate, net P&L). */
fun altrixaMetricCard(
    context: Context,
    label: String,
    value: String,
    valueTone: AltrixaTone = AltrixaTone.NEUTRAL
): LinearLayout {
    val card = altrixaCard(context)
    card.addView(
        TextView(context).apply {
            text = label
            textSize = AltrixaDimens.textCaption
            setTextColor(AltrixaColors.textFaint)
        }
    )
    card.addView(
        TextView(context).apply {
            text = value
            textSize = AltrixaDimens.textLarge
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(toneColor(valueTone))
            setPadding(0, context.dpToPx(AltrixaDimens.spaceXs), 0, 0)
        }
    )
    return card
}

/** A small pill-shaped status indicator, e.g. "STANDBY", "NOT CONNECTED". */
fun altrixaStatusBadge(context: Context, text: String, tone: AltrixaTone = AltrixaTone.NEUTRAL): TextView {
    val color = toneColor(tone)
    return TextView(context).apply {
        this.text = text
        textSize = AltrixaDimens.textCaption
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = context.dpToPxF(AltrixaDimens.radiusSm)
        }
        setPadding(
            context.dpToPx(AltrixaDimens.spaceSm), context.dpToPx(AltrixaDimens.spaceXs),
            context.dpToPx(AltrixaDimens.spaceSm), context.dpToPx(AltrixaDimens.spaceXs)
        )
        gravity = Gravity.CENTER
    }
}

/** A small selectable pill, e.g. instrument/timeframe selector. Same states as the old applySelectorStyle. */
fun altrixaChip(context: Context, text: String, selected: Boolean, onClick: () -> Unit): Button {
    return Button(context).apply {
        this.text = text
        textSize = AltrixaDimens.textCaption
        isAllCaps = false
        setPadding(context.dpToPx(AltrixaDimens.spaceSm), 0, context.dpToPx(AltrixaDimens.spaceSm), 0)
        setTextColor(if (selected) AltrixaColors.textPrimary else AltrixaColors.textSecondary)
        background = GradientDrawable().apply {
            setColor(if (selected) AltrixaColors.accent else AltrixaColors.surfaceVariant)
            cornerRadius = context.dpToPxF(AltrixaDimens.radiusSm)
        }
        setOnClickListener { onClick() }
    }
}

/** Filled, high-emphasis action button (e.g. BUY/SELL, primary CTA). */
fun altrixaPrimaryButton(
    context: Context,
    text: String,
    backgroundColor: Int = AltrixaColors.accent,
    onClick: () -> Unit
): Button {
    return Button(context).apply {
        this.text = text
        textSize = AltrixaDimens.textSubtitle
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(AltrixaColors.textPrimary)
        background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = context.dpToPxF(AltrixaDimens.radiusSm)
        }
        setOnClickListener { onClick() }
    }
}

/** Outlined, low-emphasis action button (e.g. "Retry"). */
fun altrixaSecondaryButton(context: Context, text: String, onClick: () -> Unit): Button {
    return Button(context).apply {
        this.text = text
        textSize = AltrixaDimens.textSubtitle
        setTextColor(AltrixaColors.textPrimary)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = context.dpToPxF(AltrixaDimens.radiusSm)
            setStroke(2, AltrixaColors.border)
        }
        setOnClickListener { onClick() }
    }
}

// ---------------------------------------------------------------------------
// State placeholders — screens must use these instead of fabricating data.
// ---------------------------------------------------------------------------

/** Centered spinner + message, for a screen actively waiting on real data. */
fun altrixaLoadingState(context: Context, message: String): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, context.dpToPx(AltrixaDimens.spaceXl), 0, context.dpToPx(AltrixaDimens.spaceXl))
        addView(ProgressBar(context))
        addView(
            TextView(context).apply {
                text = message
                textSize = AltrixaDimens.textBody
                setTextColor(AltrixaColors.textFaint)
                gravity = Gravity.CENTER
                setPadding(0, context.dpToPx(AltrixaDimens.spaceSm), 0, 0)
            }
        )
    }
}

/** Centered muted message for "nothing real to show here yet" — never fill this with fabricated data. */
fun altrixaEmptyState(context: Context, message: String): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, context.dpToPx(AltrixaDimens.spaceXl), 0, context.dpToPx(AltrixaDimens.spaceXl))
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

/** Centered error message with an optional retry action. */
fun altrixaErrorState(context: Context, message: String, onRetry: (() -> Unit)? = null): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, context.dpToPx(AltrixaDimens.spaceXl), 0, context.dpToPx(AltrixaDimens.spaceXl))
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
