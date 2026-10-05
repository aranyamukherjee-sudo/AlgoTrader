package com.algotrader.app.backtest

import com.algotrader.app.InstrumentInfo
import org.json.JSONObject

/**
 * Phase 3 Patch 7 — parses the backend's "/futures/contract-metadata" response
 * for one exact futures contract and attaches the authoritative lot size to
 * the Patch 5 contract.
 *
 * A lot size is accepted only when ALL of these hold:
 *  - status is "ok";
 *  - the echoed symbol equals the requested exact FYERS symbol;
 *  - "lot_size" is a JSON integer >= 1 (not a string, not fractional);
 *  - a non-blank "source" is stated;
 *  - the metadata expiry is positive and equals the expiry already obtained
 *    from "/futures/chain" (this also guards against a mis-parsed row).
 *
 * Anything else is [Outcome.Unavailable] with a reason. There is no default.
 */
object FuturesContractMetadata {

    sealed interface Outcome {
        data class Known(
            val symbol: String,
            val lotSize: Int,
            val expiryEpochSeconds: Long,
            val source: String
        ) : Outcome

        data class Unavailable(val reason: String) : Outcome
    }

    fun parse(
        responseBody: String?,
        expectedSymbol: String,
        chainExpiryEpochSeconds: Long?
    ): Outcome {
        if (responseBody.isNullOrBlank()) {
            return unavailable("empty response")
        }
        val root = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            return unavailable("response was not valid JSON")
        }

        if (root.optString("status") != "ok") {
            val message = root.optString("message").takeIf { it.isNotBlank() }
            return unavailable(message ?: "backend returned an error")
        }

        if (root.optString("symbol") != expectedSymbol) {
            return unavailable("response was for a different contract")
        }

        val lotSize = integerOrNull(root, "lot_size")
            ?: return unavailable("lot size missing or not an integer")
        if (lotSize < 1 || lotSize > Int.MAX_VALUE.toLong()) {
            return unavailable("lot size $lotSize is not valid")
        }

        val source = root.optString("source").trim()
        if (source.isEmpty()) {
            return unavailable("metadata source not stated")
        }

        val expiry = integerOrNull(root, "expiry")
        if (expiry == null || expiry <= 0L) {
            return unavailable("contract expiry missing in metadata")
        }
        if (chainExpiryEpochSeconds == null || chainExpiryEpochSeconds != expiry) {
            return unavailable("metadata expiry does not match the futures chain expiry")
        }

        return Outcome.Known(expectedSymbol, lotSize.toInt(), expiry, source)
    }

    /**
     * Returns [contract] with the verified lot size and its source, or with
     * both explicitly null when unavailable. Never touches [InstrumentInfo.lotSize].
     */
    fun applyTo(contract: InstrumentInfo, outcome: Outcome): InstrumentInfo = when (outcome) {
        is Outcome.Known -> contract.copy(
            contractLotSize = outcome.lotSize,
            contractLotSizeSource = outcome.source
        )

        is Outcome.Unavailable -> contract.copy(
            contractLotSize = null,
            contractLotSizeSource = null
        )
    }

    private fun unavailable(reason: String) =
        Outcome.Unavailable("Metadata rejected: $reason.")

    /** A JSON number with no fractional part; strings and decimals are rejected. */
    private fun integerOrNull(root: JSONObject, key: String): Long? {
        if (!root.has(key) || root.isNull(key)) return null
        val value = root.opt(key)
        return when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Double -> if (value % 1.0 == 0.0 && value.isFinite()) value.toLong() else null
            else -> null
        }
    }
}
