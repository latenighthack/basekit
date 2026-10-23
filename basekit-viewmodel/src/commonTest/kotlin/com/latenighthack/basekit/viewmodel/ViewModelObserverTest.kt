package com.latenighthack.basekit.viewmodel

import com.latenighthack.deltalist.AbstractSoftList
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LoadDirection
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Page
import com.latenighthack.deltalist.PagingEvent
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionMutation
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.paginatedDeltaList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class Recorder : ViewModelObserver {
    val events = mutableListOf<ViewModelEvent>()
    override fun onEvent(event: ViewModelEvent) {
        events += event
    }

    /** Each event as `Kind name detail`, durations left out. */
    val lines: List<String> get() = events.map { it.line() }
}

private fun ViewModelEvent.line(): String = when (this) {
    is ViewModelEvent.Started -> "Started ${operation.kind} ${operation.name}" + if (attempt > 0) " #$attempt" else ""
    is ViewModelEvent.Completed -> "Completed ${operation.kind} ${operation.name}"
    is ViewModelEvent.Failed -> "Failed ${operation.kind} ${operation.name} ${error.message}"
    is ViewModelEvent.Cancelled -> "Cancelled ${operation.kind} ${operation.name}"
    is ViewModelEvent.Attached -> "Attached ${operation.kind} ${operation.name}"
    is ViewModelEvent.Detached -> "Detached ${operation.kind} ${operation.name}"
    is ViewModelEvent.FirstEmission -> "FirstEmission ${operation.name}"
    is ViewModelEvent.StateChanged -> "State $sequence $changedProperties $state"
    is ViewModelEvent.ListDelta -> "Delta $sequence $change $size" + (failure?.let { " failed ${it.message}" } ?: "")
    is ViewModelEvent.SectionedListDelta -> "Sections $sequence $change $sectionCount" + (failure?.let { " failed ${it.message}" } ?: "")
    is ViewModelEvent.ListPaging -> "Paging ${operation.kind} ${operation.name} ${event::class.simpleName}"
}

/** Stands in for a generated `Observing{Spec}` decorator. */
private class ObservedStringViewModel(
    override val state: Flow<String>,
    observer: ViewModelObserver,
    private val recover: (suspend (ViewModelFailure, String) -> Flow<String>)? = null,
) : ViewModel<String>, ObservedViewModel<String>, ViewModelFailureHandler<String> {
    override val initialState: String = "initial"
    override val basekitObserver: ViewModelObserver = observer
    val listFailures = mutableListOf<String>()

    override fun basekitChangedProperties(previous: String, next: String): List<String> =
        if (previous != next) listOf("value") else emptyList()

    override suspend fun onStateFailed(failure: ViewModelFailure, lastState: String): Flow<String> =
        recover?.invoke(failure, lastState) ?: super.onStateFailed(failure, lastState)

    override suspend fun onListFailed(failure: ViewModelFailure) {
        listFailures += "${failure.source}: ${failure.error.message}"
    }
}

/** A list whose items fail the test when read: observation reports changes and sizes only. */
private class UnreadableList(override val size: Int) : AbstractSoftList<String>() {
    override fun softGet(index: Int): SoftValue<String>? = error("an item was read")
}

class ViewModelObserverTest {
    private val unhandled = mutableListOf<ViewModelFailure>()
    private lateinit var previous: (ViewModelFailure) -> Unit

    @BeforeTest
    fun installFallback() {
        previous = ViewModelFailures.unhandled
        ViewModelFailures.unhandled = { unhandled += it }
    }

    @AfterTest
    fun restoreFallback() {
        ViewModelFailures.unhandled = previous
    }

    @Test
    fun stateBindingReportsItsLifecycleAndChangedProperties() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(flowOf("initial", "a", "a"), recorder)

        assertEquals(listOf("initial", "a", "a"), model.boundState("Spec").toList())

