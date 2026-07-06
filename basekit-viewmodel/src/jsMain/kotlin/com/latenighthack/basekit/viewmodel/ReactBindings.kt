package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Minimal React interop used by the generated `use{ViewModel}` hooks. */
@JsModule("react")
@JsNonModule
public external object React {
    public fun useState(initial: dynamic): Array<dynamic>
    public fun useEffect(effect: () -> dynamic, deps: Array<dynamic>)
}

/**
 * Collects [flow], invoking [onEach] for every emission, until the returned cleanup runs. Designed to
 * be returned from a React `useEffect` so the subscription is torn down on unmount / dependency
 * change. The `active` flag gates a stale emission a cooperatively-cancelled collector may still
 * deliver (mirrors deltalist-react's collector).
 */
public fun <T> bindFlow(flow: Flow<T>, onEach: (T) -> Unit): () -> Unit {
    var active = true
    val scope = CoroutineScope(SupervisorJob())
    scope.launch {
        flow.collect { value -> if (active) onEach(value) }
    }
    return {
        active = false
        scope.cancel()
    }
}
