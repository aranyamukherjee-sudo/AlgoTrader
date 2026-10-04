package com.algotrader.domain.fno

/**
 * FNO-1 (Phase 3) generic F&O futures calculation contract.
 *
 * Implements docs/phase3/FNO_CAPITAL_AND_COSTS_DESIGN.md §8. Nothing in this
 * package contains a broker formula, a charge rate, a margin percentage, a
 * rounding rule or any fixture value (§8.7 structural rule 4). Everything that
 * is broker- or exchange-specific arrives as a SUPPLIED input.
 */

/** Input identifiers I1..I22 from §8.2. */
enum class InputId(val code: String) {
    BROKER("I1"),
    EXCHANGE("I2"),
    SEGMENT("I3"),
    UNDERLYING("I4"),
    CONTRACT_MONTH("I5"),
    EXPIRY("I6"),
    CONTRACT_ID("I7"),
    LOT_SIZE("I8"),
    LOTS("I9"),
    ENTRY_PRICE("I10"),
    EXIT_PRICE("I11"),
    DIRECTION("I12"),
    ORDER_TYPE("I13"),
    PRODUCT("I14"),
    MARGIN("I15"),
    BROKERAGE("I16"),
    STATUTORY_RATES("I17"),
    BROKER_RULES("I18"),
    ESTIMATE_DATE("I19"),
    REFERENCE_PRICE_TYPE("I20"),
    MARKET_STATUS("I21"),
    VALUATION_PRICE("I22"),
}

/** Result kind, §8.4a rule 2 plus the two null kinds of rule 1. */
enum class ResultKind { DERIVED, SUPPLIED, NOT_MODELLED, INVALID_INPUT }

/**
 * Evidence level of a non-null result, §8.4a rule 3. Declared strongest first;
 * a result carries the weakest evidence among its inputs.
 */
enum class Evidence {
    /** Authoritative or valid-capture evidence. */
    VERIFIED,

    /** Provided but not independently verified (includes SUPPLIED-EARLIER). */
    SUPPLIED,
}

internal fun weakest(a: Evidence, b: Evidence): Evidence = if (a.ordinal >= b.ordinal) a else b

/** State of one input: known (with evidence), unknown (null), or invalid. */
sealed interface Input<out T> {
    /**
     * A supplied value. [evidence] defaults to [Evidence.SUPPLIED]: a value is
     * never silently treated as verified.
     */
    data class Known<out T>(val value: T, val evidence: Evidence = Evidence.SUPPLIED) : Input<T>

    /** Missing / unknown. Never 0, never a default (§8.1 "Missing is null"). */
    data object Unknown : Input<Nothing>

    /** Present but failed capture or validation. */
    data class Invalid(val reason: String) : Input<Nothing>
}

/** Fixture capture validity (§8.1 fixture statuses, §8.7 rule 3). */
enum class CaptureStatus { VALID, INVALID }

/**
 * A value read from a screenshot / estimator capture. An INVALID capture can
 * never supply an input or an expected value; a VALID capture of zero is a
 * real zero.
 */
data class CapturedValue<out T>(
    val value: T,
    val status: CaptureStatus,
    val note: String = "",
) {
    fun toInput(evidence: Evidence = Evidence.SUPPLIED): Input<T> = when (status) {
        CaptureStatus.VALID -> Input.Known(value, evidence)
        CaptureStatus.INVALID -> Input.Invalid("value comes from an INVALID capture" + if (note.isBlank()) "" else ": $note")
    }
}

/**
 * Output wrapper (§8.4): value (nullable), kind, evidence, the input IDs the
 * output depended on, missing/invalid IDs and warnings.
 *
 * Invariant: [value] is non-null exactly when [kind] is DERIVED or SUPPLIED,
 * and [evidence] is non-null exactly when [value] is non-null.
 */
