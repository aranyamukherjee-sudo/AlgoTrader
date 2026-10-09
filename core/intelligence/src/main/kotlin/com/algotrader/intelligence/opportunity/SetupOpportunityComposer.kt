package com.algotrader.intelligence.opportunity

import com.algotrader.domain.Timeframe
import com.algotrader.intelligence.dna.InstrumentRef
import com.algotrader.intelligence.dna.StrategyRef
import com.algotrader.intelligence.dna.TradeSide
import com.algotrader.intelligence.setup.BreakoutDirection
import com.algotrader.intelligence.setup.BreakoutSetup
import com.algotrader.intelligence.setup.SetupLiveAssessment
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessment
import com.algotrader.intelligence.setup.context.SetupConfluenceAssessmentEngine
import com.algotrader.intelligence.setup.context.SetupContext
import java.time.Instant

/**
 * ASI-3.6 composition boundary.
 *
 * Composes an already-established BreakoutSetup with its existing LIVE
 * evidence/confidence assessment and ASI-3.5 context/confluence into the
 * existing Opportunity model.
 *
 * This component deliberately does NOT:
 * - detect breakouts or patterns;
 * - recalculate qualification;
 * - extract evidence;
 * - calculate confidence;
 * - advance setup lifecycle;
 * - advance Opportunity beyond OPPORTUNITY_FOUND;
 * - convert context/confluence into artificial EvidenceItems.
 *
 * Existing ASI-3.4 and ASI-3.5 components remain the owners of those concerns.
 */
object SetupOpportunityComposer {

    fun compose(
        setup: BreakoutSetup,
        strategy: StrategyRef,
        instrument: InstrumentRef,
        timeframe: Timeframe,
        assessment: SetupLiveAssessment,
        context: SetupContext,
        confluence: SetupConfluenceAssessment,
        at: Instant
    ): Opportunity {
        require(instrument.isTradable) {
            "an opportunity requires a tradable instrument"
        }

        require(context.breakoutId == setup.breakout.id) {
            "context belongs to ${context.breakoutId}, not ${setup.breakout.id}"
        }

        require(context.direction == setup.breakout.direction) {
            "context direction ${context.direction} does not match setup direction ${setup.breakout.direction}"
        }

        require(context.asOfIndex >= setup.breakout.confirmedIndex) {
            "context asOfIndex must be at or after breakout confirmation"
        }

        require(assessment.evidenceAssessment == null ||
            assessment.evidenceAssessment.breakoutId == setup.breakout.id
        ) {
            "evidence assessment belongs to a different breakout"
        }

        require(assessment.evidenceAssessment == null ||
            assessment.evidenceAssessment.asOfIndex == context.asOfIndex
        ) {
            "evidence assessment and context must use the same asOfIndex"
        }

        require(assessment.evidence.items.all { it.strategy == strategy }) {
            "all live evidence must belong to the exact strategy version"
        }

        require(assessment.evidence.items.all {
            it.scope == com.algotrader.intelligence.evidence.EvidenceScope.LIVE
        }) {
            "opportunity composition accepts LIVE evidence only"
        }

        val expectedConfluence =
            SetupConfluenceAssessmentEngine.assess(context)

        require(expectedConfluence == confluence) {
            "confluence assessment does not match the supplied context"
        }

        val side = when (setup.breakout.direction) {
            BreakoutDirection.UP -> TradeSide.LONG
            BreakoutDirection.DOWN -> TradeSide.SHORT
        }

        return Opportunity.detected(
            id = OpportunityId(
                "${strategy.id.value}@${strategy.version}#${setup.breakout.id.value}"
            ),
            strategy = strategy,
            instrument = instrument,
            timeframe = timeframe,
            side = side,
            at = at,
            confidence = assessment.reading?.value,
            evidence = assessment.evidence,
            reason = "Setup opportunity composed"
        )
    }
}
