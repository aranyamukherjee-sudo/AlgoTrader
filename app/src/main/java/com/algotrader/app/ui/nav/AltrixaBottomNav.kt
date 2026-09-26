package com.algotrader.app.ui.nav

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx

/** The app's five top-level destinations, in the same order the old bottom nav used. */
enum class AltrixaDestination(val label: String) {
    HOME("Home"),
    MARKET_DATA("Market Data"),
    STRATEGIES("Strategies"),
    EXECUTION("Execution"),
    BACKTEST("Backtest")
}

/**
 * Reusable bottom navigation bar for the five ALTRIXA destinations.
 *
 * Part 1 (Foundation): replaces MainActivity's previous manually-rebuilt
 * `bottomNav` LinearLayout + `addNavButton()` loop with a single reusable
 * component. Behavior is unchanged — selecting a destination still just
 * invokes [onSelect], which MainActivity wires to its existing
 * `showHome()`/`showMarketData()`/etc. functions. The one visible addition
 * is that the active tab is now highlighted, which the old bar never did.
 */
class AltrixaBottomNav(
    context: Context,
    private val onSelect: (AltrixaDestination) -> Unit
) : LinearLayout(context) {

    private val buttons = mutableMapOf<AltrixaDestination, Button>()
    private var selected: AltrixaDestination = AltrixaDestination.HOME

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(
            context.dpToPx(2), context.dpToPx(6),
            context.dpToPx(2), context.dpToPx(6)
        )
        setBackgroundColor(AltrixaColors.surface)

        AltrixaDestination.values().forEach { destination ->
            val button = Button(context).apply {
                text = destination.label
                textSize = AltrixaDimens.textCaption
                setOnClickListener {
                    setSelected(destination)
                    onSelect(destination)
                }
            }
            buttons[destination] = button
            addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        applySelectedStyle()
    }

    /** Updates which tab is highlighted without invoking [onSelect] again — call this from each showX(). */
    fun setSelected(destination: AltrixaDestination) {
        selected = destination
        applySelectedStyle()
    }

    private fun applySelectedStyle() {
        buttons.forEach { (destination, button) ->
            val isActive = destination == selected
            button.setTextColor(if (isActive) AltrixaColors.accent else AltrixaColors.textSecondary)
            button.setTypeface(button.typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
