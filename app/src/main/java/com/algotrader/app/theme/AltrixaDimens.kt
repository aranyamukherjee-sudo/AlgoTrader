package com.algotrader.app.theme

import android.content.Context

object AltrixaDimens {

    // ---- Spacing (dp) ----
    const val spaceXs = 4
    const val spaceSm = 8
    const val spaceMd = 12
    const val spaceLg = 16
    const val spaceXl = 24

    // ---- Corner radius (dp) ----
    const val radiusSm = 7f
    const val radiusMd = 11f
    const val radiusLg = 15f
    const val radiusPill = 100f

    // ---- Elevation ----
    const val cardElevation = 3f

    // ---- Type scale ----
    const val textCaption = 11f
    const val textSmall = 12f
    const val textBody = 13f
    const val textSubtitle = 14f
    const val textSection = 15f
    const val textLarge = 19f
    const val textTitle = 22f
    const val textDisplay = 28f
}

fun Context.dpToPx(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun Context.dpToPxF(value: Float): Float =
    value * resources.displayMetrics.density
