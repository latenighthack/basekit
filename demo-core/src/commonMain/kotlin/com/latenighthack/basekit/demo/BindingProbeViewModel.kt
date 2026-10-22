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
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedDeltaList
import com.latenighthack.deltalist.mutableDeltaListOf
import com.latenighthack.deltalist.operators.groupBy
import com.latenighthack.deltalist.operators.lazyMapItems
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map

@kotlin.js.JsExport
@kotlin.js.JsName("BrowserProbeMessage")
class ProbeMessage(val text: String)

/** React spells these `none` and `try-again`: the module uses `lowerCamel`, and RETRY overrides it. */
enum class ProbeFailure { NONE, @ReactEnumName("try-again") RETRY }

/** An enum section header. */
enum class ProbeShelf { TOP, BOTTOM }

/** Marker element for a sectioned list with two row types. */
interface BindingProbeRow

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

    /** A sealed section header: an object case and a case that carries a value. */
    sealed interface Section {
        data object Pinned : Section
        data class Group(val name: String) : Section
    }
    @ViewModelList(BindingChildViewModel::class) val sections: SectionedDeltaList<Section, BindingChildViewModel>
    @ViewModelList(BindingChildViewModel::class, BindingNoteViewModel::class) val shelves: Flow<SectionedDelta<ProbeShelf, BindingProbeRow>>
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
interface BindingNoteViewModel : ViewModel<BindingNoteViewModel.State>, BindingProbeRow {
    data class State(val text: String)
    suspend fun dismiss()
}

class RealBindingNoteViewModel(text: String) : BindingNoteViewModel,
    StatefulViewModel<BindingNoteViewModel.State>(BindingNoteViewModel.State(text)) {
    override suspend fun dismiss() {}
}

@ViewModelSpec
interface BindingChildViewModel : ViewModel<BindingChildViewModel.State>, BindingProbeRow {
    data class State(val title: String)
    @ViewModelIdentity val identity: String
    suspend fun select()
}

class RealBindingChildViewModel(override val identity: String) : BindingChildViewModel,
    StatefulViewModel<BindingChildViewModel.State>(BindingChildViewModel.State("Row $identity")) {
    override suspend fun select() = update { copy(title = "Selected $identity") }
}

/** [fail] makes the flows returned by [failing] throw in their current collections only; a later collection is unaffected. */
private class FailureTrigger {
    private val failures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    fun fail(message: String) { failures.tryEmit(IllegalStateException(message)) }
    fun <T> failing(flow: Flow<T>): Flow<T> = merge(flow, failures.map { throw it })
}

class RealBindingProbeViewModel : BindingProbeViewModel,
    StatefulViewModel<BindingProbeViewModel.State>(BindingProbeViewModel.State(ProbeMessage("hello"), null, ProbeFailure.NONE, null, listOf("a"))),
    ViewModelFailureHandler<BindingProbeViewModel.State> {
    var activeStateCollectors: Int = 0
        private set
    private val stateFailures = FailureTrigger()
    override val state: Flow<BindingProbeViewModel.State> = flow {
        activeStateCollectors++
        try { emitAll(stateFailures.failing(super.state)) } finally { activeStateCollectors-- }
    }
    /** The failures the bindings passed to this handler, as `source: message`. */
    val handledFailures: List<String> get() = handled.toList()
    private val handled = mutableListOf<String>()
    /** Makes the current `state` collections throw; the handler shows the error in [BindingProbeViewModel.State.note]. */
    fun failState(message: String) = stateFailures.fail(message)
    /** Makes the current `rows` collections throw. */
    fun failRows(message: String) = rowFailures.fail(message)
    override suspend fun onStateFailed(failure: ViewModelFailure, lastState: BindingProbeViewModel.State): Flow<BindingProbeViewModel.State> {
        handled += "${failure.source}: ${failure.error.message}"
        return flowOf(lastState.copy(note = "failed: ${failure.error.message}"))
    }
    override suspend fun onListFailed(failure: ViewModelFailure) {
        handled += "${failure.source}: ${failure.error.message}"
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
    private val rowFailures = FailureTrigger()
    override val rows: Flow<Delta<BindingChildViewModel>> = flow {
        activeRowCollectors++
        try { emitAll(rowFailures.failing(mutableRows)) } finally { activeRowCollectors-- }
    }
    private val sectionEntries = mutableDeltaListOf(
        listOf(BindingProbeViewModel.Section.Pinned to "pinned", BindingProbeViewModel.Section.Group("more") to "more"),
    )
    var builtSectionRows: Int = 0
        private set
    /** Sections come and go with their entries; each row is built when first shown and kept while shown. */
    override val sections: SectionedDeltaList<BindingProbeViewModel.Section, BindingChildViewModel> = sectionEntries
        .groupBy(keySelector = { it.first })
        .lazyMapItems<BindingProbeViewModel.Section, Pair<BindingProbeViewModel.Section, String>, BindingChildViewModel> { (_, id) ->
            builtSectionRows++
            RealBindingChildViewModel(id)
        }
    override val shelves: Flow<SectionedDelta<ProbeShelf, BindingProbeRow>> = flowOf(
        SectionedDelta(
            listOf(
                Section(ProbeShelf.TOP, listOf<BindingProbeRow>(RealBindingChildViewModel("top"), RealBindingNoteViewModel("note"))),
                Section(ProbeShelf.BOTTOM, emptyList()),
            ),
            SectionedChange.Reload,
        ),
    )
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
    fun insertSectionRow(index: Int, section: BindingProbeViewModel.Section, id: String) = sectionEntries.insert(index, section to id)
    fun removeSectionRow(index: Int) = sectionEntries.removeAt(index)
}

/**
 * A row whose `state` emits once and then throws, like a cold read of a deleted item. It has no
 * failure handler, so the failure goes to [ViewModelFailures.unhandled].
 */
fun failingBindingChildViewModel(message: String): BindingChildViewModel = object : BindingChildViewModel {
    override val identity = "failing"
    override val initialState = BindingChildViewModel.State("initial")
    override val state: Flow<BindingChildViewModel.State> = flow {
        emit(BindingChildViewModel.State("loaded"))
        error(message)
    }
    override suspend fun select() {}
}