data class FnoResult<out T>(
    val value: T?,
    val kind: ResultKind,
    val evidence: Evidence?,
    val dependsOn: Set<InputId> = emptySet(),
    val missingInputs: Set<InputId> = emptySet(),
    val invalidInputs: Set<InputId> = emptySet(),
    val warnings: List<String> = emptyList(),
    val labels: Set<String> = emptySet(),
) {
    init {
        val hasValue = kind == ResultKind.DERIVED || kind == ResultKind.SUPPLIED
        require((value != null) == hasValue) { "value must be non-null exactly for DERIVED/SUPPLIED results (kind=$kind)" }
        require((evidence != null) == hasValue) { "evidence must be non-null exactly when a value exists (kind=$kind)" }
    }

    val isAvailable: Boolean get() = value != null

    /** Unwraps a value known to be present; only used after availability checks. */
    internal fun requireValue(): T = checkNotNull(value) { "result unavailable ($kind, missing=$missingInputs, invalid=$invalidInputs)" }

    companion object {
        fun <T> supplied(
            value: T,
            evidence: Evidence,
            dependsOn: Set<InputId>,
            warnings: List<String> = emptyList(),
            labels: Set<String> = emptySet(),
        ): FnoResult<T> = FnoResult(value, ResultKind.SUPPLIED, evidence, dependsOn, emptySet(), emptySet(), warnings, labels)

        fun <T> derived(
            value: T,
            evidence: Evidence,
            dependsOn: Set<InputId>,
            warnings: List<String> = emptyList(),
            labels: Set<String> = emptySet(),
        ): FnoResult<T> = FnoResult(value, ResultKind.DERIVED, evidence, dependsOn, emptySet(), emptySet(), warnings, labels)

        fun <T> notModelled(
            dependsOn: Set<InputId>,
            missing: Set<InputId>,
            warnings: List<String> = emptyList(),
            labels: Set<String> = emptySet(),
        ): FnoResult<T> = FnoResult(null, ResultKind.NOT_MODELLED, null, dependsOn, missing, emptySet(), warnings, labels)

        fun <T> invalid(
            dependsOn: Set<InputId>,
            invalid: Set<InputId>,
            missing: Set<InputId> = emptySet(),
            warnings: List<String> = emptyList(),
            labels: Set<String> = emptySet(),
        ): FnoResult<T> = FnoResult(null, ResultKind.INVALID_INPUT, null, dependsOn, missing, invalid, warnings, labels)
    }
}

/** Re-types an unavailable (null) result without changing its kind, dependencies or warnings. */
internal fun <T> FnoResult<*>.unavailable(): FnoResult<T> {
    check(!isAvailable) { "result is available" }
    return FnoResult(null, kind, null, dependsOn, missingInputs, invalidInputs, warnings, labels)
}

/** Converts an input to a result, applying [validate] (returns a reason when invalid). */
internal fun <T> Input<T>.asResult(id: InputId, validate: (T) -> String? = { null }): FnoResult<T> = when (this) {
    is Input.Known -> {
        val problem = validate(value)
        if (problem == null) FnoResult.supplied(value, evidence, setOf(id))
        else FnoResult.invalid(setOf(id), setOf(id), warnings = listOf("${id.code} ${id.name}: $problem"))
    }
    Input.Unknown -> FnoResult.notModelled(setOf(id), setOf(id))
    is Input.Invalid -> FnoResult.invalid(setOf(id), setOf(id), warnings = listOf("${id.code} ${id.name}: $reason"))
}

/**
 * Null propagation (§8.4a rule 1): any INVALID dependency makes the result
 * INVALID_INPUT; otherwise any unavailable dependency makes it NOT_MODELLED;
 * otherwise [compute] runs and the result is DERIVED with the weakest evidence
 * of the dependencies. A missing input never produces 0 or a default.
 */
internal fun <T> derive(
    deps: List<FnoResult<*>>,
    extraDependsOn: Set<InputId> = emptySet(),
    extraWarnings: List<String> = emptyList(),
    extraLabels: Set<String> = emptySet(),
    compute: () -> T,
): FnoResult<T> {
    val dependsOn = deps.flatMapTo(LinkedHashSet(extraDependsOn)) { it.dependsOn }
    val missing = deps.flatMapTo(LinkedHashSet()) { it.missingInputs }
    val invalid = deps.flatMapTo(LinkedHashSet()) { it.invalidInputs }
    val warnings = (deps.flatMap { it.warnings } + extraWarnings).distinct()
    val labels = deps.flatMapTo(LinkedHashSet(extraLabels)) { it.labels }
    if (deps.any { it.kind == ResultKind.INVALID_INPUT }) {
        return FnoResult.invalid(dependsOn, invalid, missing, warnings, labels)
    }
    if (deps.any { !it.isAvailable }) {
        return FnoResult.notModelled(dependsOn, missing, warnings, labels)
    }
    val evidence = deps.map { it.evidence!! }.reduceOrNull(::weakest) ?: Evidence.SUPPLIED
    return try {
        FnoResult.derived(compute(), evidence, dependsOn, warnings, labels)
    } catch (e: ArithmeticException) {
        FnoResult.invalid(dependsOn, emptySet(), emptySet(), warnings + "arithmetic failure: ${e.message}", labels)
    }
}
