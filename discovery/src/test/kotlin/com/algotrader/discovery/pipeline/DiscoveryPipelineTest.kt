package com.algotrader.discovery.pipeline

import com.algotrader.discovery.candidate.BreakoutCandidateGenerator
import com.algotrader.discovery.candidate.CandidateGenerator
import com.algotrader.discovery.fixtures.DiscoveryFixtures
import com.algotrader.discovery.fixtures.DiscoveryFixtures.SIMPLE
import com.algotrader.discovery.fixtures.DiscoveryFixtures.named
import com.algotrader.discovery.fixtures.DiscoveryFixtures.request
import com.algotrader.discovery.fixtures.SyntheticCandles
import com.algotrader.discovery.scoring.DiscoveryPolicy
import com.algotrader.discovery.split.SegmentRole
import com.algotrader.intelligence.common.TransitionResult
import com.algotrader.intelligence.confidence.ConfidenceHistory
import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.FeatureDomain
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.evidence.EvidenceSample
import com.algotrader.intelligence.evidence.EvidenceScope
import com.algotrader.intelligence.lifecycle.StrategyLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * All data here is synthetic and deterministic (see SyntheticCandles). The
 * expected outcomes are properties of how the fixtures were constructed, not
 * statements about real markets.
 */
class DiscoveryPipelineTest {

    private val pipeline = DiscoveryPipeline(BreakoutCandidateGenerator())

    private val strong by lazy { pipeline.run(request(SyntheticCandles.allStrong)) }
    private val trap by lazy { pipeline.run(request(SyntheticCandles.allTrap)) }
    private val vanishesInValidation by lazy { pipeline.run(request(SyntheticCandles.edgeVanishesInValidation)) }
    private val vanishesInHoldout by lazy { pipeline.run(request(SyntheticCandles.edgeVanishesInHoldout)) }

    // ---- acceptance and rejection ----

    @Test
    fun `a deliberately strong fixture produces promising candidates`() {
        assertTrue(strong.promising.isNotEmpty())
        val simple = strong.candidates.named(SIMPLE)
        assertEquals(DiscoveryStage.PROMISING, simple.stage)
        assertTrue(simple.rejectionReasons.isEmpty())
        assertTrue(simple.train.trades >= 5 && simple.validation!!.trades >= 3 && simple.holdout!!.trades >= 3)
        assertEquals(48, strong.runInfo.candidatesGenerated)
        assertEquals(strong.promising.size, strong.runInfo.promising)
    }

    @Test
    fun `a fixture with no edge produces no promising candidates and explains why`() {
        assertTrue(trap.promising.isEmpty())
        assertEquals(48, trap.rejected.size)
        assertTrue(trap.candidates.all { it.stage == DiscoveryStage.REJECTED_TRAIN })
        val simple = trap.candidates.named(SIMPLE)
        assertTrue(simple.rejectionReasons.any { it.startsWith("TRAIN/net_profit") })
        assertTrue(simple.train.netProfit < 0.0)
    }

    @Test
    fun `candidates that never trade are rejected on trade count, not promoted`() {
        // a 40-bar breakout never fires in the strong fixture
        val neverTrades = strong.candidates.first { it.dna.name.startsWith("Breakout 40") }
        assertEquals(DiscoveryStage.REJECTED_TRAIN, neverTrades.stage)
        assertTrue(neverTrades.rejectionReasons.any { it.contains("trade_count") })
    }

    @Test
    fun `promising candidates are listed first, best validation score first`() {
        val n = strong.promising.size
        assertTrue(strong.candidates.take(n).all { it.isPromising })
        assertTrue(strong.candidates.drop(n).none { it.isPromising })
        val scores = strong.promising.map { it.score!!.value }
        assertEquals(scores.sortedDescending(), scores)
    }

    // ---- training / validation / holdout separation ----

    @Test
    fun `a candidate that is great in training but fails on unseen validation data is rejected`() {
        val simple = vanishesInValidation.candidates.named(SIMPLE)
        assertTrue(simple.train.netProfit > 0.0) // it looked good on the training data...
        assertEquals(DiscoveryStage.REJECTED_VALIDATION, simple.stage) // ...and was rejected on unseen data
        assertTrue(simple.validation!!.netProfit < 0.0)
        assertNull(simple.holdout)
        assertTrue(vanishesInValidation.promising.isEmpty())
    }

    @Test
    fun `a candidate that survives validation can still fail the untouched holdout`() {
        val simple = vanishesInHoldout.candidates.named(SIMPLE)
        assertEquals(DiscoveryStage.REJECTED_HOLDOUT, simple.stage)
        assertTrue(simple.train.netProfit > 0.0 && simple.validation!!.netProfit > 0.0 && simple.holdout!!.netProfit < 0.0)
        assertNull(simple.strategyRecord)
        assertTrue(vanishesInHoldout.promising.isEmpty())
    }

    @Test
    fun `every evaluation runs on exactly its own segment`() {
        val split = request(SyntheticCandles.allStrong).split
        for (run in strong.phaseLog) {
            val segment = split.segment(run.phase)
            assertEquals(segment.first().timestamp, run.firstTimestamp, "${run.phase} start")
            assertEquals(segment.last().timestamp, run.lastTimestamp, "${run.phase} end")
            assertEquals(segment.size, run.bars)
        }
    }

