package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.*
import com.latenighthack.basekit.viewmodel.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ObservingViewModelTest {
    @Test fun preservesDelegatedPropertiesExcludesMutatorsAndReportsBeforeThrowing() = runTest {
        val raw = RealBindingProbeViewModel()
        val events = mutableListOf<ViewModelActionEvent>()
        val observer = ViewModelActionObserver { events += it }
        val model = raw.observingActions(observer)
        assertSame(model, model.observingActions(observer))
        assertSame(raw, raw.observingActions(ViewModelActionObserver.NoOp))
        assertSame(raw.state, model.state)
        assertSame(raw.child, model.child)
        assertSame(raw.rows, model.rows)
        assertEquals(raw.identity, model.identity)
        model.setNote("edited")
        model.platformOnly("a", 1)
        assertTrue(events.isEmpty())
        assertFailsWith<IllegalStateException> { model.fail() }
        assertEquals("fail", events.single().actionName)
        assertEquals("com.latenighthack.basekit.demo.BindingProbeViewModel", events.single().viewModelName)
        assertSame(raw, events.single().viewModel)
    }

    @Test fun cancellationAndChildActionsRemainIntact() = runTest {
        val raw = RealBindingProbeViewModel()
        val names = mutableListOf<String>()
        val observer = ViewModelActionObserver { names += it.actionName }
        val model = raw.observingActions(observer)
        val job = launch { model.waitUntilCancelled() }
        testScheduler.runCurrent()
        assertEquals(listOf("waitUntilCancelled"), names)
        assertEquals(1, raw.startedActions)
        job.cancel()
        job.join()
        assertEquals(1, raw.cancelledActions)
        raw.child.observingActions(observer).select()
        assertEquals(listOf("waitUntilCancelled", "select"), names)
    }
}
