package com.latenighthack.basekit.viewmodel

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class NotFound : RuntimeException("404")

/** Emits [emitted], then throws [error]. */
private fun <T> failingAfter(error: Throwable, vararg emitted: T): Flow<T> = flow {
    emitted.forEach { emit(it) }
    throw error
}

private open class FailingViewModel(
    override val state: Flow<String>,
    val rows: Flow<Delta<String>> = emptyFlow(),
) : ViewModel<String> {
    override val initialState: String = "initial"
}

private class HandlingViewModel(
    state: Flow<String>,
    rows: Flow<Delta<String>> = emptyFlow(),
    private val recover: suspend (ViewModelFailure, String) -> Flow<String>,
) : FailingViewModel(state, rows), ViewModelFailureHandler<String> {
    val stateFailures = mutableListOf<Pair<ViewModelFailure, String>>()
    val listFailures = mutableListOf<ViewModelFailure>()

    override suspend fun onStateFailed(failure: ViewModelFailure, lastState: String): Flow<String> {
        stateFailures += failure to lastState
        return recover(failure, lastState)
    }

    override suspend fun onListFailed(failure: ViewModelFailure) {
        listFailures += failure
    }
}

class ViewModelFailuresTest {
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
    fun a_failed_state_calls_the_handler_once_and_continues_with_its_flow() = runTest {
        val error = NotFound()
        val vm = HandlingViewModel(failingAfter(error, "loaded")) { _, last -> flowOf("$last+error") }

        assertEquals(listOf("loaded", "loaded+error"), vm.boundState("Failing").toList())
        val (failure, last) = vm.stateFailures.single()
        assertEquals(ViewModelFailure("Failing", ViewModelFailure.STATE, error), failure)
        assertEquals("loaded", last)
        assertTrue(unhandled.isEmpty())
    }

    @Test
    fun the_handler_receives_the_initial_state_when_nothing_was_emitted() = runTest {
        val vm = HandlingViewModel(failingAfter(NotFound())) { _, _ -> emptyFlow() }

        assertEquals(emptyList(), vm.boundState("Failing").toList())
        assertEquals("initial", vm.stateFailures.single().second)
    }

    @Test
    fun returning_state_retries_and_a_second_failure_calls_the_handler_again() = runTest {
        var attempts = 0
        val state = flow {
            attempts++
            emit("attempt $attempts")
            if (attempts < 3) throw NotFound()
        }
        val vm = HandlingViewModel(state) { _, _ -> state }

        assertEquals(listOf("attempt 1", "attempt 2", "attempt 3"), vm.boundState("Failing").toList())
        assertEquals(listOf("attempt 1", "attempt 2"), vm.stateFailures.map { it.second })
    }

    @Test
    fun a_view_model_without_a_handler_reports_to_the_fallback_and_completes() = runTest {
        val error = NotFound()
        val vm = FailingViewModel(failingAfter(error, "loaded"))

        assertEquals(listOf("loaded"), vm.boundState("Failing").toList())
        assertEquals(listOf(ViewModelFailure("Failing", ViewModelFailure.STATE, error)), unhandled)
    }

    @Test
    fun the_default_handler_reports_to_the_fallback_and_keeps_the_last_state() = runTest {
        val error = NotFound()
        val vm = object : FailingViewModel(failingAfter(error, "loaded")), ViewModelFailureHandler<String> {}

        assertEquals(listOf("loaded"), vm.boundState("Failing").toList())
        assertEquals(listOf(ViewModelFailure("Failing", ViewModelFailure.STATE, error)), unhandled)
    }

    @Test
    fun a_throwing_handler_goes_to_the_fallback() = runTest {
        val original = NotFound()
        val vm = HandlingViewModel(failingAfter(original, "loaded")) { _, _ -> error("handler failed") }

        assertEquals(listOf("loaded"), vm.boundState("Failing").toList())
        val reported = unhandled.single()
        assertEquals("handler failed", reported.error.message)
        assertSame(original, reported.error.suppressedExceptions.single())
    }

    @Test
    fun a_throwing_fallback_is_caught() = runTest {
        ViewModelFailures.unhandled = { error("fallback failed") }
        val vm = FailingViewModel(failingAfter(NotFound(), "loaded"))

        assertEquals(listOf("loaded"), vm.boundState("Failing").toList())
    }

    @Test
    fun cancellation_from_state_is_rethrown() = runTest {
        val vm = HandlingViewModel(failingAfter(CancellationException("stop"), "loaded")) { _, _ -> emptyFlow() }

        assertFailsWith<CancellationException> { vm.boundState("Failing").toList() }
        assertTrue(vm.stateFailures.isEmpty())
        assertTrue(unhandled.isEmpty())
    }

    @Test
    fun a_failure_downstream_of_the_binding_is_not_handled() = runTest {
        val vm = HandlingViewModel(flowOf("loaded")) { _, _ -> emptyFlow() }

        assertFailsWith<NotFound> { vm.boundState("Failing").collect { throw NotFound() } }
        assertTrue(vm.stateFailures.isEmpty())
    }

    @Test
    fun a_failed_list_keeps_its_rows_and_calls_the_handler_with_its_name() = runTest {
        val error = NotFound()
        val rows = Delta(listOf("a", "b"), Change.Reload)
        val vm = HandlingViewModel(emptyFlow(), failingAfter(error, rows)) { _, _ -> emptyFlow() }

        assertEquals(listOf(rows), vm.boundList("Failing", "rows", vm.rows).toList())
        assertEquals(listOf(ViewModelFailure("Failing", "rows", error)), vm.listFailures)
        assertTrue(unhandled.isEmpty())
    }

    @Test
    fun a_failed_list_without_a_handler_reports_to_the_fallback() = runTest {
        val error = NotFound()
        val vm = FailingViewModel(emptyFlow(), failingAfter(error))

        assertEquals(emptyList(), vm.boundList("Failing", "rows", vm.rows).toList())
        assertEquals(listOf(ViewModelFailure("Failing", "rows", error)), unhandled)
    }

    @Test
    fun cancellation_from_a_list_is_rethrown() = runTest {
        val vm = FailingViewModel(emptyFlow(), failingAfter(CancellationException("stop")))

        assertFailsWith<CancellationException> { vm.boundList("Failing", "rows", vm.rows).toList() }
        assertTrue(unhandled.isEmpty())
    }
}
