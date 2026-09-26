package com.algotrader.app.theme

import android.content.Context

/**
 * ALTRIXA design tokens — spacing, corner radius, and the type scale.
 *
 * Part 1 (Foundation): the text sizes here match the literal `textSize`
 * values MainActivity.kt already used for title()/section()/label() and the
 * Home header, so adopting these tokens changes naming only, not rendering.
 * Spacing/radius are a new, small scale for the new reusable components
 * (cards, badges, chips) introduced in this pass.
 */
object AltrixaDimens {

    // ---- Spacing (dp) ----
    const val spaceXs = 4
    const val spaceSm = 8
    const val spaceMd = 12
    const val spaceLg = 16
    const val spaceXl = 24

    // ---- Corner radius (dp) ----
    const val radiusSm = 6f
    const val radiusMd = 10f
    const val radiusLg = 14f

    // ---- Type scale (textSize, sp-equivalent floats as the codebase already used) ----
    const val textCaption = 11f
    const val textSmall = 12f
    const val textBody = 13f
    const val textSubtitle = 14f
    const val textSection = 15f
    const val textLarge = 19f
    const val textTitle = 22f
    const val textDisplay = 28f
}

/** dp -> px, identical formula to MainActivity's original private `dp()` helper. */
fun Context.dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Float variant, needed for drawable corner radii (which take px as Float). */
fun Context.dpToPxF(value: Float): Float = value * resources.displayMetrics.density
