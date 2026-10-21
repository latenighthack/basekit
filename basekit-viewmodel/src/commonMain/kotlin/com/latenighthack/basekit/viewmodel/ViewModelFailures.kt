package com.latenighthack.basekit.viewmodel

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/** A throw that escaped a view model's [ViewModel.state] or one of its lists while a binding collected it. */
public data class ViewModelFailure(
    /** The spec's qualified name, as the generated bindings spell it. */
    val viewModelName: String,
    /** [STATE], or the name of the `@ViewModelList` property that failed. */
    val source: String,
    val error: Throwable,
) {
    public companion object {
        /** [source] of a failure in [ViewModel.state]. */
        public const val STATE: String = "state"
    }
}

/**
 * A view model that handles its own failed state or list collection. Implement it on the
 * implementation class, not on the `@ViewModelSpec` interface. Without it, failures go to
 * [ViewModelFailures.unhandled].
 */
public interface ViewModelFailureHandler<State> {
    /**
     * [failure] escaped [ViewModel.state] while a binding collected it. [lastState] is the last state
     * the binding received, or [ViewModel.initialState] if none arrived. This runs in the binding's
     * collecting coroutine, so it may suspend, navigate or show a message.
     *
     * The binding continues with the returned flow:
     * - `emptyFlow()` keeps [lastState] on screen. This is the default, which also reports [failure]
     *   to [ViewModelFailures.unhandled].
     * - `flowOf(lastState.copy(...))` shows an error state, such as a message or a popup.
     * - [ViewModel.state] retries. A failure in the returned flow calls this method again, so delay
     *   or wait for a retry signal first; returning `state` at once retries without pause.
     *
     * Back out of the screen by calling the navigator, then returning `emptyFlow()`. A throw from this
     * method goes to [ViewModelFailures.unhandled] and the binding keeps [lastState].
     */
    public suspend fun onStateFailed(failure: ViewModelFailure, lastState: State): Flow<State> {
        ViewModelFailures.report(failure)
        return emptyFlow()
    }

    /**
     * [failure] escaped the list named by [ViewModelFailure.source]. The binding keeps the rows it
     * has. The default reports [failure] to [ViewModelFailures.unhandled]. A throw from this method
     * also goes there.
     */
    public suspend fun onListFailed(failure: ViewModelFailure) {
        ViewModelFailures.report(failure)
    }
}

/** Where failures go when a view model does not implement [ViewModelFailureHandler]. */
public object ViewModelFailures {
    /**
     * Receives each unhandled failure. Install it at app startup for logging and crash reporting.
     * The default prints the failure. A throw from this function is caught and printed, never
     * rethrown: on Kotlin/Native an uncaught throw aborts the process.
     */
    @Volatile
    public var unhandled: (ViewModelFailure) -> Unit = { failure -> print(failure) }

    /** Calls [unhandled] with [failure]. */
    public fun report(failure: ViewModelFailure) {
        try {
            unhandled(failure)
        } catch (error: Throwable) {
            print(failure)
            println("BaseKit: ViewModelFailures.unhandled threw: ${error.stackTraceToString()}")
        }
    }

    private fun print(failure: ViewModelFailure) {
        println("BaseKit: ${failure.viewModelName}.${failure.source} failed: ${failure.error.stackTraceToString()}")
    }
}

/**
 * [ViewModel.state] as the generated bindings collect it, on every platform. It never throws except
 * for cancellation. When `state` throws, the error goes to [ViewModelFailureHandler.onStateFailed]
 * if this view model implements it, and this flow continues with the flow that returns; otherwise
 * the error goes to [ViewModelFailures.unhandled] and this flow completes. Nothing is reset: the
 * binding keeps the last state it rendered. [viewModelName] identifies the view model in the
 * reported [ViewModelFailure].
 */
public fun <S> ViewModel<S>.boundState(viewModelName: String): Flow<S> {
    val viewModel = this
    return flow {
        var lastState = viewModel.initialState
        var next: Flow<S>? = viewModel.state
        while (true) {
            val source = next ?: break
            next = null
            source
                .catch { error ->
                    if (error is CancellationException) throw error
                    next = viewModel.recoverState(ViewModelFailure(viewModelName, ViewModelFailure.STATE, error), lastState)
                }
                .collect { value ->
                    lastState = value
                    emit(value)
                }
        }
    }
}

/**
 * [list], one of this view model's `@ViewModelList` flows, as the generated bindings collect it. It
 * never throws except for cancellation. When [list] throws, the error goes to
 * [ViewModelFailureHandler.onListFailed] if this view model implements it, otherwise to
 * [ViewModelFailures.unhandled], and this flow completes. The binding keeps the rows it has.
 * [listName] is the property's name.
 */
public fun <T> ViewModel<*>.boundList(viewModelName: String, listName: String, list: Flow<T>): Flow<T> {
    val viewModel = this
    return list.catch { error ->
        if (error is CancellationException) throw error
        val failure = ViewModelFailure(viewModelName, listName, error)
        val handler = viewModel as? ViewModelFailureHandler<*>
        if (handler == null) {
            ViewModelFailures.report(failure)
        } else {
            handlerCall(failure) { handler.onListFailed(failure) }
        }
    }
}

private suspend fun <S> ViewModel<S>.recoverState(failure: ViewModelFailure, lastState: S): Flow<S>? {
    @Suppress("UNCHECKED_CAST")
    val handler = this as? ViewModelFailureHandler<S>
    if (handler == null) {
        ViewModelFailures.report(failure)
        return null
    }
    return handlerCall(failure) { handler.onStateFailed(failure, lastState) }
}

/** Runs a handler; a throw from it, other than cancellation, goes to [ViewModelFailures.unhandled]. */
private inline fun <R> handlerCall(failure: ViewModelFailure, call: () -> R): R? =
    try {
        call()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        error.addSuppressed(failure.error)
        ViewModelFailures.report(failure.copy(error = error))
        null
    }
