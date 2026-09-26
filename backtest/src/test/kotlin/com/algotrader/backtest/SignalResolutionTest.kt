package com.algotrader.backtest

import com.algotrader.strategy.PositionDirection
import com.algotrader.strategy.SignalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalResolutionTest {

    @Test
    fun `hold never changes the current position`() {
        assertNull(resolveTargetDirection(SignalType.HOLD, null, PositionDirection.LONG_AND_SHORT))
        assertEquals(
            TradeDirection.LONG,
            resolveTargetDirection(SignalType.HOLD, TradeDirection.LONG, PositionDirection.LONG_AND_SHORT)
        )
        assertEquals(
            TradeDirection.SHORT,
            resolveTargetDirection(SignalType.HOLD, TradeDirection.SHORT, PositionDirection.LONG_AND_SHORT)
        )
    }

    @Test
    fun `buy while flat opens long only if the strategy allows going long`() {
        assertEquals(
            TradeDirection.LONG,
            resolveTargetDirection(SignalType.BUY, null, PositionDirection.LONG_ONLY)
        )
        assertNull(resolveTargetDirection(SignalType.BUY, null, PositionDirection.SHORT_ONLY))
    }

    @Test
    fun `buy while already long does not pyramid`() {
        assertEquals(
            TradeDirection.LONG,
            resolveTargetDirection(SignalType.BUY, TradeDirection.LONG, PositionDirection.LONG_ONLY)
        )
    }

    @Test
    fun `buy while short always flattens the short, then reopens long only if allowed`() {
        assertEquals(
            TradeDirection.LONG,
            resolveTargetDirection(SignalType.BUY, TradeDirection.SHORT, PositionDirection.LONG_AND_SHORT)
        )
        assertNull(resolveTargetDirection(SignalType.BUY, TradeDirection.SHORT, PositionDirection.SHORT_ONLY))
    }

    @Test
    fun `sell while flat opens short only if the strategy allows going short`() {
        assertEquals(
            TradeDirection.SHORT,
            resolveTargetDirection(SignalType.SELL, null, PositionDirection.SHORT_ONLY)
        )
        assertNull(resolveTargetDirection(SignalType.SELL, null, PositionDirection.LONG_ONLY))
    }

    @Test
    fun `sell while already short does not pyramid`() {
        assertEquals(
            TradeDirection.SHORT,
            resolveTargetDirection(SignalType.SELL, TradeDirection.SHORT, PositionDirection.SHORT_ONLY)
        )
    }

    @Test
    fun `sell while long always flattens the long, then reopens short only if allowed`() {
        assertEquals(
            TradeDirection.SHORT,
            resolveTargetDirection(SignalType.SELL, TradeDirection.LONG, PositionDirection.LONG_AND_SHORT)
        )
        assertNull(resolveTargetDirection(SignalType.SELL, TradeDirection.LONG, PositionDirection.LONG_ONLY))
    }
}
