package com.algotrader.backtest

import com.algotrader.domain.Candle
import com.algotrader.domain.Portfolio
import com.algotrader.strategy.BollingerBandsStrategy
import com.algotrader.strategy.CprEmaTrendStrategy
import com.algotrader.strategy.DonchianChannelStrategy
import com.algotrader.strategy.DonchianEmaTrendStrategy
import com.algotrader.strategy.MacdStrategy
import com.algotrader.strategy.MovingAverageCrossoverStrategy
import com.algotrader.strategy.RsiStrategy
import com.algotrader.strategy.Signal
import com.algotrader.strategy.SignalType
import com.algotrader.strategy.Strategy
import com.algotrader.strategy.StrategyContext
import com.algotrader.strategy.indicator.bollingerBands
import com.algotrader.strategy.indicator.dailyCentralPivotRange
import com.algotrader.strategy.indicator.donchianChannel
import com.algotrader.strategy.indicator.ema
import com.algotrader.strategy.indicator.macd
import com.algotrader.strategy.indicator.rsi
import com.algotrader.strategy.indicator.sma
import java.time.ZoneOffset

/**
 * Optimized evaluator used by the backtest engine.
 *
 * Built-in strategies precompute their indicator series once over the
 * complete candle set instead of recalculating the entire history on
 * every candle.
 *
 * Unknown strategies fall back to the normal Strategy API.
 */