    @Test
    fun `later segments are only touched by survivors of earlier ones, once each`() {
        for (result in listOf(strong, trap, vanishesInValidation, vanishesInHoldout)) {
            fun ids(phase: SegmentRole) = result.phaseLog.filter { it.phase == phase }.map { it.candidate }
            val train = ids(SegmentRole.TRAIN)
            val validation = ids(SegmentRole.VALIDATION)
            val holdout = ids(SegmentRole.HOLDOUT)

            // each candidate is evaluated at most once per segment
            assertEquals(train.size, train.toSet().size)
            assertEquals(validation.size, validation.toSet().size)
            assertEquals(holdout.size, holdout.toSet().size)
            // everyone is trained; validation is for train survivors only; holdout for validation survivors only
            assertEquals(result.runInfo.candidatesGenerated, train.size)
            assertEquals(result.runInfo.trainSurvivors, validation.size)
            assertEquals(result.runInfo.validationSurvivors, holdout.size)
            assertTrue(train.containsAll(validation))
            assertTrue(validation.containsAll(holdout))

            // the log agrees with each candidate's own record
            for (record in result.candidates) {
                val id = record.dna.id
                assertEquals(record.stage != DiscoveryStage.REJECTED_TRAIN, id in validation, "validation use of $id")
                assertEquals(
                    record.stage == DiscoveryStage.REJECTED_HOLDOUT || record.stage == DiscoveryStage.PROMISING,
                    id in holdout, "holdout use of $id"
                )
            }
        }
    }

    @Test
    fun `the holdout is never touched when nothing survives validation`() {
        assertTrue(vanishesInValidation.phaseLog.none { it.phase == SegmentRole.HOLDOUT })
        assertTrue(trap.phaseLog.none { it.phase == SegmentRole.VALIDATION || it.phase == SegmentRole.HOLDOUT })
    }

    @Test
    fun `changing validation and holdout data cannot change training results or what is selected`() {
        // same first 720 bars (training segment), different data afterwards
        val byId = { r: DiscoveryResult -> r.candidates.associate { it.dna.id to it } }
        val strongById = byId(strong)
        val alteredById = byId(vanishesInValidation)
        assertEquals(strongById.keys, alteredById.keys)
        for ((id, record) in strongById) {
            assertEquals(record.train, alteredById.getValue(id).train, "train result of $id")
            assertEquals(
                record.stage == DiscoveryStage.REJECTED_TRAIN,
                alteredById.getValue(id).stage == DiscoveryStage.REJECTED_TRAIN,
                "train selection of $id"
            )
        }
    }

    @Test
    fun `changing only the holdout data cannot change validation results`() {
        val strongById = strong.candidates.associateBy { it.dna.id }
        for (record in vanishesInHoldout.candidates) {
            val before = strongById.getValue(record.dna.id)
            assertEquals(before.train, record.train)
            assertEquals(before.validation, record.validation, "validation result of ${record.dna.id}")
        }
    }

    // ---- reproducibility ----

    @Test
    fun `the same inputs give identical results`() {
        val again = DiscoveryPipeline(BreakoutCandidateGenerator()).run(request(SyntheticCandles.allStrong))
        assertEquals(strong, again)
        assertEquals(strong.runInfo.runKey, again.runInfo.runKey)
        assertEquals(strong.candidates.map { it.dna.id }, again.candidates.map { it.dna.id })
    }

    @Test
    fun `the run key changes with the data and with the policy`() {
        assertNotEquals(strong.runInfo.runKey, trap.runInfo.runKey)
        assertNotEquals(strong.runInfo.datasetFingerprint, trap.runInfo.datasetFingerprint)
        val stricter = pipeline.run(request(SyntheticCandles.allStrong, policy = DiscoveryFixtures.relaxedPolicy.copy(minConsistency = 0.75)))
        assertNotEquals(strong.runInfo.runKey, stricter.runInfo.runKey)
    }

    // ---- ASI-1 integration: evidence, confidence, lifecycle ----

    @Test
    fun `promising candidates enter the lifecycle at PROMISING and no further`() {
        for (record in strong.promising) {
            val strategyRecord = record.strategyRecord!!
            assertEquals(StrategyLifecycle.PROMISING, strategyRecord.lifecycle)
            assertEquals(
                listOf(StrategyLifecycle.VALIDATING, StrategyLifecycle.PROMISING),
                strategyRecord.history.map { it.to }
            )
            assertEquals(record.dna, strategyRecord.dna)
            assertTrue(!strategyRecord.lifecycle.canDetectOpportunities)
        }
        assertTrue(strong.candidates.none {
            it.strategyRecord?.lifecycle == StrategyLifecycle.MONITORING ||
                it.strategyRecord?.lifecycle == StrategyLifecycle.ACTIVE
        })
    }