        assertEquals(
            listOf(
                "Attached STATE_BINDING state",
                "State 0 [] initial",
                "Started ATTEMPT state #1",
                "FirstEmission state",
                "State 1 [] initial",
                "State 2 [value] a",
                "State 3 [] a",
                "Completed ATTEMPT state",
                "Detached STATE_BINDING state",
            ),
            recorder.lines,
        )
        val binding = recorder.events.first().operation
        val attempt = recorder.events[2].operation
        assertEquals("Spec", binding.owner)
        assertEquals(binding.id, attempt.parentId)
        assertNotEquals(binding.id, attempt.id)
    }

    @Test
    fun staticEmptyFlowCompletesNormally() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(emptyFlow(), recorder)

        assertEquals(emptyList(), model.boundState("Spec").toList())

        assertEquals(
            listOf(
                "Attached STATE_BINDING state",
                "State 0 [] initial",
                "Started ATTEMPT state #1",
                "Completed ATTEMPT state",
                "Detached STATE_BINDING state",
            ),
            recorder.lines,
        )
    }

    @Test
    fun recoveryStartsANewAttemptUnderTheSameBinding() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(
            flow { emit("a"); error("offline") },
            recorder,
            recover = { _, last -> flowOf("$last!") },
        )

        assertEquals(listOf("a", "a!"), model.boundState("Spec").toList())

        assertEquals(
            listOf(
                "Attached STATE_BINDING state",
                "State 0 [] initial",
                "Started ATTEMPT state #1",
                "FirstEmission state",
                "State 1 [value] a",
                "Failed ATTEMPT state offline",
                "Started ATTEMPT state #2",
                "FirstEmission state",
                "State 2 [value] a!",
                "Completed ATTEMPT state",
                "Detached STATE_BINDING state",
            ),
            recorder.lines,
        )
        val binding = recorder.events.first().operation
        val attempts = recorder.events.filterIsInstance<ViewModelEvent.Started>().map { it.operation }
        assertEquals(listOf(binding.id, binding.id), attempts.map { it.parentId })
        assertNotEquals(attempts[0].id, attempts[1].id)
    }

    @Test
    fun cancellationStaysCancellation() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(MutableStateFlow("a"), recorder)

        val job = launch { model.boundState("Spec").collect() }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            listOf(
                "Attached STATE_BINDING state",
                "State 0 [] initial",
                "Started ATTEMPT state #1",
                "FirstEmission state",
                "State 1 [value] a",
                "Cancelled ATTEMPT state",
                "Detached STATE_BINDING state",
            ),
            recorder.lines,
        )
        assertTrue(unhandled.isEmpty())
    }

    @Test
    fun aThrowFromThePlatformCollectorFailsTheAttemptAndPropagates() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(flowOf("a"), recorder)

        val error = assertFailsWith<IllegalStateException> { model.boundState("Spec").collect { error("render") } }

        assertEquals("render", error.message)
        assertEquals("Failed ATTEMPT state render", recorder.lines[recorder.lines.size - 2])
        assertEquals("Detached STATE_BINDING state", recorder.lines.last())
    }

    @Test
    fun onePlatformCollectionCollectsUpstreamOnce() = runTest {
        var collections = 0
        val model = ObservedStringViewModel(flow { collections++; emit("a") }, Recorder())

        model.boundState("Spec").toList()

        assertEquals(1, collections)
    }

    @Test
    fun observerFailuresAreDiscarded() = runTest {
        val throwing = ViewModelObserver { error("observer broke") }
        val model = ObservedStringViewModel(flowOf("a"), throwing)

        assertEquals(listOf("a"), model.boundState("Spec").toList())
        assertEquals(listOf<Any>(), model.boundList("Spec", "rows", flowOf<Delta<String>>()).toList())
        assertEquals(7, throwing.observeCall("Spec", "go", ViewModelOperationKind.ACTION, model) { 7 })
        val cancelling = ViewModelObserver { throw CancellationException("observer cancelled") }
        assertEquals(8, cancelling.observeCall("Spec", "go", ViewModelOperationKind.ACTION, model) { 8 })
        assertTrue(unhandled.isEmpty())
    }

    @Test
    fun actionsAndMutatorsReportEachOutcome() = runTest {
        val recorder = Recorder()
        val target = Any()

        recorder.observeCall("Spec", "save", ViewModelOperationKind.ACTION, target) { }
        recorder.observeCall("Spec", "setName", ViewModelOperationKind.MUTATOR, target) { }
        // The same throw; on the JVM, coroutine stack-trace recovery may rethrow a copy.
        assertEquals("denied", assertFailsWith<IllegalStateException> {
            recorder.observeCall("Spec", "save", ViewModelOperationKind.ACTION, target) { throw IllegalStateException("denied") }
        }.message)
        val job = launch { recorder.observeCall("Spec", "wait", ViewModelOperationKind.ACTION, target) { awaitCancellation() } }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertEquals(
            listOf(
                "Started ACTION save", "Completed ACTION save",
                "Started MUTATOR setName", "Completed MUTATOR setName",
                "Started ACTION save", "Failed ACTION save denied",
                "Started ACTION wait", "Cancelled ACTION wait",
            ),
            recorder.lines,
        )
        assertTrue(job.isCancelled)
        val started = recorder.events.filterIsInstance<ViewModelEvent.Started>()
        assertEquals(started.size, started.map { it.operation.id }.toSet().size)
        assertTrue(started.all { it.viewModel === target && it.operation.parentId == null })
    }

    @Test
    fun namedWorkIsReportedUnderTheRunningOperation() = runTest {
        val recorder = Recorder()

        assertEquals(1, observedWork("outside") { 1 })
        recorder.observeCall("Spec", "refresh", ViewModelOperationKind.ACTION, Any()) {
            observedWork("load") { observedWork("parse") { } }
        }

        assertEquals(
            listOf(
                "Started ACTION refresh",
                "Started WORK load",
                "Started WORK parse",
                "Completed WORK parse",
                "Completed WORK load",
                "Completed ACTION refresh",
            ),
            recorder.lines,
        )
        val (refresh, load, parse) = recorder.events.filterIsInstance<ViewModelEvent.Started>().map { it.operation }
        assertEquals(refresh.id, load.parentId)
        assertEquals(load.id, parse.parentId)
        assertEquals("Spec", parse.owner)
    }

    @Test
    fun actionOnlyObserversSeeActionsBeforeTheyRunAndNoBindings() = runTest {
        val actions = mutableListOf<String>()
        val adapted = actionObserverAsViewModelObserver { actions += "${it.viewModelName}.${it.actionName}" }
        val model = ObservedStringViewModel(flowOf("a"), adapted)

        assertNull(model.viewModelObserver())
        assertEquals(listOf("a"), model.boundState("Spec").toList())
        adapted.observeCall("Spec", "save", ViewModelOperationKind.ACTION, model) { assertEquals(listOf("Spec.save"), actions) }
        adapted.observeCall("Spec", "setName", ViewModelOperationKind.MUTATOR, model) { }

        assertEquals(listOf("Spec.save"), actions)
    }

    @Test
    fun combinedObserversReportToBoth() {
        val recorder = Recorder()
        val actions = mutableListOf<String>()
        val legacy = ViewModelActionObserver { actions += it.actionName }

        assertNull(ViewModelObservers.combined(null, ViewModelActionObserver.NoOp))
        assertSame(recorder, ViewModelObservers.combined(recorder, ViewModelActionObserver.NoOp))
        val combined = ViewModelObservers.combined(recorder, legacy)!!
        val operation = ViewModelOperation(1, null, "Spec", "save", ViewModelOperationKind.ACTION)
        combined.onEvent(ViewModelEvent.Started(operation, Any()))

        assertEquals(listOf("Started ACTION save"), recorder.lines)
        assertEquals(listOf("save"), actions)
    }

    @Test
    fun flatListDeltasReportChangesAndSizesWithoutReadingItems() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(emptyFlow(), recorder)
        val deltas = flowOf(
            Delta(UnreadableList(2), Change.Reload),
            Delta(UnreadableList(3), Change.Mutations(Mutation.Insert(1), Mutation.Move(0, 2))),
        )

        model.boundList("Spec", "rows", deltas).toList()

        assertEquals(
            listOf(
                "Attached LIST_BINDING rows",
                "Started ATTEMPT rows #1",
                "FirstEmission rows",
                "Delta 1 Reload 2",
                "Delta 2 Mutations(operations=[Insert(index=1, count=1), Move(fromIndex=0, toIndex=2, count=1)]) 3",
                "Completed ATTEMPT rows",
                "Detached LIST_BINDING rows",
            ),
            recorder.lines,
        )
    }

    @Test
    fun sectionedListDeltasReportSectionChanges() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(emptyFlow(), recorder)
        val deltas = flowOf(
            SectionedDelta(listOf(Section("a", listOf("x"))), SectionedChange.Reload),
            SectionedDelta(listOf(Section("a", listOf("x")), Section("b", emptyList())), SectionedChange.Sections(SectionMutation.Insert(1))),
        )

        model.boundList("Spec", "sections", deltas).toList()

        assertEquals(
            listOf("Sections 1 Reload 1", "Sections 2 Sections(mutations=[Insert(index=1, count=1)]) 2"),
            recorder.lines.filter { it.startsWith("Sections") },
        )
    }

    @Test
    fun aListApplyFailureIsReportedWithTheDelta() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(emptyFlow(), recorder)

        assertFailsWith<IllegalStateException> {
            model.boundList("Spec", "rows", flowOf(Delta(listOf("a"), Change.Reload))).collect { error("apply") }
        }

        assertEquals(
            listOf(
                "Attached LIST_BINDING rows",
                "Started ATTEMPT rows #1",
                "FirstEmission rows",
                "Delta 1 Reload 1 failed apply",
                "Failed ATTEMPT rows apply",
                "Detached LIST_BINDING rows",
            ),
            recorder.lines,
        )
    }

    @Test
    fun aListFailureGoesToTheHandlerAfterItIsReported() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(emptyFlow(), recorder)

        model.boundList("Spec", "rows", flow<Delta<String>> { error("gone") }).toList()

        assertEquals(listOf("rows: gone"), model.listFailures)
        assertEquals(
            listOf("Attached LIST_BINDING rows", "Started ATTEMPT rows #1", "Failed ATTEMPT rows gone", "Detached LIST_BINDING rows"),
            recorder.lines,
        )
    }

    @Test
    fun aCancelledListReportsCancelled() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(emptyFlow(), recorder)

        val job = launch { model.boundList("Spec", "rows", MutableStateFlow(Delta(listOf("a"), Change.Reload))).collect() }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertEquals("Cancelled ATTEMPT rows", recorder.lines[recorder.lines.size - 2])
        assertEquals("Detached LIST_BINDING rows", recorder.lines.last())
    }

    @Test
    fun pagingIsReportedUnderTheListBinding() = runTest {
        val recorder = Recorder()
        val model = ObservedStringViewModel(emptyFlow(), recorder)
        val pages = paginatedDeltaList<String, Int>(backgroundScope, startToken = 0) { _, _ -> Page(listOf("a", "b"), null, null) }

        model.boundList("Spec", "rows", pages).first { it.items.size == 2 && it.change == Change.Reload && it.items.softGet(0) is SoftValue.Present }

        val paging = recorder.events.filterIsInstance<ViewModelEvent.ListPaging>()
        assertEquals(
            listOf(
                "Paging LIST_BINDING rows Requested",
                "Paging LIST_BINDING rows Started",
                "Paging LIST_BINDING rows Completed",
            ),
            paging.map { it.line() },
        )
        assertEquals(LoadDirection.INITIAL, (paging.first().event as PagingEvent.Requested).direction)
        assertSame(recorder.events.first().operation, paging.first().operation)
    }

    @Test
    fun anUnobservedViewModelIsNotReported() = runTest {
        val plain = object : ViewModel<String> {
            override val initialState = "initial"
            override val state = flowOf("a")
        }

        assertNull(plain.viewModelObserver())
        assertEquals(listOf("a"), plain.boundState("Spec").toList())
    }
}