class BacktestSignalEvaluator(
    private val strategy: Strategy,
    private val candles: List<Candle>
) {

    private val closes = candles.map { it.close }
    private val highs = candles.map { it.high }
    private val lows = candles.map { it.low }

    private interface Evaluator {
        fun signalAt(index: Int): Signal?
    }

    private val evaluator: Evaluator = createEvaluator()

    fun signalAt(index: Int): Signal? {
        return evaluator.signalAt(index)
    }

    private fun parameter(name: String, default: Double): Double {
        return strategy.metadata.parameters
            .firstOrNull { it.name == name }
            ?.value
            ?: default
    }

    private fun intParameter(name: String, default: Int): Int {
        return parameter(name, default.toDouble()).toInt()
    }

    private fun doubleParameter(name: String, default: Double): Double {
        return parameter(name, default)
    }

    private fun signal(
        index: Int,
        type: SignalType,
        confidence: Double,
        reason: String
    ): Signal {
        val candle = candles[index]

        return Signal(
            instrument = candle.instrument,
            type = type,
            timestamp = candle.timestamp,
            confidence = confidence,
            reason = reason
        )
    }

    private fun createEvaluator(): Evaluator {
        return when (strategy) {

            is MovingAverageCrossoverStrategy -> {
                val fastPeriod = intParameter("fastPeriod", 20)
                val slowPeriod = intParameter("slowPeriod", 50)

                val fast = sma(closes, fastPeriod)
                val slow = sma(closes, slowPeriod)

                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        if (index <= 0) return null

                        val currentFast = fast.getOrNull(index) ?: return null
                        val currentSlow = slow.getOrNull(index) ?: return null
                        val previousFast = fast.getOrNull(index - 1) ?: return null
                        val previousSlow = slow.getOrNull(index - 1) ?: return null

                        val type = when {
                            previousFast <= previousSlow &&
                                currentFast > currentSlow -> SignalType.BUY

                            previousFast >= previousSlow &&
                                currentFast < currentSlow -> SignalType.SELL

                            else -> SignalType.HOLD
                        }

                        val confidence = kotlin.math.abs(
                            ((currentFast - currentSlow) / currentSlow)
                                .coerceIn(-1.0, 1.0)
                        )

                        return signal(
                            index = index,
                            type = type,
                            confidence = confidence,
                            reason = "Fast SMA=$currentFast, Slow SMA=$currentSlow"
                        )
                    }
                }
            }

            is MacdStrategy -> {
                val fastPeriod = intParameter("fastPeriod", 12)
                val slowPeriod = intParameter("slowPeriod", 26)
                val signalPeriod = intParameter("signalPeriod", 9)

                val result = macd(
                    closes,
                    fastPeriod = fastPeriod,
                    slowPeriod = slowPeriod,
                    signalPeriod = signalPeriod
                )

                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        if (index <= 0) return null

                        val currentMacd =
                            result.macdLine.getOrNull(index) ?: return null
                        val currentSignal =
                            result.signalLine.getOrNull(index) ?: return null
                        val previousMacd =
                            result.macdLine.getOrNull(index - 1) ?: return null
                        val previousSignal =
                            result.signalLine.getOrNull(index - 1) ?: return null

                        val type = when {
                            previousMacd <= previousSignal &&
                                currentMacd > currentSignal -> SignalType.BUY

                            previousMacd >= previousSignal &&
                                currentMacd < currentSignal -> SignalType.SELL

                            else -> SignalType.HOLD
                        }

                        return signal(
                            index = index,
                            type = type,
                            confidence = 0.5,
                            reason = "MACD=$currentMacd, Signal=$currentSignal"
                        )
                    }
                }
            }

            is RsiStrategy -> {
                val period = intParameter("period", 14)
                val oversold = doubleParameter("oversold", 30.0)
                val overbought = doubleParameter("overbought", 70.0)

                val values = rsi(
                    closes,
                    period = period
                )

                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        val currentRsi = values.getOrNull(index) ?: return null

                        val type = when {
                            currentRsi < oversold -> SignalType.BUY
                            currentRsi > overbought -> SignalType.SELL
                            else -> SignalType.HOLD
                        }

                        val confidence = (
                            kotlin.math.abs(currentRsi - 50.0) / 50.0
                        ).coerceIn(0.0, 1.0)

                        return signal(
                            index = index,
                            type = type,
                            confidence = confidence,
                            reason = "RSI=$currentRsi"
                        )
                    }
                }
            }

            is BollingerBandsStrategy -> {
                val period = intParameter("period", 20)
                val multiplier = doubleParameter("stdDevMultiplier", 2.0)

                val bands = bollingerBands(
                    closes,
                    period = period,
                    stdDevMultiplier = multiplier
                )

                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        val close = closes.getOrNull(index) ?: return null
                        val upper = bands.upper.getOrNull(index) ?: return null
                        val lower = bands.lower.getOrNull(index) ?: return null

                        val type = when {
                            close <= lower -> SignalType.BUY
                            close >= upper -> SignalType.SELL
                            else -> SignalType.HOLD
                        }

                        return signal(
                            index = index,
                            type = type,
                            confidence = 0.5,
                            reason = "Close=$close, Upper=$upper, Lower=$lower"
                        )
                    }
                }
            }

            is DonchianChannelStrategy -> {
                val period = intParameter("period", 20)

                val channel = donchianChannel(
                    highs,
                    lows,
                    period = period
                )

                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        if (index <= 0) return null

                        val close = closes.getOrNull(index) ?: return null
                        val priorUpper =
                            channel.upper.getOrNull(index - 1) ?: return null
                        val priorLower =
                            channel.lower.getOrNull(index - 1) ?: return null

                        val type = when {
                            close > priorUpper -> SignalType.BUY
                            close < priorLower -> SignalType.SELL
                            else -> SignalType.HOLD
                        }

                        return signal(
                            index = index,
                            type = type,
                            confidence = 0.5,
                            reason = "Close=$close, PriorUpper=$priorUpper, PriorLower=$priorLower"
                        )
                    }
                }
            }

            is DonchianEmaTrendStrategy -> {
                val donchianPeriod = intParameter("donchianPeriod", 20)
                val emaPeriod = intParameter("emaPeriod", 50)

                val channel = donchianChannel(
                    highs,
                    lows,
                    period = donchianPeriod
                )
                val emaValues = ema(closes, emaPeriod)

                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        if (index <= 0) return null

                        val close = closes.getOrNull(index) ?: return null
                        val priorUpper =
                            channel.upper.getOrNull(index - 1) ?: return null
                        val priorLower =
                            channel.lower.getOrNull(index - 1) ?: return null
                        val currentEma =
                            emaValues.getOrNull(index) ?: return null

                        val type = when {
                            close > priorUpper && close > currentEma ->
                                SignalType.BUY

                            close < priorLower && close < currentEma ->
                                SignalType.SELL

                            else -> SignalType.HOLD
                        }

                        return signal(
                            index = index,
                            type = type,
                            confidence = 0.5,
                            reason = "Close=$close, PriorUpper=$priorUpper, PriorLower=$priorLower, EMA=$currentEma"
                        )
                    }
                }
            }

            is CprEmaTrendStrategy -> {
                val emaPeriod = intParameter("emaPeriod", 50)

                val emaValues = ema(closes, emaPeriod)
                val cprValues = dailyCentralPivotRange(
                    candles,
                    ZoneOffset.UTC
                )

                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        val close = closes.getOrNull(index) ?: return null
                        val currentEma =
                            emaValues.getOrNull(index) ?: return null
                        val cpr = cprValues.getOrNull(index) ?: return null

                        val type = when {
                            close > currentEma &&
                                close > cpr.topCentral -> SignalType.BUY

                            close < currentEma &&
                                close < cpr.bottomCentral -> SignalType.SELL

                            else -> SignalType.HOLD
                        }

                        return signal(
                            index = index,
                            type = type,
                            confidence = 0.5,
                            reason = "Close=$close, EMA=$currentEma, CPR.TC=${cpr.topCentral}, CPR.BC=${cpr.bottomCentral}"
                        )
                    }
                }
            }

            else -> {
                object : Evaluator {
                    override fun signalAt(index: Int): Signal? {
                        val window = candles.subList(0, index + 1)

                        /*
                         * Preserve the generic Strategy API for future
                         * strategies. The current built-in strategies
                         * don't use Portfolio state.
                         */
                        return strategy.evaluate(
                            StrategyContext(
                                candles = window,
                                portfolio = Portfolio(cash = 0.0)
                            )
                        ).firstOrNull()
                    }
                }
            }
        }
    }
}
