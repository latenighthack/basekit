package com.latenighthack.basekit.navigation.test

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.NavigatorArgs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeArgs : NavigatorArgs()

private interface FakeDestination : NavigationDestination<FakeArgs>
private class FakeDestinationVm(val args: FakeArgs?) : FakeDestination

// Shares FakeArgs with FakeDestination on purpose: proves awaitViewModel keys on the destination class,
// not the args class.
private interface OtherDestination : NavigationDestination<FakeArgs>
private class OtherDestinationVm : OtherDestination

/** A hand-rolled TestNavigatorApi standing in for a generated TestClientNavigator. */
private class FakeNavigator : TestNavigatorApi {
    private val recorder = NavigationRecorder()

    override val navigationHistory get() = recorder.history

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> createDestination(
        destination: KClass<T>,
        args: NavigatorArgs?,
        responder: NavigationResponder<*>?,
    ): T =
        when (destination) {
            FakeDestination::class -> FakeDestinationVm(args as? FakeArgs) as T
            OtherDestination::class -> OtherDestinationVm() as T
            else -> error("unknown destination $destination")
        }

    override fun launch(args: NavigatorArgs, context: Any?) {
        recorder.record(NavigationEvent.NavigatedTo(FakeDestination::class, args, source = null, context = context))
    }

    fun recordNav(destination: KClass<*>, args: NavigatorArgs?, source: Any? = null, context: Any? = null) =
        recorder.record(NavigationEvent.NavigatedTo(destination, args, source, context))

    fun recordClose(context: Any? = null) = recorder.record(NavigationEvent.Closed(context))
}

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRecorderTest {
    @Test
    fun history_is_ordered_and_includes_source_and_close() = runTest {
        val nav = FakeNavigator()
        val args = FakeArgs()
        nav.recordNav(FakeDestination::class, args, source = "HOME_ON_OPEN")
        nav.recordClose(context = "ctx")

        val history = nav.navigationHistory.first()
        assertEquals(2, history.size)
        val first = history[0] as NavigationEvent.NavigatedTo
        assertEquals(FakeDestination::class, first.destination)
        assertSame(args, first.args)
        assertEquals("HOME_ON_OPEN", first.source)
        assertEquals(NavigationEvent.Closed("ctx"), history[1])
    }

    @Test
    fun await_suspends_then_resumes_on_matching_navigation() = runTest {
        val nav = FakeNavigator()
        var result: FakeDestination? = null
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            result = nav.awaitViewModel<FakeDestination>()
        }

        assertNull(result, "should suspend until a matching navigation happens")
        nav.launch(FakeArgs())
        assertNotNull(result, "should resume once the destination is navigated to")
        assertTrue(result is FakeDestinationVm)
        job.cancel()
    }

    @Test
    fun await_replays_a_navigation_that_already_happened() = runTest {
        val nav = FakeNavigator()
        val args = FakeArgs()
        nav.launch(args)

        val vm = nav.awaitViewModel<FakeDestination>()
        assertTrue(vm is FakeDestinationVm)
        assertSame(args, (vm as FakeDestinationVm).args)
    }

    @Test
    fun await_matches_on_destination_class_not_args_class() = runTest {
        val nav = FakeNavigator()
        var result: FakeDestination? = null
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            result = nav.awaitViewModel<FakeDestination>()
        }

        nav.recordNav(OtherDestination::class, FakeArgs())
        assertNull(result, "a different destination sharing the args type must not match")
        nav.recordNav(FakeDestination::class, FakeArgs())
        assertNotNull(result)
        job.cancel()
    }

    @Test
    fun two_navigators_do_not_cross_talk() = runTest {
        val a = FakeNavigator()
        val b = FakeNavigator()

        a.launch(FakeArgs())

        assertEquals(1, a.navigationHistory.first().size)
        assertEquals(0, b.navigationHistory.first().size)
    }
}

private fun NavigationRecorder.pendingResponder(): NavigationResponder<*> =
    (history.value.last() as NavigationEvent.NavigatedTo).responder!!

@OptIn(ExperimentalCoroutinesApi::class)
class ResponseNavigationTest {
    @Test
    fun respond_resumes_the_suspended_caller_with_a_value() = runTest {
        val recorder = NavigationRecorder()
        var result: Int? = -1
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            result = recorder.recordAndAwaitResponse<Int>(FakeDestination::class, null, null, null)
        }

        assertEquals(-1, result, "caller should suspend until a response arrives")
        @Suppress("UNCHECKED_CAST")
        (recorder.pendingResponder() as NavigationResponder<Int>).respond(7)
        assertEquals(7, result)
        job.cancel()
    }

    @Test
    fun responding_null_resumes_the_caller_with_null_dismiss() = runTest {
        val recorder = NavigationRecorder()
        var result: Int? = -1
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            result = recorder.recordAndAwaitResponse<Int>(FakeDestination::class, null, null, null)
        }

        recorder.pendingResponder().respond(null)
        assertNull(result)
        job.cancel()
    }

    @Test
    fun two_recorders_have_independent_pending_responses() = runTest {
        val r1 = NavigationRecorder()
        val r2 = NavigationRecorder()
        var res1: Int? = -1
        var res2: Int? = -1
        val j1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            res1 = r1.recordAndAwaitResponse<Int>(FakeDestination::class, null, null, null)
        }
        val j2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            res2 = r2.recordAndAwaitResponse<Int>(FakeDestination::class, null, null, null)
        }

        @Suppress("UNCHECKED_CAST")
        (r1.pendingResponder() as NavigationResponder<Int>).respond(1)
        assertEquals(1, res1)
        assertEquals(-1, res2, "responding on r1 must not resolve r2's caller")

        @Suppress("UNCHECKED_CAST")
        (r2.pendingResponder() as NavigationResponder<Int>).respond(2)
        assertEquals(2, res2)
        j1.cancel()
        j2.cancel()
    }

    @Test
    fun cancelling_the_caller_drops_the_pending_response() = runTest {
        val recorder = NavigationRecorder()
        var resumed = false
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            recorder.recordAndAwaitResponse<Int>(FakeDestination::class, null, null, null)
            resumed = true
        }

        job.cancelAndJoin()

        @Suppress("UNCHECKED_CAST")
        (recorder.pendingResponder() as NavigationResponder<Int>).respond(9)
        assertEquals(false, resumed, "a response after cancellation must be a no-op")
    }
}
