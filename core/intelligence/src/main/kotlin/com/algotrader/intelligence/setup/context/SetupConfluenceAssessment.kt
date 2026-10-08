package com.algotrader.intelligence.setup.context

/**
 * Deterministic confluence interpretation for ASI-3.5 context.
 *
 * Factor categories are counted once by kind. Multiple observations from the
 * same category cannot manufacture additional independent confluence.
 */
data class SetupConfluenceAssessment(
    val quality: SetupQuality,
    val supportingFactorKinds: List<SetupContextFactorKind>,
    val conflictingFactorKinds: List<SetupContextFactorKind>
) {
    init {
        require(
            supportingFactorKinds == supportingFactorKinds.distinct().sortedBy { it.ordinal }
        ) { "supporting factor kinds must be unique and deterministic" }

        require(
            conflictingFactorKinds == conflictingFactorKinds.distinct().sortedBy { it.ordinal }
        ) { "conflicting factor kinds must be unique and deterministic" }
    }

    val supportingFactorCount: Int
        get() = supportingFactorKinds.size

    val conflictingFactorCount: Int
        get() = conflictingFactorKinds.size
}

object SetupConfluenceAssessmentEngine {

    fun assess(context: SetupContext): SetupConfluenceAssessment {
        val supporting = context.supportingFactors
            .map { it.kind }
            .distinct()
            .sortedBy { it.ordinal }

        val conflicting = context.conflictingFactors
            .map { it.kind }
            .distinct()
            .sortedBy { it.ordinal }

        val quality = classify(
            supportingCount = supporting.size,
            conflictingCount = conflicting.size
        )

        return SetupConfluenceAssessment(
            quality = quality,
            supportingFactorKinds = supporting,
            conflictingFactorKinds = conflicting
        )
    }

    private fun classify(
        supportingCount: Int,
        conflictingCount: Int
    ): SetupQuality {
        if (supportingCount == 0 && conflictingCount == 0) {
            return SetupQuality.INSUFFICIENT
        }

        if (supportingCount >= 3 && conflictingCount == 0) {
            return SetupQuality.STRONG
        }

        if (
            supportingCount >= 2 &&
            conflictingCount <= 1
        ) {
            return SetupQuality.MODERATE
        }

        return SetupQuality.WEAK
    }
}
