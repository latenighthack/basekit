@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Browser-only: what the React bindings render first, for hot and delayed sources. */
@ViewModelSpec
interface RenderProbeViewModel : ViewModel<RenderProbeViewModel.State> {
    data class State(val greeting: String, val count: Int)
    @ViewModelList(BindingChildViewModel::class) val rows: Flow<Delta<BindingChildViewModel>>
    suspend fun increment()
}

/** State is a `combine` of two `StateFlow`s; with [delayMillis] above zero every source waits first. */
class RealRenderProbeViewModel(private val delayMillis: Int) : RenderProbeViewModel {
    private val greeting = MutableStateFlow("hot")
    private val count = MutableStateFlow(0)
    private val mutableRows = MutableStateFlow(Delta<BindingChildViewModel>(listOf(RealBindingChildViewModel("one"), RealBindingChildViewModel("two")), Change.Reload))
    var startedStateCollectors: Int = 0
        private set
    var activeStateCollectors: Int = 0
        private set
    var startedRowCollectors: Int = 0
        private set
    var activeRowCollectors: Int = 0
        private set

    override val initialState = RenderProbeViewModel.State("initial", -1)
    override val state: Flow<RenderProbeViewModel.State> = flow {
        startedStateCollectors++; activeStateCollectors++
        try {
            if (delayMillis > 0) delay(delayMillis.toLong())
            emitAll(combine(greeting, count, RenderProbeViewModel::State))
        } finally { activeStateCollectors-- }
    }
    override val rows: Flow<Delta<BindingChildViewModel>> = flow {
        startedRowCollectors++; activeRowCollectors++
        try {
            if (delayMillis > 0) delay(delayMillis.toLong())
            emitAll(mutableRows)
        } finally { activeRowCollectors-- }
    }
    override suspend fun increment() { count.value++ }
}

/** One probe per scenario, each with its own collection counters. */
@kotlin.js.JsExport
class BrowserRenderProbe(delayMillis: Int) {
    private val model = RealRenderProbeViewModel(delayMillis)
    val reference = model.reactReference()
    val startedStateCollectors: Int get() = model.startedStateCollectors
    val activeStateCollectors: Int get() = model.activeStateCollectors
    val startedRowCollectors: Int get() = model.startedRowCollectors
    val activeRowCollectors: Int get() = model.activeRowCollectors
}
