package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.flow.Flow

/**
 * The core contract every ViewModel implements. A ViewModel owns an immutable [State] snapshot
 * ([initialState]) and a [state] stream of subsequent snapshots. Platform bindings (generated per
 * `@ViewModel`) subscribe to [state] and map it onto native views.
 */
public interface ViewModel<State> {
    /** The state a binding renders synchronously before the first [state] emission arrives. */
    public val initialState: State

    /** The stream of state snapshots. Cold; each binding collects its own subscription. */
    public val state: Flow<State>
}
