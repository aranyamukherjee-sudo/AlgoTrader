package com.algotrader.discovery.reproducibility

import com.algotrader.intelligence.dna.Condition
import com.algotrader.intelligence.dna.FeatureRef
import com.algotrader.intelligence.dna.ParamValue
import com.algotrader.intelligence.dna.StrategyDna

/**
 * Stable text form of what a strategy DOES (market, timeframe, rules, risk).
 * It deliberately ignores id, name, description, version, evidence and
 * lineage, so identical definitions always get the identical key. Candidate
 * ids are hashes of this key, which makes discovery runs reproducible.
 */
object DnaCanonical {

    fun key(dna: StrategyDna): String = listOf(
        "signal=" + dna.market.signalSource.instrument.symbol,
        "trade=" + (dna.market.tradeTarget?.instrument?.symbol ?: "none"),
        "tf=" + dna.timeframe,
        "sides=" + dna.sides.map { it.name }.sorted().joinToString(","),
        "ctx=" + condition(dna.requiredContext),
        "entry=" + condition(dna.entry),
        "confirm=" + condition(dna.confirmation),
        "exit=" + condition(dna.exit),
        "invalid=" + condition(dna.invalidation),
        "risk=" + dna.risk
    ).joinToString("|")

    private fun condition(c: Condition?): String = when (c) {
        null -> "-"
        is Condition.Leaf -> feature(c.feature)
        is Condition.AllOf -> "ALL(" + c.conditions.joinToString(",") { condition(it) } + ")"
        is Condition.AnyOf -> "ANY(" + c.conditions.joinToString(",") { condition(it) } + ")"
    }

    private fun feature(f: FeatureRef): String {
        val params = f.params.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> "$k=" + value(v) }
        return "${f.domain}:${f.type}{$params}"
    }

    private fun value(v: ParamValue): String = when (v) {
        is ParamValue.Num -> v.value.toString()
        is ParamValue.Text -> "'" + v.value + "'"
        is ParamValue.Flag -> v.value.toString()
    }
}