    @Test
    fun `an index-only candidate cannot be moved to live monitoring`() {
        val result = pipeline.run(request(SyntheticCandles.allStrong, market = DiscoveryFixtures.indexOnlyMarket))
        val record = result.promising.first().strategyRecord!!
        assertEquals(StrategyLifecycle.PROMISING, record.lifecycle)
        val attempt = record.transitionTo(StrategyLifecycle.MONITORING, DiscoveryFixtures.at, "try")
        assertTrue(attempt is TransitionResult.Rejected)
        assertTrue((attempt as TransitionResult.Rejected).reason.contains("trade target"))
    }

    @Test
    fun `rejected candidates stay research results only`() {
        for (result in listOf(strong, trap, vanishesInValidation, vanishesInHoldout)) {
            for (record in result.rejected) {
                assertNull(record.strategyRecord)
                assertNull(record.confidence)
                assertTrue(record.evidence.isEmpty)
                assertTrue(record.rejectionReasons.isNotEmpty())
            }
        }
    }

    @Test
    fun `evidence is research evidence bound to the exact strategy version and samples`() {
        for (record in strong.promising) {
            assertTrue(record.evidence.items.all { it.scope == EvidenceScope.RESEARCH })
            assertTrue(record.evidence.items.all { it.strategy == record.dna.ref })
            assertTrue(record.evidence.hasSample(EvidenceSample.IN_SAMPLE))
            assertTrue(record.evidence.hasSample(EvidenceSample.VALIDATION))
            assertTrue(record.evidence.hasSample(EvidenceSample.OUT_OF_SAMPLE))
            assertEquals(record.evidence.refs(), record.dna.evidenceRefs)
            assertEquals(record.evidence, record.strategyRecord!!.evidence)
            val outOfSample = record.evidence.items.first { it.sample == EvidenceSample.OUT_OF_SAMPLE }
            assertEquals(record.holdout!!.trades, outOfSample.observations)
            assertTrue(outOfSample.source.reference.contains(strong.runInfo.runKey))
            assertTrue(outOfSample.source.reference.contains(record.dna.id.value))
        }
    }

    @Test
    fun `confidence uses the single asi-1 scale and is not a probability of profit`() {
        for (record in strong.promising) {
            val confidence = record.confidence!!
            assertEquals(minOf(record.score!!.value, DiscoveryFixtures.relaxedPolicy.maxConfidence), confidence.value, 1e-12)
            assertTrue(confidence.value in 0.0..1.0)
            assertTrue(confidence.reason.contains("not a probability of profit"))
            assertEquals(confidence.value, ConfidenceHistory(listOf(confidence)).current) // plugs into ASI-1 history
        }
    }

    @Test
    fun `confidence is capped below certainty`() {
        val capped = pipeline.run(request(SyntheticCandles.allStrong, policy = DiscoveryFixtures.relaxedPolicy.copy(maxConfidence = 0.5)))
        assertTrue(capped.promising.isNotEmpty())
        assertTrue(capped.promising.all { it.confidence!!.value <= 0.5 })
    }

    // ---- record invariants ----

    @Test
    fun `a candidate record cannot be silently promoted or demoted`() {
        val promising = strong.candidates.named(SIMPLE)
        val rejected = trap.candidates.named(SIMPLE)

        assertFailsWith<IllegalArgumentException> { promising.copy(strategyRecord = null) }
        assertFailsWith<IllegalArgumentException> { promising.copy(confidence = null) }
        assertFailsWith<IllegalArgumentException> { promising.copy(stage = DiscoveryStage.REJECTED_TRAIN) }
        assertFailsWith<IllegalArgumentException> {
            rejected.copy(strategyRecord = promising.strategyRecord)
        }
        assertFailsWith<IllegalArgumentException> {
            rejected.copy(stage = DiscoveryStage.PROMISING)
        }
        assertFailsWith<IllegalArgumentException> {
            rejected.copy(validation = promising.validation)
        }
    }

    // ---- guards ----

    @Test
    fun `the candidate cap is enforced`() {
        val tiny = DiscoveryFixtures.relaxedPolicy.copy(maxCandidates = 10)
        assertFailsWith<IllegalArgumentException> { pipeline.run(request(SyntheticCandles.allStrong, policy = tiny)) }
    }

    @Test
    fun `candidates using unsupported features or duplicate ids are refused`() {
        val dnas = BreakoutCandidateGenerator().generate(DiscoveryFixtures.futuresMarket, com.algotrader.domain.Timeframe.MINUTE_15)
        val unknown = dnas.first().copy(
            entry = Condition.Leaf("breakout", FeatureRef(FeatureDomain.CUSTOM, "no_such_feature"), "unknown")
        )
        assertFailsWith<IllegalArgumentException> {
            DiscoveryPipeline(CandidateGenerator { _, _ -> listOf(unknown) }).run(request(SyntheticCandles.allStrong))
        }
        assertFailsWith<IllegalArgumentException> {
            DiscoveryPipeline(CandidateGenerator { _, _ -> listOf(dnas.first(), dnas.first()) }).run(request(SyntheticCandles.allStrong))
        }
        assertFailsWith<IllegalArgumentException> {
            DiscoveryPipeline(CandidateGenerator { _, _ -> emptyList() }).run(request(SyntheticCandles.allStrong))
        }
    }
}
