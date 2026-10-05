package com.algotrader.app.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.algotrader.app.theme.AltrixaColors
import com.algotrader.app.theme.AltrixaDimens
import com.algotrader.app.theme.dpToPx
import com.algotrader.app.ui.components.AltrixaTone
import com.algotrader.app.ui.components.altrixaBanner
import com.algotrader.app.ui.components.altrixaCaption
import com.algotrader.app.ui.components.altrixaDivider
import com.algotrader.app.ui.components.altrixaInput
import com.algotrader.app.ui.components.altrixaPrimaryButton
import com.algotrader.app.ui.components.altrixaRounded
import com.algotrader.app.ui.components.altrixaSecondaryButton
import com.algotrader.app.ui.components.altrixaStyleBanner
import com.algotrader.app.ui.components.altrixaStyleInput

/**
 * Premium ALTRIXA dialog for pasting the temporary FYERS authorization code.
 *
 * This class is presentation only. It validates that a code was entered and
 * hands it to [onSubmit]; the OAuth exchange itself stays in MainActivity
 * exactly as before. MainActivity reports the outcome back through
 * [setLoading], [showError] and [showSuccessAndDismiss].
 */
class FyersAuthDialog(
    private val activity: Activity,
    private val onSubmit: (authCode: String, dialog: FyersAuthDialog) -> Unit
) {

    private val dialog = Dialog(activity)
    private val input: EditText
    private val status: TextView
    private val spinnerRow: LinearLayout
    private val continueButton: Button
    private val cancelButton: Button
    private val pasteButton: TextView

    private var loading = false

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = altrixaRounded(activity, AltrixaColors.surface, 20f, AltrixaColors.borderStrong)
            setPadding(
                activity.dpToPx(AltrixaDimens.spaceXl - 2),
                activity.dpToPx(AltrixaDimens.spaceXl - 2),
                activity.dpToPx(AltrixaDimens.spaceXl - 2),
                activity.dpToPx(AltrixaDimens.spaceLg + 2)
            )
        }

        // ---- Header ----
        card.addView(altrixaCaption(activity, "ALTRIXA \u00b7 FYERS", AltrixaColors.accent))
        card.addView(
            TextView(activity).apply {
                text = "Authorize FYERS"
                textSize = AltrixaDimens.textTitle
                setTextColor(AltrixaColors.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
            },
            params(topMargin = AltrixaDimens.spaceXs)
        )
        card.addView(
            TextView(activity).apply {
                text = "Finish signing in to restore live market data."
                textSize = AltrixaDimens.textBody
                setTextColor(AltrixaColors.textSecondary)
            },
            params(topMargin = AltrixaDimens.spaceXs)
        )

        // ---- Steps ----
        val steps = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = altrixaRounded(activity, AltrixaColors.surfaceVariant, AltrixaDimens.radiusMd, AltrixaColors.border)
            setPadding(
                activity.dpToPx(AltrixaDimens.spaceMd),
                activity.dpToPx(AltrixaDimens.spaceMd),
                activity.dpToPx(AltrixaDimens.spaceMd),
                activity.dpToPx(AltrixaDimens.spaceMd)
            )
        }
        steps.addView(stepRow("1", "Complete the FYERS login and MFA in your browser."))
        steps.addView(stepRow("2", "Copy the temporary auth code FYERS shows you."), params(topMargin = AltrixaDimens.spaceSm))
        steps.addView(stepRow("3", "Paste it below and continue."), params(topMargin = AltrixaDimens.spaceSm))
        card.addView(steps, params(topMargin = AltrixaDimens.spaceLg))

        // ---- Input ----
        val labelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(
            altrixaCaption(activity, "Authorization code"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        pasteButton = TextView(activity).apply {
            text = "PASTE"
            textSize = AltrixaDimens.textCaption
            setTextColor(AltrixaColors.accentBright)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            isClickable = true
            setPadding(
                activity.dpToPx(AltrixaDimens.spaceSm),
                activity.dpToPx(AltrixaDimens.spaceXs),
                0,
                activity.dpToPx(AltrixaDimens.spaceXs)
            )
        }
        labelRow.addView(pasteButton)
        card.addView(labelRow, params(topMargin = AltrixaDimens.spaceLg))

        input = altrixaInput(activity, "", numeric = false).apply {
            hint = "Paste FYERS auth code"
            // Same input type as the previous dialog: single-line, visible text.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        card.addView(input, params(topMargin = AltrixaDimens.spaceXs + 2))

        // ---- Status (error / success) ----
        status = altrixaBanner(activity, "", AltrixaTone.NEGATIVE).apply { visibility = View.GONE }
        card.addView(status, params(topMargin = AltrixaDimens.spaceMd))

        spinnerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        spinnerRow.addView(
            ProgressBar(activity, null, android.R.attr.progressBarStyleSmall),
            LinearLayout.LayoutParams(activity.dpToPx(18), activity.dpToPx(18))
        )
        spinnerRow.addView(
            TextView(activity).apply {
                text = "Verifying with ALTRIXA server\u2026"
                textSize = AltrixaDimens.textSmall
                setTextColor(AltrixaColors.textSecondary)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = activity.dpToPx(AltrixaDimens.spaceSm) }
        )
        card.addView(spinnerRow, params(topMargin = AltrixaDimens.spaceMd))

        // ---- Buttons ----
        card.addView(altrixaDivider(activity))
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        cancelButton = altrixaSecondaryButton(activity, "Cancel") { dialog.dismiss() }
        continueButton = altrixaPrimaryButton(activity, "Continue") { submit() }
        buttons.addView(
            cancelButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = activity.dpToPx(AltrixaDimens.spaceSm)
            }
        )
        buttons.addView(continueButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        card.addView(buttons, params())

        card.addView(
            TextView(activity).apply {
                text = "The code is sent securely to the ALTRIXA server and is never stored on this device."
                textSize = AltrixaDimens.textCaption
                setTextColor(AltrixaColors.textFaint)
                gravity = Gravity.CENTER
            },
            params(topMargin = AltrixaDimens.spaceMd)
        )

        pasteButton.setOnClickListener { pasteFromClipboard() }
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(card)
        }
        dialog.setContentView(scroll)
    }

    private fun params(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = activity.dpToPx(topMargin) }

    private fun stepRow(number: String, text: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(activity).apply {
                    this.text = number
                    textSize = AltrixaDimens.textSmall
                    setTextColor(AltrixaColors.textPrimary)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = altrixaRounded(activity, AltrixaColors.accent, 100f)
                },
                LinearLayout.LayoutParams(activity.dpToPx(22), activity.dpToPx(22))
            )
            addView(
                TextView(activity).apply {
                    this.text = text
                    textSize = AltrixaDimens.textBody
                    setTextColor(AltrixaColors.textLabel)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = activity.dpToPx(AltrixaDimens.spaceMd)
                }
            )
        }

    private fun pasteFromClipboard() {
        if (loading) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            input.setText(text)
            input.setSelection(input.text.length)
            clearStatus()
        }
    }

    private fun submit() {
        if (loading) return
        val authCode = input.text?.toString()?.trim().orEmpty()
        if (authCode.isBlank()) {
            altrixaStyleInput(input, hasError = true)
            showStatus("Auth code is required.", AltrixaTone.NEGATIVE)
            return
        }
        altrixaStyleInput(input, hasError = false)
        clearStatus()
        setLoading(true)
        onSubmit(authCode, this)
    }

    private fun showStatus(message: String, tone: AltrixaTone) {
        status.text = message
        altrixaStyleBanner(status, tone)
        status.visibility = View.VISIBLE
    }

    private fun clearStatus() {
        status.visibility = View.GONE
    }

    // -----------------------------------------------------------------
    // API used by MainActivity
    // -----------------------------------------------------------------

    fun show() {
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }
        input.requestFocus()
    }

    fun dismiss() {
        try {
            if (dialog.isShowing) dialog.dismiss()
        } catch (_: Exception) {
            // The activity may already be finishing; nothing to clean up.
        }
    }

    /** Disables the form while the code is being verified. Cancel stays available. */
    fun setLoading(isLoading: Boolean) {
        loading = isLoading
        input.isEnabled = !isLoading
        pasteButton.isEnabled = !isLoading
        continueButton.isEnabled = !isLoading
        continueButton.alpha = if (isLoading) 0.55f else 1f
        continueButton.text = if (isLoading) "Verifying\u2026" else "Continue"
        spinnerRow.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) clearStatus()
    }

    /** Re-enables the form and shows [message] inline. */
    fun showError(message: String) {
        setLoading(false)
        altrixaStyleInput(input, hasError = true)
        showStatus(message, AltrixaTone.NEGATIVE)
    }

    /** Shows a success state briefly, then closes the dialog. */
    fun showSuccessAndDismiss(message: String) {
        loading = true
        input.isEnabled = false
        continueButton.isEnabled = false
        cancelButton.isEnabled = false
        spinnerRow.visibility = View.GONE
        continueButton.text = "Connected"
        showStatus(message, AltrixaTone.POSITIVE)
        input.postDelayed({ dismiss() }, 900L)
    }
}
