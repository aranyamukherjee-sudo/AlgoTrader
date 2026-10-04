package com.algotrader.domain.fno

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/*
 * Validated charge schedule abstraction (§8.5).
 *
 * A schedule "counts as validated only through documentary evidence: it is
 * dated (effective-from), cites an authoritative source, and is complete for
 * every component, covering rate, applicable side, calculation base, rounding,
 * and any cap or minimum." This file only models that structure and checks it
 * is complete. It ships NO schedule, rate, rounding rule or brokerage rule:
 * every value arrives from the caller, and a schedule whose numbers merely
 * match a fixture is not validated by that fact.
 *
 * What the validator can and cannot do: it checks that the evidence fields are
 * present and the schedule is internally unambiguous. It cannot check that the
 * citation is genuine; that remains a documentary-review step.
 */

enum class ScheduleSourceKind { EXCHANGE_CIRCULAR, REGULATOR_CIRCULAR, BROKER_PUBLISHED_SCHEDULE }

/** Authoritative source of a schedule. [citation] must identify the document (title, number, URL, date). */
data class ScheduleSource(val kind: ScheduleSourceKind, val citation: String)

/** Calculation base and rate of one component (§8.3 C8: the schedule defines the base). */
sealed interface ChargeBasis {
    /** [rate] (a fraction, not a percentage) multiplied by the leg value. */
    data class RateOfLegValue(val rate: BigDecimal) : ChargeBasis

    /** [rate] multiplied by the value of an EARLIER component of the same leg. */
    data class RateOfComponent(val component: String, val rate: BigDecimal) : ChargeBasis

    /** A flat [amount] per leg. */
    data class FlatPerLeg(val amount: BigDecimal) : ChargeBasis
}

/** Rounding declared by the schedule for one component. [None] is an explicit declaration, not a default. */
sealed interface Rounding {
    data object None : Rounding
    data class To(val scale: Int, val mode: RoundingMode) : Rounding
}

/** A minimum or cap declared by the schedule for one component. [None] is an explicit declaration. */
sealed interface Bound {
    data object None : Bound
    data class Of(val value: BigDecimal) : Bound
}

/**
 * One component of a schedule. Every field is required (no defaults) so that
 * incompleteness shows up as a missing declaration, never as an implicit rule.
 */
data class ChargeComponentSpec(
    val name: String,
    val appliesTo: Set<Side>,
    val basis: ChargeBasis,
    val rounding: Rounding,
    val minimum: Bound,
    val cap: Bound,
)

/**
 * Candidate schedule. [effectiveFrom] and [source] are nullable only so that
 * their absence can be reported by validation; a schedule missing either is
 * rejected. Component evaluation order is the list order, as declared.
 */
data class ChargeScheduleDefinition(
    val broker: String,
    val exchange: String,
    val segment: String,
    val effectiveFrom: LocalDate?,
    val source: ScheduleSource?,
    val components: List<ChargeComponentSpec>,
    /** True if the schedule's charges depend on order type (I13); then I13 must be supplied. */
    val keyedByOrderType: Boolean,
    /** True if the schedule's charges depend on product (I14); then I14 must be supplied. */
    val keyedByProduct: Boolean,
)

sealed interface ScheduleValidation {
    data class Valid(val schedule: ValidatedChargeSchedule) : ScheduleValidation
    data class Rejected(val errors: List<String>) : ScheduleValidation
}

/** One computed charge line of one leg. */
data class ChargeLine(val component: String, val amount: BigDecimal)

/** Charges of one leg, from that leg's own side and own leg value (§8.3 C8, §8.7 rule 2). */
data class LegCharges(
    val side: Side,
    val legValue: BigDecimal,
    val lines: List<ChargeLine>,
    val total: BigDecimal,
)

/** Charges of a leg as an exact affine function of leg value V: alpha + beta * V. */
internal data class AffineCharge(val alpha: BigDecimal, val beta: BigDecimal)

/**
 * A schedule that passed [validate]. The constructor is private, so one cannot
 * exist without having been checked for the evidence fields in §8.5.
 */
