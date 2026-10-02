package com.latenighthack.basekit.viewmodel.compose

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedViewModelOwnerTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun preparationFailureRetryAndRetention() = runTest(dispatcher) {
        var attempts = 0
        var disposals = 0
        val instance = Any()
        val owner = PreparedViewModelOwner {
            attempts++
            if (attempts == 1) error("offline")
            PreparedViewModel(instance) { disposals++ }
        }
        val store = ViewModelStore().apply { put("root", owner) }
        runCurrent()
        assertIs<Preparation.Failed>(owner.state.value)
        owner.retry(); owner.retry()
        runCurrent()
        assertSame(instance, assertIs<Preparation.Ready<Any>>(owner.state.value).viewModel)
        assertEquals(2, attempts)
        assertSame(owner, store["root"])
        owner.retry(); runCurrent()
        assertEquals(2, attempts)
        store.clear(); store.clear()
        assertEquals(1, disposals)
    }

    @Test fun clearingDuringNonCooperativePreparationDisposesLateResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var disposals = 0
        val owner = PreparedViewModelOwner {
            withContext(NonCancellable) { gate.await() }
            PreparedViewModel(Any()) { disposals++ }
        }
        val store = ViewModelStore().apply { put("root", owner) }
        runCurrent(); store.clear(); gate.complete(Unit); runCurrent()
        assertEquals(1, disposals)
        assertIs<Preparation.Loading>(owner.state.value)
    }

    @Test fun actionErrorsAreDeliveredAndClearingCancelsActions() = runTest(dispatcher) {
        val owner = PreparedViewModelOwner { PreparedViewModel(Any()) {} }
        val store = ViewModelStore().apply { put("root", owner) }
        runCurrent()
        val errors = mutableListOf<Throwable>()
        owner.launch(errors::add) { error("action failed") }; runCurrent()
        assertEquals("action failed", errors.single().message)
        var cancelled = false
        val job = owner.launch(errors::add) { try { awaitCancellation() } finally { cancelled = true } }
        runCurrent(); store.clear(); runCurrent()
        assertTrue(job.isCancelled); assertTrue(cancelled); assertEquals(1, errors.size)
    }
}
