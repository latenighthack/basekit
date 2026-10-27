package com.latenighthack.basekit.viewmodel

import com.latenighthack.deltalist.react.ReactFlowStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** Minimal React interop used by the generated `use{ViewModel}` hooks and navigation stack. */
@JsModule("react")
@JsNonModule
public external object React {
    public fun useMemo(factory: () -> dynamic, deps: Array<dynamic>): dynamic
    public fun useState(initial: dynamic): Array<dynamic>
    public fun useEffect(effect: () -> dynamic, deps: Array<dynamic>)
    public fun useSyncExternalStore(subscribe: dynamic, getSnapshot: dynamic): dynamic
    public fun createElement(type: dynamic, props: dynamic, vararg children: dynamic): dynamic
    public val Fragment: dynamic
}

/**
 * The state store, actions and binding object that a generated React hook shares across every
 * component bound to one view model instance.
 *
 * [state] collects [ViewModel.boundState] as a [ReactFlowStore]: when `state` emits on
 * subscription, the first render shows that state; otherwise it shows [ViewModel.initialState].
 * One collection serves every mounted component. While none is mounted the collection stops after
 * the store's grace period, and in-flight actions are cancelled with it.
 */
public class ReactViewModelBinding<S>(viewModel: ViewModel<S>, viewModelName: String) {
    private var actions: CoroutineScope? = null
    private var inputs: Array<dynamic>? = null
    private var result: dynamic = null

    public val state: ReactFlowStore<S> = ReactFlowStore(
        initial = viewModel.initialState,
        source = viewModel.boundState(viewModelName),
        onStop = {
            actions?.cancel()
            actions = null
        },
    )

    /** Runs [action] until it completes or the state collection stops. */
    public fun run(action: suspend () -> Unit): Promise<Unit> {
        val scope = actions ?: CoroutineScope(SupervisorJob()).also { actions = it }
        return scope.promise { action() }
    }

    /**
     * The last object [build] returned while every one of [inputs] is the same (`===`) as on that
     * call; otherwise a new one from [build].
     */
    public fun result(inputs: Array<dynamic>, build: () -> dynamic): dynamic {
        val previous = this.inputs
        if (previous != null && previous.size == inputs.size && inputs.indices.all { previous[it] === inputs[it] }) {
            return result
        }
        this.inputs = inputs
        result = build()
        return result
    }
}

/** One generated binding per view model instance, for as long as the instance is reachable. */
public class ReactBindingCache<VM : Any, B : Any>(private val create: (VM) -> B) {
    private val bindings: dynamic = js("new WeakMap()")

    public fun get(viewModel: VM): B {
        val existing = bindings.get(viewModel)
        if (existing != undefined) return existing.unsafeCast<B>()
        val created = create(viewModel)
        bindings.set(viewModel, created)
        return created
    }
}

/** [convert] applied to the last value, reused while the value is the same (`===`). */
public class ReactConversion<T>(private val convert: (T) -> dynamic) {
    private var converted = false
    private var source: T? = null
    private var value: dynamic = null

    public operator fun invoke(next: T): dynamic {
        if (converted && source === next) return value
        converted = true
        source = next
        value = convert(next)
        return value
    }
}
