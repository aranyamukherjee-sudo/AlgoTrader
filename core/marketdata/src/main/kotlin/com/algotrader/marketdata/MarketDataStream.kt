package com.algotrader.marketdata

import com.algotrader.domain.Instrument
import com.algotrader.domain.MarketQuote

interface MarketDataStream {

    fun subscribe(instruments: List<Instrument>)

    fun unsubscribe(instruments: List<Instrument>)

    fun setListener(listener: Listener?)

    interface Listener {
        fun onQuote(quote: MarketQuote)
        fun onError(error: Throwable)
    }
}
