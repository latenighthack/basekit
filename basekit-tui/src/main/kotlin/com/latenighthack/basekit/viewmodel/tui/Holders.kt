package com.latenighthack.basekit.viewmodel.tui

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.loadedItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects a `Flow<State>` into a volatile [value] that a screen reads synchronously during
 * [TuiScreen.render]. The TamboUI tick loop repaints, so updates appear on the next frame.
 */
public class StateHolder<S>(scope: CoroutineScope, initial: S, flow: Flow<S>) {
    @Volatile
    public var value: S = initial
        private set

    init {
        scope.launch { flow.collect { value = it } }
    }
}

/**
 * Collects a `Flow<Delta<T>>` into a plain snapshot [items] of the loaded child ViewModels for a
 * `@ViewModelList`. Only the loaded prefix is materialized (soft/paginated placeholders are skipped).
 */
public class ListHolder<T>(scope: CoroutineScope, flow: Flow<Delta<T>>) {
    @Volatile
    public var items: List<T> = emptyList()
        private set

    init {
        scope.launch { flow.collect { items = it.loadedItems() } }
    }
}
