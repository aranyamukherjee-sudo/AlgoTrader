package com.algotrader.app.asi3

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.pattern.PatternAnalysis
import com.algotrader.intelligence.pattern.PatternConfig
import com.algotrader.intelligence.setup.Breakout
import com.algotrader.intelligence.setup.BreakoutDirection
import com.algotrader.intelligence.setup.BreakoutDetector
import com.algotrader.intelligence.setup.BreakoutQualifier
import com.algotrader.intelligence.setup.BufferRule
import com.algotrader.intelligence.setup.QualificationConfig
import com.algotrader.intelligence.setup.QualificationStatus
import com.algotrader.intelligence.setup.SetupConfig
import com.algotrader.intelligence.structure.AnalysisStatus
import com.algotrader.intelligence.structure.ClassifiedPivot
import com.algotrader.intelligence.structure.MarketStructureState
import com.algotrader.intelligence.structure.PivotType
import com.algotrader.intelligence.structure.StructureAnalysis
import com.algotrader.intelligence.structure.StructureLabel
import com.algotrader.intelligence.structure.SwingPivot
import java.time.Instant

/**
 * ASI-3.3 device verification.
 *
 * Exercises the production breakout path:
 *   deterministic candles
 *     -> BreakoutDetector
 *        -> LevelExtractor
 *     -> BreakoutQualifier
 *
 * No ASI-3.3 production implementation is duplicated here.
 */
object Asi33DeviceHarness {

    data class Result(
        val passed: Boolean,
        val message: String,
        val candleCount: Int,
        val breakoutCount: Int,
        val direction: String?,
        val level: Double?,
        val trigger: Double?,
        val breakIndex: Int?,
        val confirmedIndex: Int?,
        val breakoutId: String?,
        val qualifiedStatus: String,
        val qualificationChecks: List<String>,
        val negativeStatus: String,
        val negativeChecks: List<String>,
        val deterministic: Boolean
    )

    private val instrument = Instrument("ASI33-FIXTURE", "NSE")

    private fun candles(): List<Candle> =
        listOf(99.0, 100.0, 101.0).mapIndexed { index, close ->
            Candle(
                instrument = instrument,
                timeframe = Timeframe.MINUTE_5,
                timestamp = Instant.parse("2026-10-06T04:00:00Z")
                    .plusSeconds(index * 300L),
                open = close,
                high = close + 0.5,
                low = close - 0.5,
                close = close,
                volume = 1000.0
            )
        }

    private fun patterns(count: Int) = PatternAnalysis(
        status = AnalysisStatus.OK,
        message = "device fixture",
        candleCount = count,
        patterns = emptyList()
    )

    private fun pivot(
        type: PivotType,
        index: Int,
        confirmedIndex: Int,
        price: Double,
        candles: List<Candle>,
        label: StructureLabel
    ) = ClassifiedPivot(
        pivot = SwingPivot(
            type = type,
            index = index,
            timestamp = candles[index].timestamp,
            price = price,
            confirmedIndex = confirmedIndex,
            confirmedAt = candles[confirmedIndex].timestamp,
            strength = 1,
            prominence = 1.0
        ),
        label = label
    )

    private fun rangeStructure(candles: List<Candle>): StructureAnalysis =
        StructureAnalysis(
            status = AnalysisStatus.OK,
            message = "range fixture",
            candleCount = candles.size,
            pivots = listOf(
                pivot(
                    PivotType.HIGH,
                    0,
                    0,
                    100.0,
                    candles,
                    StructureLabel.EQH
                ),
                pivot(
                    PivotType.LOW,
                    1,
                    1,
                    99.0,
                    candles,
                    StructureLabel.EQL
                )
            ),
            state = MarketStructureState.RANGE,
            zones = emptyList(),
            atr = null
        )

    private fun downtrendStructure(candles: List<Candle>): StructureAnalysis =
        StructureAnalysis(
            status = AnalysisStatus.OK,
            message = "downtrend fixture",
            candleCount = candles.size,
            pivots = listOf(
                pivot(
                    PivotType.HIGH,
                    0,
                    0,
                    100.0,
                    candles,
                    StructureLabel.LH
                ),
                pivot(
                    PivotType.LOW,
                    1,
                    1,
                    99.0,
                    candles,
                    StructureLabel.LL
                )
            ),
            state = MarketStructureState.DOWNTREND,
            zones = emptyList(),
            atr = null
        )

    private fun detect(
        candles: List<Candle>,
        structure: StructureAnalysis
    ): List<Breakout> {
        val config = SetupConfig(
            pattern = PatternConfig(),
            levelSources = setOf(
                com.algotrader.intelligence.setup.LevelSource.PIVOT
            ),
            buffer = BufferRule(),
            confirmationCloses = 1,
            retestWindow = 3,
            failureWindow = 4,
            continuationWindow = 5
        )

        return BreakoutDetector.detect(
            candles = candles,
            structure = structure,
            patterns = patterns(candles.size),
            config = config
        )
    }

