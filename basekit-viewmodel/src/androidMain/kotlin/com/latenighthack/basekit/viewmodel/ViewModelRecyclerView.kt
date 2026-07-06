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
