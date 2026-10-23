package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.*
import com.latenighthack.basekit.viewmodel.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** The generated decorator with a [ViewModelObserver]: actions, mutators, state, children and rows. */
class ObservingViewModelEventsTest {
    private val events = mutableListOf<ViewModelEvent>()
    private val observer = ViewModelObserver { events += it }

    @Test fun reportsActionsAndMutatorsWithTheirOutcomes() = runTest {
        val raw = RealBindingProbeViewModel()
        val model = raw.observing(observer)

        model.setNote("edited")
        assertFailsWith<IllegalStateException> { model.fail() }
        model.platformOnly("a", 1)

        assertEquals(
            listOf("Started MUTATOR setNote", "Completed MUTATOR setNote", "Started ACTION fail", "Failed ACTION fail"),
            events.map { "${it::class.simpleName} ${it.operation.kind} ${it.operation.name}" },
        )
        assertTrue(events.all { it.operation.owner == "com.latenighthack.basekit.demo.BindingProbeViewModel" })
        assertSame(raw, (events.first() as ViewModelEvent.Started).viewModel)
        assertEquals("edited", raw.state.first().note)
    }

    @Test fun installsOnceAndNotWithoutAnObserver() {
        val raw = RealBindingProbeViewModel()
        val model = raw.observing(observer)

        assertSame(raw, raw.observing(null))
        assertSame(model, model.observing(observer))
        assertSame(observer, model.viewModelObserver())
        assertSame(raw.rows, model.rows)
        assertEquals(raw.identity, model.identity)
    }

    @Test fun childrenReportToTheSameObserverAndKeepTheirIdentity() = runTest {
        val raw = RealBindingProbeViewModel()
        val model = raw.observing(observer)

        assertNotSame(raw.child, model.child)
        assertSame(model.child, model.child)
        assertSame(observer, model.child.viewModelObserver())
        model.child.select()

        assertEquals(listOf("BindingChildViewModel.select"), events.filterIsInstance<ViewModelEvent.Started>().map {
            "${it.operation.owner.substringAfterLast('.')}.${it.operation.name}"
        })
    }

    @Test fun stateNamesTheGeneratedPropertiesThatChanged() = runTest {
        val raw = RealBindingProbeViewModel()
        val model = raw.observing(observer)
        val job = launch { model.boundState("probe").collect() }
        testScheduler.runCurrent()

        model.setNote("n")
        testScheduler.runCurrent()
        model.setMessage(ProbeMessage("m"))
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        val states = events.filterIsInstance<ViewModelEvent.StateChanged>()
        assertEquals(listOf(0L, 1L, 2L, 3L), states.map { it.sequence })
        assertEquals(listOf(emptyList(), emptyList(), listOf("note"), listOf("message", "optionalMessage")), states.map { it.changedProperties })
        assertSame(raw.initialState, states.first().state)
        assertEquals(1, events.count { it is ViewModelEvent.Cancelled })
        assertEquals(0, raw.activeStateCollectors)
    }

    @Test fun recoveryIsANewAttemptOverOneUpstreamCollection() = runTest {
        val raw = RealBindingProbeViewModel()
        val model = raw.observing(observer)
        val job = launch { model.boundState("probe").collect() }
        testScheduler.runCurrent()
        assertEquals(1, raw.activeStateCollectors)

        raw.failState("offline")
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        val attempts = events.filterIsInstance<ViewModelEvent.Started>()
        val binding = events.first { it is ViewModelEvent.Attached }.operation
        assertEquals(listOf(1, 2), attempts.map { it.attempt })
        assertEquals(listOf(binding.id, binding.id), attempts.map { it.operation.parentId })
        assertEquals("offline", events.filterIsInstance<ViewModelEvent.Failed>().single().error.message)
        assertEquals(listOf("state: offline"), raw.handledFailures)
    }

    @Test fun aStaticRowCompletesNormally() = runTest {
        val raw = RealBindingProbeViewModel()
        raw.replaceStaticRows("fixed")
        val row = raw.rows.first().items.softGet(0).let { (it as com.latenighthack.deltalist.SoftValue.Present).value }

        row.observing(observer).boundState("row").collect()

        assertEquals(
            listOf("Attached", "StateChanged", "Started", "Completed", "Detached"),
            events.map { it::class.simpleName },
        )
    }

    @Test fun legacyActionObserversKeepTheirBehaviour() = runTest {
        val raw = RealBindingProbeViewModel()
        val actions = mutableListOf<String>()
        val model = raw.observingActions { actions += it.actionName }

        assertSame(raw.child, model.child)
        assertNull(model.viewModelObserver())
        model.setNote("x")
        assertFailsWith<IllegalStateException> { model.fail() }

        assertEquals(listOf("fail"), actions)
    }
}
