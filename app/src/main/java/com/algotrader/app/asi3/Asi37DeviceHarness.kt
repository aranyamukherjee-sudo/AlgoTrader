package com.algotrader.app.asi3

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.InstrumentKind
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.opportunity.OpportunityState
import com.algotrader.intelligence.opportunity.SetupOpportunityComposer
import com.algotrader.intelligence.pattern.PatternConfig
import com.algotrader.intelligence.pattern.PatternDetector
import com.algotrader.intelligence.setup.*
import com.algotrader.intelligence.setup.context.SetupContextExtractor
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessmentEngine
import com.algotrader.intelligence.structure.PriceActionStructure
import com.algotrader.intelligence.structure.StructureConfig
import java.time.Instant

/**
 * ASI-4.2 device harness for the complete ASI-3.7 candle-derived pipeline.
 * Uses actual OHLC candles and production detectors; no injected pivots/zones.
 */
object Asi37DeviceHarness {

    data class Result(
        val passed: Boolean,
        val message: String,
        val candleCount: Int,
        val breakoutId: String,
        val qualification: String,
        val opportunityState: String,
        val deterministic: Boolean,
        val lookaheadRejected: Boolean,
        val opportunityId: String
    )

    private val instrument = Instrument("TEST-FUT", "NSE")
    private val timeframe = Timeframe.MINUTE_5
    private val t0 = Instant.parse("2026-10-06T04:00:00Z")
    private val structureConfig = StructureConfig(left = 2, right = 2, atrPeriod = 3)
    private val patternConfig = PatternConfig(structure = structureConfig)
    private val strategy = StrategyRef(StrategyId("asi37-device"), 1)
    private val tradeInstrument = InstrumentRef(
        instrument = instrument,
        kind = InstrumentKind.FUTURES,
        underlying = "TEST",
        contractId = "TEST-FUT-1"
    )

    private data class Pipeline(
        val candleCount: Int,
        val breakoutId: String,
        val qualification: String,
        val opportunityState: String,
        val opportunityId: String,
        val context: com.algotrader.intelligence.setup.context.SetupContext,
        val confluence: com.algotrader.intelligence.setup.context.SetupConfluenceAssessment,
        val opportunity: com.algotrader.intelligence.opportunity.Opportunity,
        val lookaheadRejected: Boolean
    )

    fun run(): Result {
        val first = runPipeline()
        val second = runPipeline()

        val deterministic =
            first.breakoutId == second.breakoutId &&
            first.qualification == second.qualification &&
            first.context == second.context &&
            first.confluence == second.confluence &&
            first.opportunity == second.opportunity

        check(deterministic) {
            "Identical candle inputs produced different pipeline results"
        }
        check(first.opportunityState == OpportunityState.OPPORTUNITY_FOUND.name) {
            "Expected OPPORTUNITY_FOUND, got ${first.opportunityState}"
        }
        check(first.lookaheadRejected) {
            "Full-series structure/pattern analysis was accepted for a historical prefix"
        }

        return Result(
            passed = true,
            message = "All ASI-3.7 candle-derived pipeline checks passed.",
            candleCount = first.candleCount,
            breakoutId = first.breakoutId,
            qualification = first.qualification,
            opportunityState = first.opportunityState,
            deterministic = deterministic,
            lookaheadRejected = first.lookaheadRejected,
            opportunityId = first.opportunityId
        )
    }

    private fun candles(): List<Candle> {
        val closes = listOf(
            100.0, 102.0, 105.0, 101.0, 98.0,
            100.0, 103.0, 101.0, 99.0, 102.0,
            104.0, 102.0, 100.0, 103.0, 106.0,
            103.0, 101.0, 104.0, 107.0, 111.0
        )
        return closes.mapIndexed { i, close ->
            val spread = if (i == closes.lastIndex) 0.8 else 0.5
            Candle(
                instrument = instrument,
                timeframe = timeframe,
                timestamp = t0.plusSeconds(i * 300L),
                open = close - 0.1,
                high = close + spread,
                low = close - spread,
                close = close,
                volume = 1000.0 + i * 10.0
            )
        }
    }

