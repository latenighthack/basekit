@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.latenighthack.basekit.demo

import com.latenighthack.basekit.viewmodel.*
import com.latenighthack.basekit.viewmodel.annotations.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Change

@kotlin.js.JsExport
@kotlin.js.JsName("BrowserProbeMessage")
class ProbeMessage(val text: String)

enum class ProbeFailure { NONE, RETRY }

/** Compiled on every target and consumed directly by the native and TS acceptance fixtures. */
@ViewModelSpec
interface BindingProbeViewModel : ViewModel<BindingProbeViewModel.State> {
    data class State(
        val message: ProbeMessage,
        val optionalMessage: ProbeMessage?,
        val failure: ProbeFailure,
        val note: String?,
        val tags: List<String>,
    )
    @ViewModelIdentity val identity: String
    @ChildViewModel val child: BindingChildViewModel
    @ViewModelList(BindingChildViewModel::class) val rows: Flow<Delta<BindingChildViewModel>>
    suspend fun waitUntilCancelled()
    suspend fun setNote(value: String?)
    suspend fun setMessage(value: ProbeMessage)
    suspend fun setFailure(value: ProbeFailure)
    suspend fun setTags(value: List<String>)
    @Throws(Exception::class)
    suspend fun fail()
    @CodegenIgnore suspend fun platformOnly(a: String, b: Int)
}

@ViewModelSpec
interface BindingChildViewModel : ViewModel<BindingChildViewModel.State> {
    data class State(val title: String)
    @ViewModelIdentity val identity: String
    suspend fun select()
}

class RealBindingChildViewModel(override val identity: String) : BindingChildViewModel,
    StatefulViewModel<BindingChildViewModel.State>(BindingChildViewModel.State("Row $identity")) {
    override suspend fun select() = update { copy(title = "Selected $identity") }
}

class RealBindingProbeViewModel : BindingProbeViewModel,
    StatefulViewModel<BindingProbeViewModel.State>(BindingProbeViewModel.State(ProbeMessage("hello"), null, ProbeFailure.NONE, null, listOf("a"))) {
    var activeStateCollectors: Int = 0
        private set
    override val state: Flow<BindingProbeViewModel.State> = flow {
        activeStateCollectors++
        try { emitAll(super.state) } finally { activeStateCollectors-- }
    }
    var startedActions: Int = 0
        private set
    var cancelledActions: Int = 0
        private set
    override suspend fun waitUntilCancelled() {
        startedActions++
        try { awaitCancellation() } finally { cancelledActions++ }
    }
    override val identity = "probe"
    override val child = RealBindingChildViewModel("child")
    private val mutableRows = MutableStateFlow(Delta<BindingChildViewModel>(emptyList(), Change.Reload))
    var activeRowCollectors: Int = 0
        private set
    override val rows: Flow<Delta<BindingChildViewModel>> = flow {
        activeRowCollectors++
        try { emitAll(mutableRows) } finally { activeRowCollectors-- }
    }
    override suspend fun setNote(value: String?) = update { copy(note = value) }
    override suspend fun setMessage(value: ProbeMessage) = update { copy(message = value, optionalMessage = value) }
    override suspend fun setFailure(value: ProbeFailure) = update { copy(failure = value) }
    override suspend fun setTags(value: List<String>) = update { copy(tags = value) }
    override suspend fun fail() { error("probe failure") }
    override suspend fun platformOnly(a: String, b: Int) {}
    fun replaceStaticRows(title: String) {
        val child = object : BindingChildViewModel {
            override val identity = "static"
            override val initialState = BindingChildViewModel.State(title)
            override val state = kotlinx.coroutines.flow.emptyFlow<BindingChildViewModel.State>()
            override suspend fun select() {}
        }
        mutableRows.value = Delta(listOf(child), Change.Reload)
    }
    fun clearRows() { mutableRows.value = Delta(emptyList(), Change.Reload) }
    fun replaceRows() { mutableRows.value = Delta(listOf(RealBindingChildViewModel("child")), Change.Reload) }
}