    private fun qualify(
        candles: List<Candle>,
        structure: StructureAnalysis,
        breakout: Breakout
    ) = BreakoutQualifier.qualify(
        candles = candles,
        structure = structure,
        breakout = breakout,
        config = QualificationConfig()
    )

    private fun checkLines(
        qualification: com.algotrader.intelligence.setup.BreakoutQualification
    ): List<String> =
        qualification.checks.map {
            buildString {
                append(it.criterion.name)
                append("=")
                append(it.status.name)
                it.trendState?.let { trend ->
                    append(" trend=")
                    append(trend.name)
                }
                it.observed?.let { observed ->
                    append(" observed=")
                    append(observed)
                }
            }
        }

    private fun signature(
        breakouts: List<Breakout>,
        qualification: com.algotrader.intelligence.setup.BreakoutQualification
    ): String =
        buildString {
            append(breakouts.map {
                listOf(
                    it.id.value,
                    it.direction.name,
                    it.level.price,
                    it.attempt,
                    it.breakIndex,
                    it.confirmedIndex,
                    it.breakClose,
                    it.trigger,
                    it.buffer,
                    it.retestBand,
                    it.deadlines.retestUntil,
                    it.deadlines.failureUntil,
                    it.deadlines.continuationUntil,
                    it.deadlines.expiresAt
                ).joinToString("|")
            })
            append("#")
            append(qualification.status.name)
            append("#")
            append(
                qualification.checks.joinToString("|") {
                    "${it.criterion.name}:${it.status.name}:${it.observed}:${it.requiredValue}:${it.trendState}"
                }
            )
        }

    fun run(): Result {
        val candles = candles()
        val structure = rangeStructure(candles)

        val firstBreakouts = detect(candles, structure)
        val secondBreakouts = detect(candles, structure)

        require(firstBreakouts.size == 1) {
            "expected exactly one breakout, got ${firstBreakouts.size}"
        }

        val breakout = firstBreakouts.single()

        require(breakout.direction == BreakoutDirection.UP) {
            "expected UP breakout, got ${breakout.direction}"
        }
        require(breakout.level.price == 100.0) {
            "expected level 100.0, got ${breakout.level.price}"
        }
        require(breakout.trigger == 100.0) {
            "expected trigger 100.0, got ${breakout.trigger}"
        }
        require(breakout.breakIndex == 2) {
            "expected breakIndex 2, got ${breakout.breakIndex}"
        }
        require(breakout.confirmedIndex == 2) {
            "expected confirmedIndex 2, got ${breakout.confirmedIndex}"
        }
        require(breakout.breakClose == 101.0) {
            "expected breakClose 101.0, got ${breakout.breakClose}"
        }
        require(breakout.id.value == "UP:100.0:b1") {
            "unexpected breakout id ${breakout.id.value}"
        }

        val qualification = qualify(candles, structure, breakout)

        require(qualification.status == QualificationStatus.QUALIFIED) {
            "expected qualified RANGE breakout, got ${qualification.status}"
        }

        require(
            qualification.checks.all {
                !it.required || it.status == com.algotrader.intelligence.setup.CheckStatus.PASS
            }
        ) {
            "qualified result contains a required non-PASS check"
        }

        val negativeStructure = downtrendStructure(candles)
        val negativeBreakouts = detect(candles, negativeStructure)

        require(negativeBreakouts.size == 1) {
            "expected one breakout in negative fixture, got ${negativeBreakouts.size}"
        }

        val negativeQualification =
            qualify(candles, negativeStructure, negativeBreakouts.single())

        require(negativeQualification.status == QualificationStatus.UNQUALIFIED) {
            "expected downtrend/opposite-direction fixture to be unqualified"
        }

        require(
            negativeQualification.checks.any {
                it.criterion ==
                    com.algotrader.intelligence.setup.QualificationCriterion.TREND_ALIGNMENT &&
                    it.status == com.algotrader.intelligence.setup.CheckStatus.FAIL
            }
        ) {
            "expected TREND_ALIGNMENT to fail in negative fixture"
        }

        val deterministic =
            firstBreakouts == secondBreakouts &&
                signature(firstBreakouts, qualification) ==
                signature(secondBreakouts, qualify(candles, structure, secondBreakouts.single()))

        return Result(
            passed = deterministic,
            message = if (deterministic) {
                "ASI-3.3 production breakout + qualification path passed."
            } else {
                "ASI-3.3 output was not deterministic."
            },
            candleCount = candles.size,
            breakoutCount = firstBreakouts.size,
            direction = breakout.direction.name,
            level = breakout.level.price,
            trigger = breakout.trigger,
            breakIndex = breakout.breakIndex,
            confirmedIndex = breakout.confirmedIndex,
            breakoutId = breakout.id.value,
            qualifiedStatus = qualification.status.name,
            qualificationChecks = checkLines(qualification),
            negativeStatus = negativeQualification.status.name,
            negativeChecks = checkLines(negativeQualification),
            deterministic = deterministic
        )
    }
}
