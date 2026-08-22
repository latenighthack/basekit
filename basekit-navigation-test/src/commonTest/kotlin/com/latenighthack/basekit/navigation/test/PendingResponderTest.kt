package com.latenighthack.basekit.navigation.test

import com.latenighthack.basekit.navigation.NavigatorArgs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PickerArgs : NavigatorArgs()
private val PICKER: KClass<*> = PickerArgs::class

/**
 * Proves [NavigationRecorder.dismissTopPending] — the mechanism the generated `close()` uses — resolves
 * the correct suspended caller. The bug this guards against: a `close()` keyed on the *last history
 * event* fails to dismiss a responding destination once any later navigation has been recorded, leaving
 * its caller suspended forever (a hung test, the worst failure a harness can have).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingResponderTest {

    @Test
    fun dismiss_resolves_a_picker_that_is_no_longer_the_last_event() = runTest {
        val recorder = NavigationRecorder()
        var result: Int? = -1
        val caller = launch(UnconfinedTestDispatcher(testScheduler)) {
            result = recorder.recordAndAwaitResponse<Int>(PICKER, PickerArgs(), null, null)
        }

        // A later navigation happens, so the picker is no longer the newest history entry.
        recorder.record(NavigationEvent.NavigatedTo(String::class, null, null, null))

        recorder.dismissTopPending()

        assertTrue(caller.isCompleted, "the suspended picker caller must resume")
        assertNull(result, "dismissal resumes it with null")
    }

    @Test
    fun dismiss_is_last_in_first_out_across_nested_pickers() = runTest {
        val recorder = NavigationRecorder()
        var outer: Int? = -1
        var inner: Int? = -1
        val outerCall = launch(UnconfinedTestDispatcher(testScheduler)) {
            outer = recorder.recordAndAwaitResponse<Int>(PICKER, null, null, null)
        }
        val innerCall = launch(UnconfinedTestDispatcher(testScheduler)) {
            inner = recorder.recordAndAwaitResponse<Int>(PICKER, null, null, null)
        }

        recorder.dismissTopPending() // dismisses the inner (most recent) picker
        assertTrue(innerCall.isCompleted)
        assertNull(inner)
        assertFalse(outerCall.isCompleted, "the outer picker is still pending")

        recorder.dismissTopPending()
        assertTrue(outerCall.isCompleted)
        assertNull(outer)
    }

    @Test
    fun a_directly_resolved_picker_is_not_dismissed_again() = runTest {
        val recorder = NavigationRecorder()
        var result: Int? = -1
        val caller = launch(UnconfinedTestDispatcher(testScheduler)) {
            result = recorder.recordAndAwaitResponse<Int>(PICKER, null, null, null)
        }

        val event = recorder.history.value.last() as NavigationEvent.NavigatedTo
        @Suppress("UNCHECKED_CAST")
        (event.responder as com.latenighthack.basekit.navigation.NavigationResponder<Int>).respond(9)
        assertEquals(9, result)

        // Nothing is pending now, so a subsequent close() is a no-op rather than clobbering a value.
        recorder.dismissTopPending()
        assertEquals(9, result)
        assertTrue(caller.isCompleted)
    }

    @Test
    fun dismiss_with_nothing_pending_is_a_noop() = runTest {
        val recorder = NavigationRecorder()
        recorder.dismissTopPending() // must not throw
        assertTrue(recorder.history.value.isEmpty())
    }
}
