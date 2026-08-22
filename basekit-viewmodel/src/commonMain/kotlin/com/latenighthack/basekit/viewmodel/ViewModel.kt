package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.flow.Flow

/**
 * The core contract every ViewModel implements. A ViewModel owns an immutable [State] snapshot
 * ([initialState]) and a [state] stream of subsequent snapshots. Platform bindings (generated per
 * `@ViewModelSpec`) subscribe to [state] and map it onto native views.
 */
public interface ViewModel<State> {
    /** The state a binding renders synchronously before the first [state] emission arrives. */
    public val initialState: State

    /**
     * The stream of state snapshots. Backed by a hot, conflated `MutableStateFlow`: every collector
     * replays the latest value on subscribe, and intermediate states emitted faster than a collector
     * consumes them may be conflated away. Multiple bindings can collect the same instance safely
     * (e.g. an iOS `Kvo{Vm}` and a SwiftUI `Observable{Vm}` over one ViewModel).
     */
    public val state: Flow<State>
}
