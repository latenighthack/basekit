package com.latenighthack.basekit.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Exposes the protected state API so the tests can drive it directly. */
private class CounterViewModel : StatefulViewModel<Int>(0) {
    suspend fun apply(updater: Int.() -> Int) = update(updater)
    suspend fun inspect(inspector: suspend (Int) -> Unit) = withState(inspector)
    suspend fun current(): Int {
        var value = 0
        withState { value = it }
        return value
    }
}

class StatefulViewModelTest {
    @Test
    fun update_applies_and_withState_reads_the_result() = runTest {
        val vm = CounterViewModel()
        vm.apply { this + 5 }
        vm.apply { this + 3 }
        assertEquals(8, vm.current())
        assertEquals(8, vm.state.first()) // late bindings receive current state
        assertEquals(0, vm.initialState) // initialState is the construction fallback
    }

    @Test
    fun initial_state_is_exposed_before_any_update() = runTest {
        val vm = CounterViewModel()
        assertEquals(0, vm.initialState)
        assertEquals(0, vm.current())
    }

    @Test
    fun concurrent_pure_updates_do_not_lose_increments() = runTest {
        val vm = CounterViewModel()
        coroutineScope {
            repeat(8) {
                launch(Dispatchers.Default) {
                    repeat(1_000) { vm.apply { this + 1 } }
                }
            }
        }
        assertEquals(8_000, vm.current())
    }

    @Test
    fun suspending_snapshot_inspection_does_not_prevent_new_updates() = runTest {
        val vm = CounterViewModel()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inspection = launch {
            vm.inspect { captured ->
                entered.complete(Unit)
                release.await()
                assertEquals(0, captured) // a captured snapshot is not a reservation
            }
        }
        entered.await()
        vm.apply { this + 1 }
        assertEquals(1, vm.current())
        release.complete(Unit)
        inspection.join()
    }

    @Test
    fun a_failed_reducer_keeps_the_previous_snapshot() = runTest {
        val vm = CounterViewModel()
        vm.apply { 7 }
        assertFailsWith<IllegalArgumentException> {
            vm.apply { throw IllegalArgumentException("invalid transition") }
        }
        assertEquals(7, vm.state.first())
    }
}
