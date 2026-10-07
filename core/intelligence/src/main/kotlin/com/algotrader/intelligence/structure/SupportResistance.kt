package com.algotrader.intelligence.structure

enum class ZoneKind { SUPPORT, RESISTANCE }

/**
 * A price band built from clustered pivots of one type (lows -> SUPPORT, highs -> RESISTANCE).
 * [touches] = pivots in the band; [lastIndex] = the latest member pivot's bar index;
 * [lastConfirmedIndex] = the candle at which the zone, in its current form, became knowable (the latest member's
 * confirmation). Do not use the zone before that candle.
 */
data class PriceZone(
    val kind: ZoneKind,
    val low: Double,
    val high: Double,
    val touches: Int,
    val firstIndex: Int,
    val lastIndex: Int,
    val lastConfirmedIndex: Int
) {
    init {
        require(low <= high) { "zone low must be <= high" }
        require(touches >= 1) { "zone needs at least one touch" }
    }
}

internal object SupportResistance {

    /**
     * Anchored greedy clustering of pivot prices (sorted ascending, ties by index): a cluster is anchored on its
     * lowest price and takes every following price within `tolerance * anchor`. No chaining, so the result does
     * not depend on how a long run of slowly rising prices is split by accident. Output order:
     * low ascending, then high, then SUPPORT before RESISTANCE.
     */
    fun zones(pivots: List<SwingPivot>, config: StructureConfig): List<PriceZone> {
        val out = ArrayList<PriceZone>()
        for (type in PivotType.values()) {
            val sorted = pivots.filter { it.type == type }.sortedWith(compareBy({ it.price }, { it.index }))
            var i = 0
            while (i < sorted.size) {
                val anchor = sorted[i].price
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].price - anchor <= config.zoneTolerance * kotlin.math.abs(anchor)) j++
                val members = sorted.subList(i, j + 1)
                if (members.size >= config.minZoneTouches) {
                    out += PriceZone(
                        kind = if (type == PivotType.HIGH) ZoneKind.RESISTANCE else ZoneKind.SUPPORT,
                        low = members.first().price,
                        high = members.last().price,
                        touches = members.size,
                        firstIndex = members.minOf { it.index },
                        lastIndex = members.maxOf { it.index },
                        lastConfirmedIndex = members.maxOf { it.confirmedIndex }
                    )
                }
                i = j + 1
            }
        }
        return out.sortedWith(compareBy({ it.low }, { it.high }, { it.kind }))
    }
}
