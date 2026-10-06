package com.algotrader.intelligence.alert

import com.algotrader.intelligence.opportunity.OpportunityState
import com.algotrader.intelligence.opportunity.OpportunityState.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertPreferencesTest {

    private val all = AlertPreferences(
        entryAlertsEnabled = true,
        exitAlertsEnabled = true,
        earlyWarningAlertsEnabled = true,
        minimumConfidence = 0.0
    )

    @Test
    fun `defaults`() {
        val d = AlertPreferences()
        assertTrue(d.entryAlertsEnabled)
        assertTrue(d.exitAlertsEnabled)
        assertFalse(d.earlyWarningAlertsEnabled)
        assertEquals(0.0, d.minimumConfidence)
    }

    @Test
    fun `minimum confidence uses the shared 0 to 1 scale`() {
        assertFailsWith<IllegalArgumentException> { AlertPreferences(minimumConfidence = 70.0) }
        assertFailsWith<IllegalArgumentException> { AlertPreferences(minimumConfidence = -0.1) }
        assertFailsWith<IllegalArgumentException> { AlertPreferences(minimumConfidence = Double.NaN) }
        AlertPreferences(minimumConfidence = 1.0)
    }

    @Test
    fun `each state maps to the right kind of alert`() {
        val expected = mapOf(
            OPPORTUNITY_FOUND to AlertKind.EARLY_WARNING,
            ALERTED to AlertKind.EARLY_WARNING,
            WAITING_FOR_ENTRY to AlertKind.EARLY_WARNING,
            ENTRY_CONDITIONS_MET to AlertKind.EARLY_WARNING, // not yet a confirmed entry
            ENTRY_CONFIRMED to AlertKind.ENTRY,
            EXIT_CONDITIONS_FORMING to AlertKind.EARLY_WARNING, // a warning, not an exit signal
            EXIT_ALERT to AlertKind.EARLY_WARNING,
            EXIT_CONDITIONS_MET to AlertKind.EXIT,
            EXIT_CONFIRMED to AlertKind.EXIT,
            CANCELLED to AlertKind.EARLY_WARNING,
            EXPIRED to AlertKind.EARLY_WARNING
        )
        expected.forEach { (state, kind) -> assertEquals(kind, state.alertKind(), "$state") }
        assertNull(MONITORING.alertKind())
        assertEquals(OpportunityState.values().size, expected.size + 1)
    }

    @Test
    fun `each toggle controls only its own kind`() {
        val entryOnly = AlertPreferences(entryAlertsEnabled = true, exitAlertsEnabled = false, earlyWarningAlertsEnabled = false)
        assertTrue(entryOnly.permits(ENTRY_CONFIRMED, 0.9))
        assertFalse(entryOnly.permits(EXIT_CONDITIONS_MET, 0.9))
        assertFalse(entryOnly.permits(OPPORTUNITY_FOUND, 0.9))

        val exitOnly = AlertPreferences(entryAlertsEnabled = false, exitAlertsEnabled = true, earlyWarningAlertsEnabled = false)
        assertFalse(exitOnly.permits(ENTRY_CONFIRMED, 0.9))
        assertTrue(exitOnly.permits(EXIT_CONFIRMED, 0.9))

        val warningsOnly = AlertPreferences(entryAlertsEnabled = false, exitAlertsEnabled = false, earlyWarningAlertsEnabled = true)
        assertTrue(warningsOnly.permits(OPPORTUNITY_FOUND, 0.9))
        assertTrue(warningsOnly.permits(EXIT_CONDITIONS_FORMING, 0.9))
        assertFalse(warningsOnly.permits(ENTRY_CONFIRMED, 0.9))
    }

    @Test
    fun `monitoring never alerts`() {
        assertFalse(all.permits(MONITORING, 1.0))
    }

    @Test
    fun `minimum confidence gates entry related alerts`() {
        val prefs = all.copy(minimumConfidence = 0.7)
        assertFalse(prefs.permits(ENTRY_CONFIRMED, 0.65))
        assertTrue(prefs.permits(ENTRY_CONFIRMED, 0.7))
        assertTrue(prefs.permits(ENTRY_CONFIRMED, 0.84))
        assertFalse(prefs.permits(OPPORTUNITY_FOUND, 0.5))
        assertFalse(prefs.permits(ENTRY_CONDITIONS_MET, 0.5))
        assertTrue(prefs.permits(WAITING_FOR_ENTRY, 0.8))
    }

    @Test
    fun `an unscored opportunity does not pass a confidence threshold`() {
        val prefs = all.copy(minimumConfidence = 0.5)
        assertFalse(prefs.permits(ENTRY_CONFIRMED, null))
        // with no threshold, unscored is fine
        assertTrue(all.permits(ENTRY_CONFIRMED, null))
    }

    @Test
    fun `exit alerts are never suppressed by the entry confidence threshold`() {
        val strict = all.copy(minimumConfidence = 0.95)
        assertTrue(strict.permits(EXIT_CONDITIONS_MET, 0.10))
        assertTrue(strict.permits(EXIT_CONFIRMED, 0.10))
        assertTrue(strict.permits(EXIT_CONFIRMED, null))
        assertTrue(strict.permits(EXIT_CONDITIONS_FORMING, 0.10))
        assertTrue(strict.permits(EXIT_ALERT, 0.10))
        // and cancellation/expiry notices are not gated either
        assertTrue(strict.permits(CANCELLED, 0.10))
        assertTrue(strict.permits(EXPIRED, null))
    }

    @Test
    fun `exit alerts still respect the exit toggle`() {
        val noExit = all.copy(exitAlertsEnabled = false, minimumConfidence = 0.0)
        assertFalse(noExit.permits(EXIT_CONDITIONS_MET, 1.0))
        assertFalse(noExit.permits(EXIT_CONFIRMED, 1.0))
    }
}
