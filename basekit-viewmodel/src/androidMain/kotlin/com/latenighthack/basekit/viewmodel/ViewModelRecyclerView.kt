package com.latenighthack.basekit.viewmodel

import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.android.recyclerview.DeltaAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * RecyclerView adapter over a `Flow<Delta<ChildVm>>` where each item is itself a [ViewModel]. The
 * delta mechanics (insert/remove/move/update, stable ids, lazy lists) come from deltalist's
 * [DeltaAdapter]; this layer adds a per-row subscription to each child ViewModel's state.
 */
public class ViewModelDeltaAdapter<T : ViewModel<S>, S>(
    deltaList: DeltaList<T>,
    private val scope: CoroutineScope,
    private val viewFactory: (ViewGroup) -> View,
    private val binder: (View, T) -> Unit,
    private val stateBinder: (View, T, S) -> Unit,
) : DeltaAdapter<T, ViewModelDeltaAdapter.Holder>(deltaList) {

    public class Holder(view: View) : RecyclerView.ViewHolder(view) {
        internal var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(viewFactory(parent))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val vm = getItem(position)
        holder.job?.cancel()
        binder(holder.itemView, vm)
        stateBinder(holder.itemView, vm, vm.initialState)
        holder.job = scope.launch {
            vm.state.collect { state -> stateBinder(holder.itemView, vm, state) }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
    }
}

/**
 * One row type of a polymorphic `@ViewModelList`: which items it claims, the view it inflates, and its
 * binders. Built with [viewModelRow]; the generated `bind{ListProp}` helper takes one per declared
 * `possibleTypes` entry, so adding a row type to the annotation breaks every call site until it is
 * handled — the Android analog of the exhaustive `switch` the Apple generator emits.
 */
public class ViewModelRowSpec<T : ViewModel<S>, S> @PublishedApi internal constructor(
    @PublishedApi internal val matches: (Any?) -> Boolean,
    @PublishedApi internal val viewFactory: (ViewGroup) -> View,
    @PublishedApi internal val binder: (View, T) -> Unit,
    @PublishedApi internal val stateBinder: (View, T, S) -> Unit,
) {
    /**
     * Dispatch entry point. The cast is safe: [matches] is a reified `is T` check and the KSP processor
     * rejects overlapping `possibleTypes`, so at most one spec claims any item.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun bind(view: View, item: Any?, scope: CoroutineScope): Job? {
        val vm = item as T
        binder(view, vm)
        stateBinder(view, vm, vm.initialState)
        return scope.launch {
            vm.state.collect { state -> stateBinder(view, vm, state) }
        }
    }
}

/** Declares the row spec for one child ViewModel type of a polymorphic `@ViewModelList`. */
public inline fun <reified T : ViewModel<S>, S> viewModelRow(
    noinline view: (ViewGroup) -> View,
    noinline bind: (View, T) -> Unit = { _, _ -> },
    noinline bindState: (View, T, S) -> Unit,
): ViewModelRowSpec<T, S> = ViewModelRowSpec({ it is T }, view, bind, bindState)

/**
 * RecyclerView adapter over a heterogeneous `Flow<Delta<E>>` whose items are each one of [specs]' row
 * types. Sibling of [ViewModelDeltaAdapter] for lists that interleave child ViewModel types; delta
 * mechanics still come from deltalist's [DeltaAdapter], and each visible row keeps its own state
 * subscription.
 */
public class MultiTypeViewModelDeltaAdapter<E : Any>(
    deltaList: DeltaList<E>,
    private val scope: CoroutineScope,
    private val specs: List<ViewModelRowSpec<*, *>>,
) : DeltaAdapter<E, MultiTypeViewModelDeltaAdapter.Holder>(deltaList) {

    public class Holder(view: View) : RecyclerView.ViewHolder(view) {
        internal var job: Job? = null
    }

    /**
     * First matching spec wins. A miss is a wiring bug that would otherwise render a blank row, so it
     * fails loudly — matching the Apple generator's `preconditionFailure` and React's `error`.
     */
    private fun specIndexFor(item: Any?): Int {
        val index = specs.indexOfFirst { it.matches(item) }
        require(index >= 0) {
            "No ViewModelRowSpec matches ${item?.let { it::class.simpleName }}; " +
                "declare it in the @ViewModelList possibleTypes"
        }
        return index
    }

    override fun getItemViewType(position: Int): Int = specIndexFor(getItem(position))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(specs[viewType].viewFactory(parent))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.job?.cancel()
        holder.job = specs[specIndexFor(item)].bind(holder.itemView, item, scope)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
    }
}

/**
 * Binds a heterogeneous `Flow<Delta<E>>` to this RecyclerView, collecting against [owner]'s lifecycle.
 * The generated `bind{ListProp}` helpers for polymorphic `@ViewModelList`s call into this.
 *
 * Generic in [E] so no cast is needed at the call site: `Delta<T>` is invariant, so a `DeltaList` of a
 * marker supertype is not a `DeltaList<Any>`.
 */
public fun <E : Any> RecyclerView.bindViewModelRows(
    owner: LifecycleOwner,
    scope: CoroutineScope,
    items: DeltaList<E>,
    specs: List<ViewModelRowSpec<*, *>>,
) {
    val rowAdapter = MultiTypeViewModelDeltaAdapter(items, scope, specs)
    adapter = rowAdapter
    rowAdapter.bind(owner)
}

/**
 * Binds a `Flow<Delta<ChildVm>>` to this RecyclerView, collecting against [owner]'s lifecycle.
 * The generated `bind{ListProp}` helpers call into this.
 */
public fun <T : ViewModel<S>, S> RecyclerView.bindViewModels(
    owner: LifecycleOwner,
    scope: CoroutineScope,
    items: DeltaList<T>,
    viewFactory: (ViewGroup) -> View,
    binder: (View, T) -> Unit = { _, _ -> },
    stateBinder: (View, T, S) -> Unit,
) {
    val vmAdapter = ViewModelDeltaAdapter(items, scope, viewFactory, binder, stateBinder)
    adapter = vmAdapter
    vmAdapter.bind(owner)
}
