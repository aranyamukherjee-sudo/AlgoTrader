package com.algotrader.domain

data class Instrument(
    val symbol: String,
    val exchange: String,
    val currency: String = "INR"
)
