package com.algotrader.intelligence.confidence

import com.algotrader.domain.Instrument
import com.algotrader.intelligence.TestFixtures.at
import com.algotrader.strategy.Signal
import com.algotrader.strategy.SignalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfidenceTest {

    @Test
    fun `scale is the existing 0 to 1 signal scale`() {
        assertTrue(ConfidenceScale.isValid(0.0))
        assertTrue(ConfidenceScale.isValid(1.0))
        assertTrue(ConfidenceScale.isValid(0.68))
        assertTrue(!ConfidenceScale.isValid(-0.01))
        assertTrue(!ConfidenceScale.isValid(1.01))
        assertTrue(!ConfidenceScale.isValid(Double.NaN))
        assertTrue(!ConfidenceScale.isValid(Double.POSITIVE_INFINITY))
        // a 0-100 style value is rejected, not silently accepted
        assertTrue(!ConfidenceScale.isValid(68.0))
    }

    @Test
    fun `readings outside the range are rejected`() {
        assertFailsWith<IllegalArgumentException> { ConfidenceReading(1.2, at(0)) }
        assertFailsWith<IllegalArgumentException> { ConfidenceReading(-0.1, at(0)) }
        assertFailsWith<IllegalArgumentException> { ConfidenceReading(Double.NaN, at(0)) }
    }

    @Test
    fun `a reading can be built from an existing signal without a second representation`() {
        val signal = Signal(
            instrument = Instrument("NIFTY-FUT", "NSE"),
            type = SignalType.BUY,
            timestamp = at(5),
            confidence = 0.42,
            reason = "RSI=25"
        )
        val reading = ConfidenceReading.fromSignal(signal)

        assertEquals(0.42, reading.value)
        assertEquals(at(5), reading.at)
        assertEquals("RSI=25", reading.reason)
        // a signal whose confidence is not on the shared scale is refused
        assertFailsWith<IllegalArgumentException> {
            ConfidenceReading.fromSignal(signal.copy(confidence = 70.0))
        }
    }

    @Test
    fun `confidence changes over time and history is preserved`() {
        var history = ConfidenceHistory()
        assertNull(history.current)
        assertEquals(ConfidenceTrend.UNKNOWN, history.trend)

        history = history.record(ConfidenceReading(0.68, at(0), "setup forming"))
        assertEquals(0.68, history.current)
        assertNull(history.change)

        history = history.record(ConfidenceReading(0.76, at(1), "approaching trigger"))
        assertEquals(ConfidenceTrend.RISING, history.trend)

        history = history.record(ConfidenceReading(0.84, at(2), "entry confirmed"))
        history = history.record(ConfidenceReading(0.72, at(3), "conditions weakening"))

        assertEquals(0.72, history.current)
        assertEquals(0.84, history.previous)
        assertEquals(-0.12, history.change!!, 1e-9)
        assertEquals(ConfidenceTrend.FALLING, history.trend)
        assertEquals(4, history.readings.size)
    }

    @Test
    fun `equal consecutive readings are steady`() {
        val history = ConfidenceHistory()
            .record(ConfidenceReading(0.5, at(0)))
            .record(ConfidenceReading(0.5, at(1)))
        assertEquals(ConfidenceTrend.STEADY, history.trend)
    }

    @Test
    fun `readings must be chronological`() {
        val history = ConfidenceHistory().record(ConfidenceReading(0.5, at(5)))
        assertFailsWith<IllegalArgumentException> { history.record(ConfidenceReading(0.6, at(1))) }
    }
}