    private fun runPipeline(): Pipeline {
        val allCandles = candles()
        val fullStructure = PriceActionStructure.analyze(allCandles, structureConfig)
        val fullPatterns = PatternDetector.detect(allCandles, patternConfig)

        check(fullStructure.candleCount == allCandles.size) {
            "Structure candle count mismatch"
        }
        check(fullPatterns.candleCount == allCandles.size) {
            "Pattern candle count mismatch"
        }
        check(fullStructure.pivots.isNotEmpty()) {
            "No candle-derived structure pivots found"
        }

        val setupConfig = SetupConfig(
            pattern = patternConfig,
            levelSources = setOf(LevelSource.PIVOT),
            confirmationCloses = 1,
            retestWindow = 3,
            failureWindow = 4,
            continuationWindow = 5
        )
        val breakouts = BreakoutDetector.detect(
            candles = allCandles,
            structure = fullStructure,
            patterns = fullPatterns,
            config = setupConfig
        )
        check(breakouts.isNotEmpty()) {
            "Fixture produced no candle-derived breakout"
        }

        val breakout = breakouts.first()
        val prefix = allCandles.take(breakout.confirmedIndex + 1)
        check(prefix.size < allCandles.size) {
            "Fixture must contain candles after breakout confirmation"
        }

        val prefixStructure = PriceActionStructure.analyze(prefix, structureConfig)
        val prefixPatterns = PatternDetector.detect(prefix, patternConfig)
        val qualification = BreakoutQualifier.qualify(
            candles = prefix,
            structure = prefixStructure,
            breakout = breakout,
            config = QualificationConfig(
                requireTrendAlignment = false,
                minCloseLocation = 0.0,
                atrPeriod = structureConfig.atrPeriod
            )
        )
        val setup = BreakoutSetup(
            breakout = breakout,
            qualification = qualification,
            events = listOf(
                BrokenEvent(
                    index = breakout.confirmedIndex,
                    at = breakout.confirmedAt,
                    close = breakout.breakClose
                )
            )
        )
        val assessment = SetupEvidenceAssessmentEngine.assess(
            setup = setup,
            strategy = strategy,
            asOfIndex = breakout.confirmedIndex,
            asOf = breakout.confirmedAt
        )
        val context = SetupContextExtractor.extract(
            setup = setup,
            structure = prefixStructure,
            patterns = prefixPatterns,
            asOfIndex = breakout.confirmedIndex
        )
        val confluence = SetupConfluenceAssessmentEngine.assess(context)

        val structureLookaheadRejected = try {
            SetupContextExtractor.extract(
                setup = setup,
                structure = fullStructure,
                patterns = prefixPatterns,
                asOfIndex = breakout.confirmedIndex
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        val patternLookaheadRejected = try {
            SetupContextExtractor.extract(
                setup = setup,
                structure = prefixStructure,
                patterns = fullPatterns,
                asOfIndex = breakout.confirmedIndex
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        check(structureLookaheadRejected && patternLookaheadRejected) {
            "Lookahead guard did not reject both full-series analyses"
        }

        val opportunity = SetupOpportunityComposer.compose(
            setup = setup,
            strategy = strategy,
            instrument = tradeInstrument,
            timeframe = timeframe,
            assessment = assessment,
            context = context,
            confluence = confluence,
            at = breakout.confirmedAt
        )
        check(opportunity.state == OpportunityState.OPPORTUNITY_FOUND) {
            "Unexpected opportunity state: ${opportunity.state}"
        }
        check(context.breakoutId == breakout.id) {
            "Context breakout ID mismatch"
        }
        check(context.asOfIndex == breakout.confirmedIndex) {
            "Context historical index mismatch"
        }
        check(SetupConfluenceAssessmentEngine.assess(context) == confluence) {
            "Confluence assessment is not deterministic"
        }

        return Pipeline(
            candleCount = allCandles.size,
            breakoutId = breakout.id.toString(),
            qualification = qualification.status.name,
            opportunityState = opportunity.state.name,
            opportunityId = opportunity.id.value,
            context = context,
            confluence = confluence,
            opportunity = opportunity,
            lookaheadRejected = structureLookaheadRejected && patternLookaheadRejected
        )
    }
}
