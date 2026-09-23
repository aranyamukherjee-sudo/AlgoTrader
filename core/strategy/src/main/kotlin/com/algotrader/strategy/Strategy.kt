package com.algotrader.strategy

interface Strategy {

    val name: String

    fun evaluate(context: StrategyContext): List<Signal>
}
