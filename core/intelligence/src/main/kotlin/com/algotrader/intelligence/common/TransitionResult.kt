package com.algotrader.intelligence.common

/**
 * Outcome of asking a state model to change state.
 *
 * Illegal transitions are an expected, business-level outcome, so they are
 * returned as [Rejected] with a reason instead of being thrown. Models are
 * immutable: [Applied] carries the new value and the original is untouched.
 */
sealed interface TransitionResult<out T> {
    data class Applied<out T>(val value: T) : TransitionResult<T>
    data class Rejected(val reason: String) : TransitionResult<Nothing>
}

fun <T> TransitionResult<T>.appliedOrNull(): T? = when (this) {
    is TransitionResult.Applied -> value
    is TransitionResult.Rejected -> null
}

fun <T> TransitionResult<T>.getOrThrow(): T = when (this) {
    is TransitionResult.Applied -> value
    is TransitionResult.Rejected -> throw IllegalStateException(reason)
}
