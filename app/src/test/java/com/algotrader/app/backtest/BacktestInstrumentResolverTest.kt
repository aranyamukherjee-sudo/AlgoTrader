package com.algotrader.app.backtest

import com.algotrader.app.InstrumentInfo
import com.algotrader.app.Instruments
import com.algotrader.app.backtest.BacktestInstrumentResolver.HistoryPath
import com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Routing / lot-size / metadata rules for Patch 6. Lot sizes used for futures
 * below (e.g. 10) are arbitrary TEST values, not any real contract size.
 */
class BacktestInstrumentResolverTest {

    private val exactSymbol = "NSE:NIFTY26OCTFUT"
    private val expiry = 1_790_000_000L // arbitrary test epoch, carried through untouched

    private fun futures(
        contractLotSize: Int?,
        expiryEpoch: Long? = expiry,
        source: String? = if (contractLotSize != null) "test metadata source" else null
    ) = InstrumentInfo(
        displayName = "NIFTY FUT",
        backendSymbol = exactSymbol,
        lotSize = 1, // legacy placeholder, must never be used for futures accounting
        isFutures = true,
        underlyingSymbol = "NSE:NIFTY50-INDEX",
        expiryEpochSeconds = expiryEpoch,
        contractLotSize = contractLotSize,
        contractLotSizeSource = source
    )

    private fun resolved(r: Resolution): Resolution.Resolved {
        if (r !is Resolution.Resolved) fail("expected Resolved but was $r")
        return r as Resolution.Resolved
    }

    // ---- INDEX: existing behaviour -----------------------------------------

    @Test
    fun `every existing index resolves to the index history path and its existing lot size`() {
        for (index in Instruments.all) {
            val r = resolved(
                BacktestInstrumentResolver.resolve(BacktestInstrumentType.INDEX, index, futures(10))
            )
            assertEquals(BacktestInstrumentType.INDEX, r.type)
            assertEquals(HistoryPath.INDEX_HISTORY, r.historyPath)
            assertEquals("/history", r.historyPath.endpoint)
            assertEquals(index.backendSymbol, r.instrument.backendSymbol)
            assertEquals(index.lotSize, r.lotSize)
            assertEquals(BacktestFormat.lotSizeFor(index.backendSymbol), r.lotSize) // same value the Worker used before
            assertNull(r.futuresContract)
        }
    }

    @Test
    fun `existing index lot sizes are unchanged`() {
        assertEquals(65, Instruments.NIFTY.lotSize)
        assertEquals(30, Instruments.BANK_NIFTY.lotSize)
        assertEquals(20, Instruments.SENSEX.lotSize)
        assertEquals("NSE:NIFTY50-INDEX", Instruments.NIFTY.backendSymbol)
        assertEquals("NSE:NIFTYBANK-INDEX", Instruments.BANK_NIFTY.backendSymbol)
        assertEquals("BSE:SENSEX-INDEX", Instruments.SENSEX.backendSymbol)
    }

    @Test
    fun `index run is never silently routed to a selected futures contract`() {
        val r = BacktestInstrumentResolver.resolve(BacktestInstrumentType.INDEX, futures(10), futures(10))
        assertTrue(r is Resolution.Rejected)
    }

    // ---- FUTURES ----------------------------------------------------------

