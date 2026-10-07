package com.algotrader.intelligence.setup

import com.algotrader.intelligence.pattern.PatternConfig
import com.algotrader.intelligence.structure.AnalysisStatus

data class QualificationConfig(
    val requireTrendAlignment: Boolean = true,
    val minBreakDistance: BufferRule? = null,
    val minCloseLocation: Double? = null,
    val minRangeAtr: Double? = null,
    val volume: VolumeRule? = null,
    val atrPeriod: Int = 14
) {
    init {
        require(minCloseLocation == null || minCloseLocation in 0.0..1.0) {
            "minCloseLocation must be between 0 and 1"
        }
        require(
            minRangeAtr == null ||
                (minRangeAtr.isFinite() && minRangeAtr >= 0.0)
        ) {
            "minRangeAtr must be finite and non-negative"
        }
        require(atrPeriod >= 1) {
            "atrPeriod must be >= 1"
        }
        require(
            requireTrendAlignment ||
                minBreakDistance != null ||
                minCloseLocation != null ||
                minRangeAtr != null ||
                volume != null
        ) { "at least one qualification criterion must be required" }
    }
}

data class SetupConfig(
    val pattern: PatternConfig = PatternConfig(),
    val levelSources: Set<LevelSource> =
        setOf(LevelSource.ZONE, LevelSource.PATTERN_BOUNDARY),
    val buffer: BufferRule = BufferRule(),
    val confirmationCloses: Int = 1,
    val retestWindow: Int = 10,
    val retestBand: BufferRule = BufferRule(),
    val failureWindow: Int = 10,
    val continuationWindow: Int = 20,
    val qualification: QualificationConfig = QualificationConfig()
) {
    init {
        require(levelSources.isNotEmpty()) { "levelSources must not be empty" }
        require(confirmationCloses >= 1) { "confirmationCloses must be >= 1" }
        require(retestWindow >= 1) { "retestWindow must be >= 1" }
        require(failureWindow >= 1) { "failureWindow must be >= 1" }
        require(continuationWindow >= 1) { "continuationWindow must be >= 1" }
    }
}

data class SetupAnalysis(
    val status: AnalysisStatus,
    val message: String,
    val candleCount: Int,
    val setups: List<BreakoutSetup>,
    val signals: List<SetupSignal>
)
