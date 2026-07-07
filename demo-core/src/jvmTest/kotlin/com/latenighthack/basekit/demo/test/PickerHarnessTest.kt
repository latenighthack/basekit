package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.HomeScreen
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerScreen
import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.test.NavigationEvent
import com.latenighthack.basekit.navigation.test.awaitViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * End-to-end proof of response navigation: a caller (HomeScreen) suspends on a responding destination
 * (PickerScreen) and resumes with the picked value, a null on dismiss/close, or a directly-injected response.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PickerHarnessTest {
    @Test
    fun picker_responds_and_resumes_the_caller_with_a_value() = runTest {
        val navigator = TestClientNavigator(DemoRegistry())
        navigator.launch(HomeScreen.Args())
        val home = navigator.awaitViewModel<HomeScreen>()
        assertIs<RealHomeScreen>(home)

        // onPickTapped suspends inside navigateToPicker; run it off the test coroutine so it can suspend.
        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPickTapped() }
        val picker = navigator.awaitViewModel<PickerScreen>()
        assertIs<RealPickerScreen>(picker)

        picker.onItemSelected(3)

        assertEquals(PickResult(3), home.lastPick)
        assertEquals(1, home.pickCount)
        pick.cancel()
    }

    @Test
    fun closing_a_pending_picker_resumes_the_caller_with_null() = runTest {
        val navigator = TestClientNavigator(DemoRegistry())
        navigator.launch(HomeScreen.Args())
        val home = navigator.awaitViewModel<HomeScreen>()
        assertIs<RealHomeScreen>(home)

        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPickTapped() }
        navigator.awaitViewModel<PickerScreen>()

        navigator.close()

        assertNull(home.lastPick)
        assertEquals(1, home.pickCount, "the caller must actually have resumed (with null), not still be suspended")
        pick.cancel()
    }

    @Test
    fun responding_directly_via_the_recorded_event_resumes_the_caller() = runTest {
        val navigator = TestClientNavigator(DemoRegistry())
        navigator.launch(HomeScreen.Args())
        val home = navigator.awaitViewModel<HomeScreen>()
        assertIs<RealHomeScreen>(home)

        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPickTapped() }

        val event = navigator.navigationHistory.first().last() as NavigationEvent.NavigatedTo
        assertEquals(PickerScreen::class, event.destination)
        @Suppress("UNCHECKED_CAST")
        (event.responder as NavigationResponder<PickResult>).respond(PickResult(9))

        assertEquals(PickResult(9), home.lastPick)
        pick.cancel()
    }
}
