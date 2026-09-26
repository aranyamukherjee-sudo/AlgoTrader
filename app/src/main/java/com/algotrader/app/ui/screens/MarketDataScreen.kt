package com.algotrader.app.ui.screens

import android.content.Context
import android.widget.LinearLayout
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaLabel
import com.algotrader.app.ui.components.altrixaSectionHeader
import com.algotrader.app.ui.components.altrixaStatusBadge
import com.algotrader.app.ui.components.altrixaTitle

/**
 * Market Data screen (Part 1 — Foundation).
 *
 * Same content as before (the 3 streamed indices + provider), restyled onto
 * ALTRIXA cards. The one behavior change: the status badge now reflects the
 * real WebSocket connection state passed in from MainActivity
 * (`quotesWebSocket != null`) instead of a hardcoded "Streaming: Connected"
 * string that was always shown regardless of the actual connection.
 */
object MarketDataScreen {

    fun render(context: Context, container: LinearLayout, isLiveConnected: Boolean) {
        val topGap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dpToPx(4) }

        container.addView(altrixaTitle(context, "Market Data"))
        container.addView(altrixaLabel(context, "Market data configuration and instruments"))

        container.addView(altrixaSectionHeader(context, "Indices"))
        val indices = altrixaCard(context)
        indices.addView(altrixaLabel(context, "NIFTY 50"))
        indices.addView(altrixaLabel(context, "BANK NIFTY"))
        indices.addView(altrixaLabel(context, "SENSEX"))
        container.addView(indices, topGap)

        container.addView(altrixaSectionHeader(context, "Data Status"))
        val status = altrixaCard(context)
        status.addView(altrixaLabel(context, "Provider: FYERS"))
        status.addView(altrixaLabel(context, "Indices: NIFTY 50 \u2022 BANK NIFTY \u2022 SENSEX"))
        status.addView(
            altrixaStatusBadge(
                context,
                if (isLiveConnected) "STREAMING" else "RECONNECTING\u2026",
                if (isLiveConnected) AltrixaTone.POSITIVE else AltrixaTone.WARNING
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(6) }
        )
        container.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(4) }
        )
    }
}