class ValidatedChargeSchedule private constructor(
    val broker: String,
    val exchange: String,
    val segment: String,
    val effectiveFrom: LocalDate,
    val source: ScheduleSource,
    val components: List<ChargeComponentSpec>,
    val keyedByOrderType: Boolean,
    val keyedByProduct: Boolean,
) {
    /** Computes one leg's charges, component by component, in the schedule's declared order. */
    fun legCharges(side: Side, legValue: BigDecimal): LegCharges {
        val values = LinkedHashMap<String, BigDecimal>()
        val lines = ArrayList<ChargeLine>()
        for (c in components) {
            if (side !in c.appliesTo) continue
            var v = when (val b = c.basis) {
                is ChargeBasis.RateOfLegValue -> legValue.multiply(b.rate)
                is ChargeBasis.RateOfComponent -> values.getValue(b.component).multiply(b.rate)
                is ChargeBasis.FlatPerLeg -> b.amount
            }
            // Validation guarantees a component never has both a bound and rounding.
            (c.minimum as? Bound.Of)?.let { if (v < it.value) v = it.value }
            (c.cap as? Bound.Of)?.let { if (v > it.value) v = it.value }
            (c.rounding as? Rounding.To)?.let { v = v.setScale(it.scale, it.mode) }
            values[c.name] = v
            lines += ChargeLine(c.name, v)
        }
        return LegCharges(side, legValue, lines, lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount) })
    }

    /**
     * Exact affine form of a leg's total charge in leg value, or null when any
     * applicable component has rounding, a minimum or a cap (then no closed
     * form exists and break-even is NOT_MODELLED).
     */
    internal fun affineForm(side: Side): AffineCharge? {
        val parts = HashMap<String, AffineCharge>()
        var alpha = BigDecimal.ZERO
        var beta = BigDecimal.ZERO
        for (c in components) {
            if (side !in c.appliesTo) continue
            if (c.rounding != Rounding.None || c.minimum != Bound.None || c.cap != Bound.None) return null
            val a = when (val b = c.basis) {
                is ChargeBasis.RateOfLegValue -> AffineCharge(BigDecimal.ZERO, b.rate)
                is ChargeBasis.RateOfComponent -> parts.getValue(b.component).let { AffineCharge(it.alpha.multiply(b.rate), it.beta.multiply(b.rate)) }
                is ChargeBasis.FlatPerLeg -> AffineCharge(b.amount, BigDecimal.ZERO)
            }
            parts[c.name] = a
            alpha = alpha.add(a.alpha)
            beta = beta.add(a.beta)
        }
        return AffineCharge(alpha, beta)
    }

    companion object {
        /**
         * Validates completeness and unambiguity. Rejections are reported, never repaired.
         *
         * Besides the §8.5 evidence checks, two combinations are rejected because
         * §8 does not define their meaning and ALTRIXA must not invent it:
         *  - a component with rounding AND a minimum/cap (order of the two is undefined);
         *  - a rounded component used as the base of another component (whether the
         *    dependent uses the rounded or unrounded value is undefined).
         */
        fun validate(def: ChargeScheduleDefinition): ScheduleValidation {
            val errors = ArrayList<String>()
            if (def.broker.isBlank()) errors += "broker is required"
            if (def.exchange.isBlank()) errors += "exchange is required"
            if (def.segment.isBlank()) errors += "segment is required"
            if (def.effectiveFrom == null) errors += "effective-from date is required"
            val src = def.source
            if (src == null) errors += "authoritative source is required"
            else if (src.citation.isBlank()) errors += "source citation must identify the document"
            if (def.components.isEmpty()) errors += "at least one component is required"

            val earlier = LinkedHashMap<String, ChargeComponentSpec>()
            for (c in def.components) {
                val n = c.name
                if (n.isBlank()) { errors += "component name is required"; continue }
                if (n in earlier) errors += "$n: duplicate component name"
                if (c.appliesTo.isEmpty()) errors += "$n: applicable side(s) must be declared"
                when (val b = c.basis) {
                    is ChargeBasis.RateOfLegValue -> if (b.rate.signum() < 0) errors += "$n: rate must not be negative"
                    is ChargeBasis.FlatPerLeg -> if (b.amount.signum() < 0) errors += "$n: amount must not be negative"
                    is ChargeBasis.RateOfComponent -> {
                        if (b.rate.signum() < 0) errors += "$n: rate must not be negative"
                        val base = earlier[b.component]
                        if (base == null) errors += "$n: base component '${b.component}' must be declared earlier"
                        else {
                            if (!base.appliesTo.containsAll(c.appliesTo)) {
                                errors += "$n: base component '${b.component}' must apply on every side $n applies on"
                            }
                            if (base.rounding != Rounding.None) {
                                errors += "$n: base component '${b.component}' is rounded; whether dependents use the rounded value is undefined"
                            }
                        }
                    }
                }
                (c.minimum as? Bound.Of)?.let { if (it.value.signum() < 0) errors += "$n: minimum must not be negative" }
                (c.cap as? Bound.Of)?.let { if (it.value.signum() < 0) errors += "$n: cap must not be negative" }
                val min = (c.minimum as? Bound.Of)?.value
                val cap = (c.cap as? Bound.Of)?.value
                if (min != null && cap != null && min > cap) errors += "$n: minimum exceeds cap"
                if (c.rounding != Rounding.None && (c.minimum != Bound.None || c.cap != Bound.None)) {
                    errors += "$n: rounding combined with a minimum/cap has an undefined order"
                }
                earlier[n] = c
            }
            if (errors.isNotEmpty()) return ScheduleValidation.Rejected(errors)
            return ScheduleValidation.Valid(
                ValidatedChargeSchedule(
                    def.broker.trim(), def.exchange.trim(), def.segment.trim(),
                    def.effectiveFrom!!, def.source!!, def.components.toList(),
                    def.keyedByOrderType, def.keyedByProduct,
                ),
            )
        }
    }
}