    @Test
    fun `futures resolves to the exact contract, the futures history path and the contract lot size`() {
        val r = resolved(
            BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(10))
        )
        assertEquals(BacktestInstrumentType.FUTURES, r.type)
        assertEquals(HistoryPath.FUTURES_HISTORY, r.historyPath)
        assertEquals("/futures/history", r.historyPath.endpoint)
        assertEquals(exactSymbol, r.instrument.backendSymbol)
        assertEquals(10, r.lotSize)
    }

    @Test
    fun `futures does not use the selected index lot size`() {
        val r = resolved(
            BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(10))
        )
        assertTrue(r.lotSize != Instruments.NIFTY.lotSize)
    }

    @Test
    fun `unknown futures lot size is rejected and never defaults`() {
        val r = BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(null))
        assertTrue(r is Resolution.Rejected)
        assertEquals(BacktestInstrumentResolver.UNKNOWN_LOT_SIZE_MESSAGE, (r as Resolution.Rejected).reason)
        // A non-positive value is also not a usable lot size.
        assertTrue(
            BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(0))
                is Resolution.Rejected
        )
    }

    @Test
    fun `no discovered contract is rejected and nothing is constructed`() {
        val r = BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, null)
        assertEquals(BacktestInstrumentResolver.NO_CONTRACT_MESSAGE, (r as Resolution.Rejected).reason)
        // An index passed as the "contract" is not a futures contract.
        assertTrue(
            BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, Instruments.NIFTY)
                is Resolution.Rejected
        )
    }

    @Test
    fun `a lot size without a stated source is not verified and blocks futures`() {
        for (source in listOf(null, "", "   ")) {
            val r = BacktestInstrumentResolver.resolve(
                BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(10, source = source)
            )
            assertTrue("source=$source", r is Resolution.Rejected)
        }
    }

    @Test
    fun `none of the three index lot sizes can enable futures`() {
        // Even if an index lot size leaked into contractLotSize with no source, it stays blocked.
        for (index in Instruments.all) {
            val leaked = futures(index.lotSize, source = null)
            assertTrue(
                BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, index, leaked)
                    is Resolution.Rejected
            )
        }
        // The legacy placeholder lotSize (1) of a discovered contract never counts.
        val discovered = futures(null)
        assertEquals(1, discovered.lotSize)
        assertTrue(
            BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, discovered)
                is Resolution.Rejected
        )
    }

    @Test
    fun `the lot size source is carried into the persisted contract`() {
        val r = resolved(
            BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(10))
        )
        assertEquals("test metadata source", r.futuresContract!!.lotSizeSource)
    }

    // ---- Expiry / metadata ---------------------------------------------------

    @Test
    fun `expiry and identity are carried exactly as supplied`() {
        val r = resolved(
            BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(10))
        )
        val c = r.futuresContract
        assertNotNull(c)
        c!!
        assertEquals(expiry, c.expiryEpochSeconds)
        assertEquals(exactSymbol, c.contractId)
        assertEquals("NSE:NIFTY50-INDEX", c.underlying)
        assertEquals(10, c.lotSize)
        // Not derived or fabricated:
        assertNull(c.expiry)
        assertNull(c.contractMonth)
    }

    @Test
    fun `missing expiry stays null and is not inferred`() {
        val r = resolved(
            BacktestInstrumentResolver.resolve(
                BacktestInstrumentType.FUTURES, Instruments.NIFTY, futures(10, expiryEpoch = null)
            )
        )
        assertNull(r.futuresContract!!.expiryEpochSeconds)
    }

    // ---- Worker lot-size hook ---------------------------------------------------

    @Test
    fun `worker lot size for INDEX is the existing symbol lookup`() {
        assertEquals(
            65,
            BacktestInstrumentResolver.engineLotSize(BacktestInstrumentType.INDEX, "NSE:NIFTY50-INDEX", null)
        )
        assertEquals(
            30,
            BacktestInstrumentResolver.engineLotSize(BacktestInstrumentType.INDEX, "NSE:NIFTYBANK-INDEX", null)
        )
        assertEquals(
            20,
            BacktestInstrumentResolver.engineLotSize(BacktestInstrumentType.INDEX, "BSE:SENSEX-INDEX", null)
        )
    }

    @Test
    fun `worker lot size for FUTURES is the persisted contract lot size`() {
        val c = FuturesContractConfig(contractId = exactSymbol, lotSize = 10)
        assertEquals(10, BacktestInstrumentResolver.engineLotSize(BacktestInstrumentType.FUTURES, exactSymbol, c))
    }

    @Test
    fun `worker refuses a futures job with no lot size instead of using 1`() {
        for (config in listOf(null, FuturesContractConfig(contractId = exactSymbol, lotSize = null))) {
            try {
                BacktestInstrumentResolver.engineLotSize(BacktestInstrumentType.FUTURES, exactSymbol, config)
                fail("expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                assertEquals(BacktestInstrumentResolver.UNKNOWN_LOT_SIZE_MESSAGE, expected.message)
            }
        }
    }
}
