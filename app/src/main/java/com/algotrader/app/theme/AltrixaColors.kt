package com.algotrader.app.theme

import android.graphics.Color

/**
 * ALTRIXA visual design tokens.
 *
 * These values intentionally create a deeper terminal-style hierarchy:
 * near-black application background, elevated graphite surfaces, restrained
 * borders, and a bright cyan/blue trading accent.
 */
object AltrixaColors {

    // ---- Surfaces ----
    val background = Color.rgb(7, 10, 15)
    val surface = Color.rgb(15, 20, 27)
    val surfaceVariant = Color.rgb(23, 29, 38)
    val surfaceElevated = Color.rgb(19, 25, 33)

    val border = Color.rgb(38, 47, 59)
    val borderStrong = Color.rgb(55, 66, 82)

    // ---- Text ----
    val textPrimary = Color.rgb(245, 248, 252)
    val textLabel = Color.rgb(190, 198, 210)
    val textSecondary = Color.rgb(148, 158, 173)
    val textMuted = Color.rgb(119, 130, 146)
    val textFaint = Color.rgb(86, 97, 113)

    // ---- Accent / semantic state ----
    val accent = Color.rgb(37, 129, 255)
    val accentBright = Color.rgb(74, 157, 255)

    val positive = Color.rgb(45, 205, 125)
    val negative = Color.rgb(245, 82, 82)
    val bullish = Color.rgb(35, 181, 99)
    val bearish = negative
    val warning = Color.rgb(224, 171, 67)

    // ---- Chart ----
    val chartBackground = 0xFF0A0E14.toInt()
    val chartGrid = 0xFF202733.toInt()
    val chartEma20 = 0xFF29B6F6.toInt()
    val chartEma50 = 0xFFFFA726.toInt()
    val chartPriceLine = 0xFF4DD0E1.toInt()
    val chartAxisText = 0xFF7F8999.toInt()
}
