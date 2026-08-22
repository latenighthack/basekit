package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Base [ViewModel] backed by a [MutableStateFlow]. Subclasses evolve state with [update] and read
 * the current snapshot with [withState]; both are suspend so state transitions can await work.
 */
public abstract class StatefulViewModel<State>(initialState: State) : ViewModel<State> {
    override val initialState: State = initialState

    private val internalState = MutableStateFlow(initialState)

    // Serializes [update] so a suspending updater runs exactly once. StateFlow.getAndUpdate is a CAS
    // retry loop and would re-invoke a suspending updater under contention (re-running its side
    // effects), so a mutex — not getAndUpdate — is what makes "runs once" true.
    private val updateMutex = Mutex()

    override val state: Flow<State> get() = internalState

    /**
     * Applies [updater] to the current state and stores the result. Serialized against other
     * [update] calls: the updater body runs exactly once, and concurrent updates observe each
     * other's writes in order.
     */
    protected suspend fun update(updater: suspend State.() -> State) {
        updateMutex.withLock {
            internalState.value = internalState.value.updater()
        }
    }

    /** Reads the current state snapshot without mutating it. */
    protected suspend fun withState(inspector: suspend (State) -> Unit) {
        inspector(internalState.value)
    }
}
