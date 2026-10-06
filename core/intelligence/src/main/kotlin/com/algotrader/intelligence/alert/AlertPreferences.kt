package com.algotrader.intelligence.alert

import com.algotrader.intelligence.confidence.ConfidenceScale
import com.algotrader.intelligence.opportunity.OpportunityPhase
import com.algotrader.intelligence.opportunity.OpportunityState

enum class AlertKind { ENTRY, EXIT, EARLY_WARNING }

/**
 * Which kind of alert (if any) a state corresponds to.
 * - ENTRY: the entry is confirmed (not merely detected or approaching).
 * - EXIT: exit conditions are met / confirmed (not merely forming).
 * - EARLY_WARNING: heads-ups, including setup found, entry approaching, exit
 *   forming, and an opportunity cancelled/expired after the user may have been told.
 */
fun OpportunityState.alertKind(): AlertKind? = when (this) {
    OpportunityState.OPPORTUNITY_FOUND, OpportunityState.ALERTED,
    OpportunityState.WAITING_FOR_ENTRY, OpportunityState.ENTRY_CONDITIONS_MET,
    OpportunityState.EXIT_CONDITIONS_FORMING, OpportunityState.EXIT_ALERT,
    OpportunityState.CANCELLED, OpportunityState.EXPIRED -> AlertKind.EARLY_WARNING
    OpportunityState.ENTRY_CONFIRMED -> AlertKind.ENTRY
    OpportunityState.EXIT_CONDITIONS_MET, OpportunityState.EXIT_CONFIRMED -> AlertKind.EXIT
    OpportunityState.MONITORING -> null
}

/**
 * User-configurable alert settings (in-memory model; persistence and the
 * notification flow are later sprints).
 *
 * [minimumConfidence] uses the shared 0.0..1.0 confidence scale. It gates only
 * alerts about getting INTO a trade (setup and entry phases). It never gates
 * exit or cancellation alerts: confidence dropping is exactly when the user
 * most needs to hear about an exit.
 */
data class AlertPreferences(
    val entryAlertsEnabled: Boolean = true,
    val exitAlertsEnabled: Boolean = true,
    val earlyWarningAlertsEnabled: Boolean = false,
    val minimumConfidence: Double = 0.0
) {
    init {
        ConfidenceScale.requireValid(minimumConfidence, "minimumConfidence")
    }

    fun isEnabled(kind: AlertKind): Boolean = when (kind) {
        AlertKind.ENTRY -> entryAlertsEnabled
        AlertKind.EXIT -> exitAlertsEnabled
        AlertKind.EARLY_WARNING -> earlyWarningAlertsEnabled
    }

    /**
     * Whether an alert for [state] is permitted. An unscored opportunity
     * ([confidence] null) does not pass a threshold it cannot be compared to.
     */
    fun permits(state: OpportunityState, confidence: Double?): Boolean {
        val kind = state.alertKind() ?: return false
        if (!isEnabled(kind)) return false
        val gated = state.phase == OpportunityPhase.SETUP || state.phase == OpportunityPhase.ENTRY
        if (!gated || minimumConfidence <= 0.0) return true
        return confidence != null && confidence >= minimumConfidence
    }
}
