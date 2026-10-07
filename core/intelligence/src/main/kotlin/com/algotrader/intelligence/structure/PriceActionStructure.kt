package com.algotrader.intelligence.structure

import com.algotrader.domain.Candle

enum class AnalysisStatus { OK, INSUFFICIENT_DATA, INVALID_INPUT }

/**
 * Result of one structure evaluation as of the LAST candle supplied.
 * When [status] is not OK every collection is empty, [state] is UNDEFINED and [atr] is null.
 *
 * @property pivots confirmed pivots, labelled, in chronological order (HIGH before LOW on the same bar).
 * @property atr latest ATR, or null while the ATR is still warming up.
 */
data class StructureAnalysis(
    val status: AnalysisStatus,
    val message: String,
    val candleCount: Int,
    val pivots: List<ClassifiedPivot>,
    val state: MarketStructureState,
    val zones: List<PriceZone>,
    val atr: Double?
)

/**
 * Entry point of the price-action structure foundation (ASI-3.1). Pure and stateless: the same candles and config
 * always give an equal result. "As of bar k" evaluation is simply `analyze(candles.subList(0, k + 1))`; because
 * pivots are only emitted once confirmed, that result is always a prefix-consistent subset of any longer analysis.
 *
 * Pattern detection (ASI-3.2) and breakout intelligence (ASI-3.3) are NOT part of this foundation.
 */
object PriceActionStructure {

    fun analyze(candles: List<Candle>, config: StructureConfig = StructureConfig()): StructureAnalysis {
        CandleSeries.problem(candles)?.let { return notOk(AnalysisStatus.INVALID_INPUT, it, candles.size) }
        if (candles.size < config.minCandles) {
            return notOk(
                AnalysisStatus.INSUFFICIENT_DATA,
                "need at least ${config.minCandles} candles, got ${candles.size}",
                candles.size
            )
        }
        val pivots = SwingDetector.detect(candles, config)
        val classified = MarketStructure.classify(pivots, config.equalityTolerance)
        return StructureAnalysis(
            status = AnalysisStatus.OK,
            message = "",
            candleCount = candles.size,
            pivots = classified,
            state = MarketStructure.state(classified),
            zones = SupportResistance.zones(pivots, config),
            atr = Volatility.atr(candles, config.atrPeriod).last()
        )
    }

    private fun notOk(status: AnalysisStatus, message: String, count: Int) = StructureAnalysis(
        status, message, count, emptyList(), MarketStructureState.UNDEFINED, emptyList(), null
    )
}
