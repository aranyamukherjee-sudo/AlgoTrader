package com.algotrader.intelligence.dna

import com.algotrader.domain.Instrument

/**
 * What kind of instrument something is. F&O is the primary research target;
 * an [INDEX] is a reference / signal-discovery instrument and is never
 * automatically the final tradable instrument.
 */
enum class InstrumentKind(val isTradable: Boolean) {
    INDEX(isTradable = false),
    FUTURES(isTradable = true),
    OPTION(isTradable = true),
    EQUITY(isTradable = true);

    val isDerivative: Boolean get() = this == FUTURES || this == OPTION
}

/**
 * An existing [Instrument] plus its [kind]. [contractId] is optional: research
 * on "NIFTY futures" generally has none; a live opportunity on a specific
 * contract should carry one. Option contract details (strike, expiry, call/put)
 * are deferred.
 */
data class InstrumentRef(
    val instrument: Instrument,
    val kind: InstrumentKind,
    /** Underlying symbol for derivatives, e.g. "NIFTY". Required for FUTURES/OPTION. */
    val underlying: String? = null,
    val contractId: String? = null
) {
    init {
        require(instrument.symbol.isNotBlank()) { "instrument symbol must not be blank" }
        if (kind.isDerivative) {
            require(!underlying.isNullOrBlank()) { "$kind requires a non-blank underlying" }
        }
        require(contractId == null || contractId.isNotBlank()) { "contractId must be null or non-blank" }
    }

    val isTradable: Boolean get() = kind.isTradable
}

/**
 * Where a strategy looks ([signalSource], any kind including an index) versus
 * what it trades ([tradeTarget], never an index). [tradeTarget] is null while
 * a strategy is research-only on reference instruments.
 */
data class MarketScope(
    val signalSource: InstrumentRef,
    val tradeTarget: InstrumentRef? = null
) {
    init {
        require(tradeTarget == null || tradeTarget.isTradable) {
            "tradeTarget must be tradable; ${tradeTarget?.kind} is reference-only"
        }
    }

    val hasTradeTarget: Boolean get() = tradeTarget != null
}
