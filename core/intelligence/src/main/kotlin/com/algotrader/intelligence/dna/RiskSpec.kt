package com.algotrader.intelligence.dna

/** Risk parameters. Every field is optional: null means "not specified", never zero. */
data class RiskSpec(
    val stopLossPercent: Double? = null,
    val takeProfitPercent: Double? = null,
    val trailingStopPercent: Double? = null,
    val maxHoldBars: Int? = null,
    val maxRiskPerTradePercent: Double? = null
) {
    init {
        requirePositiveOrNull("stopLossPercent", stopLossPercent)
        requirePositiveOrNull("takeProfitPercent", takeProfitPercent)
        requirePositiveOrNull("trailingStopPercent", trailingStopPercent)
        requirePositiveOrNull("maxRiskPerTradePercent", maxRiskPerTradePercent)
        require(maxHoldBars == null || maxHoldBars > 0) { "maxHoldBars must be positive when set" }
    }

    private fun requirePositiveOrNull(name: String, value: Double?) {
        require(value == null || (value.isFinite() && value > 0.0)) {
            "$name must be a finite positive number when set"
        }
    }
}
