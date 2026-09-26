package com.algotrader.app.ui.screens

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaCard
import com.algotrader.app.ui.components.altrixaEmptyState
import com.algotrader.app.ui.components.altrixaLabel
import com.algotrader.app.ui.components.altrixaSecondaryButton
import com.algotrader.app.ui.components.altrixaSectionHeader
import com.algotrader.app.ui.components.altrixaStatusBadge
import com.algotrader.app.ui.components.altrixaTitle

/**
 * Execution screen (Part 1.8 — UI Foundation).
 *
 * This is a UI-only pass. `core:execution` currently has no source files —
 * there is no order-placement, position, or trade-history API anywhere in
 * the app to read from yet. So, in line with `AltrixaComponents`' state
 * placeholders:
 *
 *  - Account/mode strip: descriptive UI state only (paper mode, no broker,
 *    local account) — not simulated broker data.
 *  - Order Entry: fields are rendered read-only/disabled and the primary
 *    action button is disabled, since there is no execution API to safely
 *    wire a real "place order" action into without fabricating a result.
 *  - Open Positions / Orders / Trade Log: honest empty states. No fake
 *    positions, orders, fills, or P&L are ever shown here.
 *
 * When a real `core:execution` API exists, this screen's empty states and
 * disabled Order Entry controls are the intended place to wire it in.
 */
object ExecutionScreen {

    fun render(context: Context, container: LinearLayout) {
        val topGap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dpToPx(4) }

        // ---- Header ----
        container.addView(altrixaTitle(context, "Execution"))
        container.addView(
            altrixaLabel(
                context,
                "Paper-trading execution workspace \u2014 no live broker orders are placed from this screen."
            )
        )

        // ---- Account / Trading Mode ----
        container.addView(altrixaSectionHeader(context, "Account"))
        val account = altrixaCard(context)
        account.addView(statusRow(context, "Mode", "PAPER TRADING", AltrixaTone.ACCENT))
        account.addView(statusRow(context, "Broker", "Not connected", AltrixaTone.WARNING))
        account.addView(statusRow(context, "Account Status", "LOCAL", AltrixaTone.NEUTRAL))
        container.addView(account, topGap)

        // ---- Order Entry ----
        container.addView(altrixaSectionHeader(context, "Order Entry"))
        val orderEntry = altrixaCard(context)
        orderEntry.addView(fieldRow(context, "Instrument", "\u2014"))
        orderEntry.addView(fieldRow(context, "Side", "BUY / SELL"))
        orderEntry.addView(fieldRow(context, "Quantity", "\u2014"))
        orderEntry.addView(fieldRow(context, "Order Type", "MARKET"))
        orderEntry.addView(fieldRow(context, "Price", "\u2014"))

        val placeOrderButton = altrixaSecondaryButton(context, "Place Paper Order") {
            // Intentionally a no-op: no core:execution API exists yet to
            // safely wire this into, and we never fake a successful order.
        }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        orderEntry.addView(
            placeOrderButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceMd) }
        )
        orderEntry.addView(
            altrixaLabel(context, "Order placement is not wired up yet \u2014 no execution engine is connected."),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(AltrixaDimens.spaceXs) }
        )
        container.addView(orderEntry, topGap)

        // ---- Open Positions ----
        container.addView(altrixaSectionHeader(context, "Open Positions"))
        val positions = altrixaCard(context)
        positions.addView(altrixaEmptyState(context, "No open paper positions"))
        container.addView(positions, topGap)

        // ---- Orders ----
        container.addView(altrixaSectionHeader(context, "Orders"))
        val orders = altrixaCard(context)
        orders.addView(altrixaEmptyState(context, "No paper orders yet"))
        container.addView(orders, topGap)

        // ---- Trade Log ----
        container.addView(altrixaSectionHeader(context, "Trade Log"))
        val tradeLog = altrixaCard(context)
        tradeLog.addView(altrixaEmptyState(context, "No trades recorded yet"))
        container.addView(tradeLog, topGap)

        // ---- Safety / Disclaimer ----
        val disclaimer = altrixaCard(context)
        disclaimer.addView(
            altrixaLabel(context, "Paper trading only \u2014 no broker orders are sent from this screen.").apply {
                setTextColor(AltrixaColors.warning)
            }
        )
        container.addView(disclaimer, topGap)
    }

    /** A label + status-badge row, e.g. "Mode" — "PAPER TRADING". */
    private fun statusRow(context: Context, label: String, value: String, tone: AltrixaTone): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                altrixaLabel(context, label),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(altrixaStatusBadge(context, value, tone))
        }
    }

    /** A label + static value row for a presentational (non-editable) form field. */
    private fun fieldRow(context: Context, label: String, value: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                altrixaLabel(context, label),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = AltrixaDimens.textBody
                    setTextColor(AltrixaColors.textSecondary)
                }
            )
        }
    }
}
