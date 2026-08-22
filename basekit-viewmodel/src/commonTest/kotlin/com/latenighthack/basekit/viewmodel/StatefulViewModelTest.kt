package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exposes the protected [update]/[withState] surface so the tests can drive it directly. */
private class CounterViewModel : StatefulViewModel<Int>(0) {
    suspend fun apply(updater: suspend Int.() -> Int) = update(updater)
    suspend fun current(): Int {
        var value = 0
        withState { value = it }
        return value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatefulViewModelTest {

    @Test
    fun update_applies_and_withState_reads_the_result() = runTest {
        val vm = CounterViewModel()
        vm.apply { this + 5 }
        vm.apply { this + 3 }
        assertEquals(8, vm.current())
    }

    @Test
    fun initial_state_is_exposed_before_any_update() = runTest {
        val vm = CounterViewModel()
        assertEquals(0, vm.initialState)
        assertEquals(0, vm.current())
    }

    // A second update must not enter while the first is still running its (suspending) body. This is
    // deterministic even single-threaded: it asserts mutual exclusion via suspension ordering, not a
    // thread race. It fails on the old getAndUpdate implementation (no lock; the second body runs
    // immediately and a CAS race can drop an increment), and passes on the Mutex-serialized version.
    @Test
    fun update_serializes_a_suspending_body_so_it_runs_exactly_once() = runTest {
        val vm = CounterViewModel()
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.apply {
                firstEntered.complete(Unit)
                release.await() // hold the update lock until the test releases it
                this + 1
            }
        }
        firstEntered.await()

        var secondEntered = false
        val second = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.apply { secondEntered = true; this + 1 }
        }

        assertFalse(secondEntered, "the second update must wait for the first to release the lock")

        release.complete(Unit)
        first.join()
        second.join()

        assertTrue(secondEntered, "the second update runs once the first completes")
        assertEquals(2, vm.current(), "each update body applied exactly once, in order")
    }
}
