package com.algotrader.domain.fno

import java.math.BigDecimal

/**
 * Fixture provenance from FNO design §8.7.
 *
 * This is deliberately separate from [Evidence]. Evidence describes the
 * confidence of a calculation/input; fixture status describes what was
 * established about the captured fixture field.
 */
enum class FixtureStatus {
    VERIFIED,
    SUPPLIED_EARLIER,
    UNKNOWN,
    INVALID,
}

/**
 * One captured fixture field.
 *
 * UNKNOWN and INVALID never carry a usable value. A valid zero, where the
 * underlying domain permits zero, remains a real value and is never converted
 * to UNKNOWN.
 */
data class FixtureValue<T>(
    val value: T?,
    val status: FixtureStatus,
    val note: String = "",
) {
    init {
        if (status == FixtureStatus.UNKNOWN || status == FixtureStatus.INVALID) {
            require(value == null) {
                "$status fixture values must not carry a usable value"
            }
        }
    }

    val isKnown: Boolean
        get() = status == FixtureStatus.VERIFIED ||
            status == FixtureStatus.SUPPLIED_EARLIER

    fun toInput(): Input<T> = when (status) {
        FixtureStatus.VERIFIED ->
            Input.Known(checkNotNull(value), Evidence.VERIFIED)

        FixtureStatus.SUPPLIED_EARLIER ->
            Input.Known(checkNotNull(value), Evidence.SUPPLIED)

        FixtureStatus.UNKNOWN ->
            Input.Unknown

        FixtureStatus.INVALID ->
            Input.Invalid(
                "value comes from an INVALID fixture capture" +
                    if (note.isBlank()) "" else ": $note"
            )
    }

    companion object {
        fun <T> verified(value: T, note: String = "") =
            FixtureValue(value, FixtureStatus.VERIFIED, note)

        fun <T> suppliedEarlier(value: T, note: String = "") =
            FixtureValue(value, FixtureStatus.SUPPLIED_EARLIER, note)

        fun <T> unknown(note: String = "") =
            FixtureValue<T>(null, FixtureStatus.UNKNOWN, note)

        fun <T> invalid(note: String = "") =
            FixtureValue<T>(null, FixtureStatus.INVALID, note)
    }
}

/**
 * A named fixture field.
 *
 * Keeping the field name explicit prevents a comparison from silently
 * becoming associated with a different screenshot/displayed value.
 */
data class FixtureField<T>(
    val name: String,
    val captured: FixtureValue<T>,
)

/**
 * Generic fixture comparison entry point.
 *
 * Rules:
 * - UNKNOWN expected -> NOT_COMPARABLE.
 * - INVALID expected capture -> NOT_COMPARABLE.
 * - INVALID actual result -> INVALID_INPUT.
 * - unavailable actual result -> NOT_COMPARABLE.
 * - otherwise compare numerically using the supplied tolerance.
 * - no tolerance is invented here.
 *
 * A PASS is only an arithmetic comparison. It never promotes an unverified
 * broker formula, rate, rounding rule, margin calculation or break-even
 * method.
 */
object FnoFixtureComparison {

    fun compare(
        computedOutput: String,
        actual: FnoResult<BigDecimal>,
        expected: FixtureField<BigDecimal>,
        tolerance: BigDecimal,
        inputIds: Set<InputId> = actual.dependsOn,
    ): ComparisonRecord {
        require(tolerance >= BigDecimal.ZERO) {
            "comparison tolerance must be non-negative"
        }

        val expectedValue = expected.captured.value

        if (expected.captured.status == FixtureStatus.UNKNOWN) {
            return ComparisonRecord(
                computedOutput = computedOutput,
                expectedValue = null,
                fixtureField = expected.name,
                tolerance = tolerance,
                inputIds = inputIds.toList(),
                state = ComparisonState.NOT_COMPARABLE,
                computedValue = actual.value,
                reasons = listOf(
                    "fixture field '${expected.name}' is UNKNOWN"
                ),
            )
        }

        if (expected.captured.status == FixtureStatus.INVALID) {
            return ComparisonRecord(
                computedOutput = computedOutput,
                expectedValue = null,
                fixtureField = expected.name,
                tolerance = tolerance,
                inputIds = inputIds.toList(),
                state = ComparisonState.NOT_COMPARABLE,
                computedValue = actual.value,
                reasons = listOf(
                    "fixture field '${expected.name}' is INVALID capture" +
                        if (expected.captured.note.isBlank()) {
                            ""
                        } else {
                            ": ${expected.captured.note}"
                        }
                ),
            )
        }

        if (actual.kind == ResultKind.INVALID_INPUT) {
            return ComparisonRecord(
                computedOutput = computedOutput,
                expectedValue = expectedValue,
                fixtureField = expected.name,
                tolerance = tolerance,
                inputIds = inputIds.toList(),
                state = ComparisonState.INVALID_INPUT,
                computedValue = null,
                reasons = actual.warnings.ifEmpty {
                    listOf("computed output is INVALID_INPUT")
                },
            )
        }

        if (!actual.isAvailable) {
            val reason = if (actual.missingInputs.isNotEmpty()) {
                "computed output is unavailable; missing inputs: " +
                    actual.missingInputs.joinToString { it.code }
            } else {
                "computed output is unavailable"
            }

            return ComparisonRecord(
                computedOutput = computedOutput,
                expectedValue = expectedValue,
                fixtureField = expected.name,
                tolerance = tolerance,
                inputIds = inputIds.toList(),
                state = ComparisonState.NOT_COMPARABLE,
                computedValue = null,
                reasons = listOf(reason),
            )
        }

        val computed = checkNotNull(actual.value)
        val expectedKnown = checkNotNull(expectedValue)

        val difference = computed.subtract(expectedKnown).abs()
        val state = if (difference <= tolerance) {
            ComparisonState.PASS
        } else {
            ComparisonState.FAIL
        }

        return ComparisonRecord(
            computedOutput = computedOutput,
            expectedValue = expectedKnown,
            fixtureField = expected.name,
            tolerance = tolerance,
            inputIds = inputIds.toList(),
            state = state,
            computedValue = computed,
            reasons = if (state == ComparisonState.FAIL) {
                listOf(
                    "absolute difference $difference exceeds tolerance $tolerance"
                )
            } else {
                emptyList()
            },
        )
    }
}
