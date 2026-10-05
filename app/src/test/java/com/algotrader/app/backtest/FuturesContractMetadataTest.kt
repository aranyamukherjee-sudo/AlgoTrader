package com.algotrader.app.backtest

import com.algotrader.app.InstrumentInfo
import com.algotrader.app.Instruments
import com.algotrader.app.backtest.BacktestInstrumentResolver.Resolution
import com.algotrader.app.backtest.FuturesContractMetadata.Outcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All values are deterministic TEST values (lot sizes 7, 11 and expiry
 * 1_790_000_000). They are not real market lot sizes or expiries.
 */
class FuturesContractMetadataTest {

    private val symbol = "NSE:NIFTY26OCTFUT"
    private val expiry = 1_790_000_000L

    private fun body(
        lotSize: Any? = 7,
        symbolValue: String = symbol,
        expiryValue: Any? = expiry,
        source: String? = "test source",
        status: String = "ok"
    ) = JSONObject().apply {
        put("status", status)
        put("symbol", symbolValue)
        if (lotSize != null) put("lot_size", lotSize)
        if (expiryValue != null) put("expiry", expiryValue)
        if (source != null) put("source", source)
    }.toString()

    private fun contract() = InstrumentInfo(
        displayName = "NIFTY FUT",
        backendSymbol = symbol,
        lotSize = 1,
        isFutures = true,
        underlyingSymbol = "NSE:NIFTY50-INDEX",
        expiryEpochSeconds = expiry
    )

    private fun parse(b: String?, chainExpiry: Long? = expiry) =
        FuturesContractMetadata.parse(b, symbol, chainExpiry)

    // ---- Metadata tests 1-5 -------------------------------------------------

    @Test
    fun `valid authoritative lot size is accepted`() {
        val o = parse(body(lotSize = 7)) as Outcome.Known
        assertEquals(7, o.lotSize)
        assertEquals(symbol, o.symbol)
        assertEquals(expiry, o.expiryEpochSeconds)
        assertEquals("test source", o.source)
    }

    @Test
    fun `missing lot size stays unknown`() {
        assertTrue(parse(body(lotSize = null)) is Outcome.Unavailable)
    }

    @Test
    fun `zero lot size is rejected`() {
        assertTrue(parse(body(lotSize = 0)) is Outcome.Unavailable)
    }

    @Test
    fun `negative lot size is rejected`() {
        assertTrue(parse(body(lotSize = -7)) is Outcome.Unavailable)
    }

    @Test
    fun `invalid metadata cannot enable futures backtesting`() {
        val invalid = listOf(
            body(lotSize = 0), body(lotSize = -1), body(lotSize = null),
            body(lotSize = "7"),                 // string, not an integer
            body(lotSize = 7.5),                 // fractional
            body(source = null), body(source = " "),
            body(status = "error"),
            body(symbolValue = "NSE:NIFTY26NOVFUT"),
            body(expiryValue = null), body(expiryValue = expiry + 1),
            "", "not json", "{}"
        )
        for (b in invalid) {
            val outcome = parse(b)
            assertTrue("should be unavailable: $b", outcome is Outcome.Unavailable)
            val applied = FuturesContractMetadata.applyTo(contract(), outcome)
            assertNull(applied.contractLotSize)
            assertNull(applied.contractLotSizeSource)
            assertTrue(
                BacktestInstrumentResolver.resolve(
                    BacktestInstrumentType.FUTURES, Instruments.NIFTY, applied
                ) is Resolution.Rejected
            )
        }
        assertTrue(parse(null) is Outcome.Unavailable)
    }

    @Test
    fun `expiry from chain must be present and equal`() {
        assertTrue(parse(body(), chainExpiry = null) is Outcome.Unavailable)
        assertTrue(parse(body(), chainExpiry = expiry - 1) is Outcome.Unavailable)
    }

    @Test
    fun `unavailable reason is clear and carries no lot size`() {
        val o = parse(body(lotSize = null)) as Outcome.Unavailable
        assertTrue(o.reason.contains("lot size", ignoreCase = true))
    }

    // ---- Isolation tests 6-9 -----------------------------------------------

    @Test
    fun `applying metadata never touches the placeholder lotSize or the index lot sizes`() {
        val applied = FuturesContractMetadata.applyTo(contract(), parse(body(lotSize = 11)))
        assertEquals(11, applied.contractLotSize)
        assertEquals(1, applied.lotSize)
        assertEquals(65, Instruments.NIFTY.lotSize)
        assertEquals(30, Instruments.BANK_NIFTY.lotSize)
        assertEquals(20, Instruments.SENSEX.lotSize)
    }

    @Test
    fun `futures never inherit any index lot size`() {
        val known = FuturesContractMetadata.applyTo(contract(), parse(body(lotSize = 11)))
        for (index in Instruments.all) {
            val r = BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, index, known)
                as Resolution.Resolved
            assertEquals(11, r.lotSize)
            assertTrue(r.lotSize != index.lotSize)
        }
        // Unknown: blocked, so no index lot size can stand in.
        val unknown = contract()
        for (index in Instruments.all) {
            assertTrue(
                BacktestInstrumentResolver.resolve(BacktestInstrumentType.FUTURES, index, unknown)
                    is Resolution.Rejected
            )
        }
    }

    @Test
    fun `index resolution is unaffected by futures metadata`() {
        val known = FuturesContractMetadata.applyTo(contract(), parse(body(lotSize = 11)))
        for (index in Instruments.all) {
            val r = BacktestInstrumentResolver.resolve(BacktestInstrumentType.INDEX, index, known)
                as Resolution.Resolved
            assertEquals(index.lotSize, r.lotSize)
            assertEquals(BacktestInstrumentResolver.HistoryPath.INDEX_HISTORY, r.historyPath)
            assertNull(r.futuresContract)
        }
    }

    // ---- Resolver tests 10-13 --------------------------------------------------

    @Test
    fun `known lot size allows futures and preserves exact symbol and expiry`() {
        val known = FuturesContractMetadata.applyTo(contract(), parse(body(lotSize = 11)))
        val r = BacktestInstrumentResolver.resolve(
            BacktestInstrumentType.FUTURES, Instruments.NIFTY, known
        ) as Resolution.Resolved
        assertEquals(symbol, r.instrument.backendSymbol)
        assertEquals(symbol, r.futuresContract!!.contractId)
        assertEquals(expiry, r.futuresContract!!.expiryEpochSeconds)
        assertEquals(11, r.lotSize)
        assertEquals("test source", r.futuresContract!!.lotSizeSource)
        assertEquals(
            11,
            BacktestInstrumentResolver.engineLotSize(
                BacktestInstrumentType.FUTURES, symbol, r.futuresContract
            )
        )
    }

    @Test
    fun `unknown lot size blocks futures configuration`() {
        val r = BacktestInstrumentResolver.resolve(
            BacktestInstrumentType.FUTURES, Instruments.NIFTY, contract()
        )
        assertTrue(r is Resolution.Rejected)
    }
}
