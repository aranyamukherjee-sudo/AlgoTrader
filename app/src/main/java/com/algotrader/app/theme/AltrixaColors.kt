package com.algotrader.app.theme

import android.graphics.Color

/**
 * ALTRIXA design tokens — colors.
 *
 * Part 1 (Foundation): every value here is copied verbatim from the literal
 * `Color.rgb(...)` / hex constants that were previously scattered across
 * `MainActivity.kt` (root background, bottom nav, header text, chart paints,
 * BUY/SELL buttons, etc). Centralizing them here does not change anything
 * already on screen — it only gives every screen/component/chart one shared
 * source of truth instead of repeating literals.
 *
 * Note: these are plain `val`, not `const val`. `Color.rgb(...)` and
 * `0xFF....toInt()` are function calls, not compile-time constant
 * expressions, so `const val` would fail to compile (same reasoning the
 * original chart code already documented).
 */
object AltrixaColors {

    // ---- Surfaces ----
    /** App root background. */
    val background = Color.rgb(10, 14, 20)

    /** Bottom nav bar / elevated surfaces (cards). */
    val surface = Color.rgb(20, 25, 32)

    /** Unselected chip/selector background. */
    val surfaceVariant = Color.rgb(30, 36, 46)

    /** Subtle card/divider border (new token — no prior equivalent existed). */
    val border = Color.rgb(40, 47, 58)

    // ---- Text ----
    val textPrimary = Color.WHITE
    val textLabel = Color.LTGRAY

    /** Unselected chip/selector text. */
    val textSecondary = Color.rgb(180, 188, 200)

    /** Header subtitle ("NIFTY 50 · 5m"). */
    val textMuted = Color.rgb(160, 170, 185)

    /** Time-range row under the chart. */
    val textFaint = Color.rgb(120, 128, 140)

    // ---- Accent / semantic state ----
    /** Selected chip/selector background. */
    val accent = Color.rgb(41, 121, 255)

    /** Header price-change text when the move is positive. */
    val positive = Color.rgb(60, 200, 120)

    /** Header price-change text when negative; also SELL button / bearish candles. */
    val negative = Color.rgb(239, 83, 80)

    /** BUY button / bullish candles (a distinct green from [positive]). */
    val bullish = Color.rgb(38, 166, 91)

    /** Alias kept for readability at bearish/SELL call sites — same value as [negative]. */
    val bearish = negative

    /** Header connection/status line (e.g. reload/backoff messages). */
    val warning = Color.rgb(200, 160, 60)

    // ---- Chart-only (candlestick / volume / RSI panels) ----
    val chartBackground = 0xFF0D1117.toInt()
    val chartGrid = 0xFF262B33.toInt()
    val chartEma20 = 0xFF29B6F6.toInt()
    val chartEma50 = 0xFFFFA726.toInt()
    val chartPriceLine = 0xFF4DD0E1.toInt()
    val chartAxisText = 0xFF8B93A1.toInt()
}
