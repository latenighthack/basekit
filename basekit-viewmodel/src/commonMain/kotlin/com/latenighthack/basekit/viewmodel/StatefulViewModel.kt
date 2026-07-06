package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Base [ViewModel] backed by a [MutableStateFlow]. Subclasses evolve state with [update] and read
 * the current snapshot with [withState]; both are suspend so state transitions can await work.
 */
public abstract class StatefulViewModel<State>(initialState: State) : ViewModel<State> {
    override val initialState: State = initialState

    private val internalState = MutableStateFlow(initialState)

    override val state: Flow<State> get() = internalState

    /** Atomically replaces the current state with the result of [updater]. */
    protected suspend fun update(updater: suspend State.() -> State) {
        internalState.getAndUpdate { it.updater() }
    }

    /** Reads the current state snapshot without mutating it. */
    protected suspend fun withState(inspector: suspend (State) -> Unit) {
        inspector(internalState.value)
    }
}
