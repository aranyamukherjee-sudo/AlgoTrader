package com.algotrader.intelligence.opportunity

import com.algotrader.domain.Candle
import com.algotrader.domain.Instrument
import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.InstrumentKind
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyId
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.setup.*
import com.algotrader.intelligence.pattern.PatternConfig
import com.algotrader.intelligence.pattern.PatternDetector
import com.algotrader.intelligence.structure.PriceActionStructure
import com.algotrader.intelligence.structure.StructureConfig
import com.algotrader.intelligence.setup.context.SetupContextExtractor
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessmentEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.Instant

/**
 * ASI-3.7 integration coverage.
 *
 * Uses real candle-derived structure and pattern analysis, then passes the
 * resulting setup through qualification, live evidence assessment, context,
 * confluence, and opportunity composition.
 */
class Asi3PipelineIntegrationTest {

    private val instrument = Instrument("TEST-FUT", "NSE")
    private val timeframe = Timeframe.MINUTE_5
    private val t0 = Instant.parse("2026-10-06T04:00:00Z")
    private val structureConfig = StructureConfig(
        left = 2,
        right = 2,
        atrPeriod = 3
    )
    private val patternConfig = PatternConfig(structure = structureConfig)
    private val strategy = StrategyRef(StrategyId("asi37-integration"), 1)
    private val tradeInstrument = InstrumentRef(
        instrument = instrument,
        kind = InstrumentKind.FUTURES,
        underlying = "TEST",
        contractId = "TEST-FUT-1"
    )

    private fun candles(): List<Candle> {
        // A real OHLC sequence with confirmed local highs/lows and a later
        // close crossing the prior resistance. No injected pivots or zones.
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

    private data class PipelineResult(
        val breakoutId: BreakoutId,
        val qualification: QualificationStatus,
        val context: com.algotrader.intelligence.setup.context.SetupContext,
        val confluence: com.algotrader.intelligence.setup.context.SetupConfluenceAssessment,
        val opportunity: Opportunity
    )

    private fun runPipeline(): PipelineResult {
        val allCandles = candles()
        val fullStructure = PriceActionStructure.analyze(
            allCandles,
            structureConfig
        )
        val fullPatterns = PatternDetector.detect(
            allCandles,
            patternConfig
        )

        assertEquals(allCandles.size, fullStructure.candleCount)
        assertEquals(allCandles.size, fullPatterns.candleCount)
        assertTrue(fullStructure.pivots.isNotEmpty(), "real structure pivots expected")

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
        assertTrue(
            breakouts.isNotEmpty(),
            "fixture must produce a detector breakout from candle-derived pivots"
        )

        val breakout = breakouts.first()
        val prefix = allCandles.take(breakout.confirmedIndex + 1)

        assertTrue(
            prefix.size < allCandles.size,
            "fixture must include candles after breakout confirmation"
        )

        val prefixStructure = PriceActionStructure.analyze(
            prefix,
            structureConfig
        )
        val prefixPatterns = PatternDetector.detect(
            prefix,
            patternConfig
        )

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

        val liveAssessment = SetupEvidenceAssessmentEngine.assess(
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

        // Full-series analyses must not be accepted for a historical prefix.
        if (fullStructure.candleCount != prefix.size) {
            assertFailsWith<IllegalArgumentException> {
                SetupContextExtractor.extract(
                    setup = setup,
                    structure = fullStructure,
                    patterns = prefixPatterns,
                    asOfIndex = breakout.confirmedIndex
                )
            }
        }
        if (fullPatterns.candleCount != prefix.size) {
            assertFailsWith<IllegalArgumentException> {
                SetupContextExtractor.extract(
                    setup = setup,
                    structure = prefixStructure,
                    patterns = fullPatterns,
                    asOfIndex = breakout.confirmedIndex
                )
            }
        }

        val opportunity = SetupOpportunityComposer.compose(
            setup = setup,
            strategy = strategy,
            instrument = tradeInstrument,
            timeframe = timeframe,
            assessment = liveAssessment,
            context = context,
            confluence = confluence,
            at = breakout.confirmedAt
        )

        assertEquals(OpportunityState.OPPORTUNITY_FOUND, opportunity.state)
        assertEquals(breakout.id, context.breakoutId)
        assertEquals(breakout.confirmedIndex, context.asOfIndex)
        assertEquals(
            SetupConfluenceAssessmentEngine.assess(context),
            confluence
        )
        assertNotNull(opportunity.id.value)

        return PipelineResult(
            breakoutId = breakout.id,
            qualification = qualification.status,
            context = context,
            confluence = confluence,
            opportunity = opportunity
        )
    }

    @Test
    fun candleDerivedPipelineComposesDeterministicallyAndRejectsLookahead() {
        val first = runPipeline()
        val second = runPipeline()

        assertEquals(first.breakoutId, second.breakoutId)
        assertEquals(first.qualification, second.qualification)
        assertEquals(first.context, second.context)
        assertEquals(first.confluence, second.confluence)
        assertEquals(first.opportunity, second.opportunity)
    }
}
