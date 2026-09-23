from pathlib import Path

base = Path("core/strategy/src/main/kotlin/com/algotrader/strategy")
base.mkdir(parents=True, exist_ok=True)

path = base / "MovingAverageCrossoverStrategy.kt"

path.write_text("""package com.algotrader.strategy

class MovingAverageCrossoverStrategy(
    private val fastPeriod: Int = 5,
    private val slowPeriod: Int = 10
) : Strategy {

    init {
        require(fastPeriod > 0) { "fastPeriod must be greater than zero" }
        require(slowPeriod > fastPeriod) {
            "slowPeriod must be greater than fastPeriod"
        }
    }

    override val name: String = "Moving Average Crossover"

    override fun evaluate(context: StrategyContext): List<Signal> {
        val candles = context.candles.sortedBy { it.timestamp }

        if (candles.size < slowPeriod + 1) {
            return emptyList()
        }

        val previousCandles = candles.dropLast(1)
        val currentCandle = candles.last()

        val previousFast = averageClose(previousCandles.takeLast(fastPeriod))
        val previousSlow = averageClose(previousCandles.takeLast(slowPeriod))

        val currentFast = averageClose(candles.takeLast(fastPeriod))
        val currentSlow = averageClose(candles.takeLast(slowPeriod))

        val signalType = when {
            previousFast <= previousSlow && currentFast > currentSlow ->
                SignalType.BUY

            previousFast >= previousSlow && currentFast < currentSlow ->
                SignalType.SELL

            else ->
                SignalType.HOLD
        }

        return listOf(
            Signal(
                instrument = currentCandle.instrument,
                type = signalType,
                timestamp = currentCandle.timestamp,
                confidence = confidence(currentFast, currentSlow),
                reason = "Fast MA=$currentFast, Slow MA=$currentSlow"
            )
        )
    }

    private fun averageClose(
        candles: List<com.algotrader.domain.Candle>
    ): Double {
        return candles.map { it.close }.average()
    }

    private fun confidence(
        fast: Double,
        slow: Double
    ): Double {
        if (slow == 0.0) return 0.0

        return ((fast - slow) / slow)
            .coerceIn(-1.0, 1.0)
            .let { kotlin.math.abs(it) }
    }
}
""")

print(f"Created: {path}")
