package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * State-driven [ViewModel] backed by a hot, conflated [MutableStateFlow]. Subclasses evolve immutable
 * state with short, pure [update] calls. Perform suspending work in the calling action before
 * applying its result, never inside an updater. This class creates no coroutine scope or jobs;
 * actions run in their caller's coroutine and bindings own collection of [state].
 *
 * Alternatively implement [ViewModel] directly with a composed Flow. Cold upstream work then runs
 * for each binding's collection and is cancelled with that collection.
 */
public abstract class StatefulViewModel<State>(initialState: State) : ViewModel<State> {
    override val initialState: State = initialState

    private val internalState = MutableStateFlow(initialState)

    override val state: Flow<State> get() = internalState

    /**
     * Atomically transforms the current state using StateFlow's update operation. [updater]
     * cannot suspend and may be evaluated more than once under contention: it must be pure and
     * free of I/O, navigation, launches and other side effects. No application lock is required.
     * Equal states do not emit; slow collectors can skip intermediate state values.
     */
    protected suspend fun update(updater: State.() -> State) {
        internalState.update { current -> current.updater() }
    }

    /**
     * Reads the current state without reserving it. If [inspector] suspends, newer updates may
     * arrive in the meantime; use [update] to derive a change from the latest state on completion.
     */
    protected suspend fun withState(inspector: suspend (State) -> Unit) {
        inspector(internalState.value)
    }
}
