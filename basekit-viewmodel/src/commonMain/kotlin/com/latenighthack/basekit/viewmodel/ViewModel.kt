package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.flow.Flow

/**
 * The coroutine/Flow contract every ViewModel implements: an immutable [initialState] and a
 * [state] stream of immutable presentation values. Use [StatefulViewModel] for explicit update calls,
 * or implement this interface directly with a composed Flow. Neither path requires a ViewModel-owned
 * coroutine scope. Platform bindings subscribe to [state] and own their collection's lifetime.
 * Observable collections belong in separate DeltaList properties, mapped to child ViewModels;
 * do not copy lists into [State] or manage filtered list snapshots alongside those streams.
 * Each specification declares its own nested `State` class inside the interface, referenced as
 * `ExampleViewModel.State`. A destination's `Args` class is likewise nested in its spec interface.
 */
public interface ViewModel<State> {
    /** The state a binding renders synchronously before the first [state] emission arrives. */
    public val initialState: State

    /**
     * Current presentation state, not an event queue. This contract does not require a hot StateFlow:
     * a cold `flow`, `map` or `combine` pipeline is valid. Cold work starts per collector, inherits
     * its coroutine context (subject to explicit upstream context operators), and is cancelled when
     * that collection ends. Keep per-collector bookkeeping inside the flow. A binding must be able
     * to render on recollection using [initialState] and the current state stream; a constant child
     * can expose its fixed [initialState] with `emptyFlow()` for subsequent changes.
     *
     * [StatefulViewModel] supplies hot, replaying, equality-conflated state. Direct implementations
     * document their replay/sharing behavior. Collecting a hot flow does not own its producer;
     * explicit `stateIn`/`shareIn` producers belong to the scope passed to those operators.
     */
    public val state: Flow<State>
}
