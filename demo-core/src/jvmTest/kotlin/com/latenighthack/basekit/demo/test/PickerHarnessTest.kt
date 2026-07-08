package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerOption
import com.latenighthack.basekit.demo.PickerViewModel
import com.latenighthack.basekit.demo.RealPickerOptionViewModel
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
import kotlin.test.assertTrue

/**
 * End-to-end proof of response navigation: the real [HomeViewModel] suspends on the responding
 * [PickerViewModel] and resumes with the picked value (applied to the shared store), or with null on
 * dismiss. Plus a direct check that a picker option row responds with its id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PickerHarnessTest {
    @Test
    fun picker_responds_and_resumes_the_caller_with_a_value() = runTest {
        val registry = DemoRegistry()
        val navigator = TestClientNavigator(registry)
        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()

        // onPick suspends inside navigateToPicker; run it off the test coroutine so it can suspend.
        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPick() }
        navigator.awaitViewModel<PickerViewModel>()

        val event = navigator.navigationHistory.first().last() as NavigationEvent.NavigatedTo
        @Suppress("UNCHECKED_CAST")
        (event.responder as NavigationResponder<PickResult>).respond(PickResult(1))

        assertTrue(pick.isCompleted, "the caller must have resumed with the value")
        assertTrue(registry.store.feedItems().any { it.id == "option-1" }, "the picked option is added to the feed")
    }

    @Test
    fun closing_a_pending_picker_resumes_the_caller_with_null() = runTest {
        val registry = DemoRegistry()
        val navigator = TestClientNavigator(registry)
        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()
        val before = registry.store.feedItems().size

        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPick() }
        navigator.awaitViewModel<PickerViewModel>()

        navigator.close()

        assertTrue(pick.isCompleted, "the caller must have resumed (with null), not still be suspended")
        assertEquals(before, registry.store.feedItems().size, "a dismissed pick adds nothing to the feed")
    }

    @Test
    fun responding_directly_via_the_recorded_event_resumes_the_caller() = runTest {
        val registry = DemoRegistry()
        val navigator = TestClientNavigator(registry)
        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()

        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPick() }

        val event = navigator.navigationHistory.first().last() as NavigationEvent.NavigatedTo
        assertEquals(PickerViewModel::class, event.destination)
        @Suppress("UNCHECKED_CAST")
        (event.responder as NavigationResponder<PickResult>).respond(PickResult(2))

        assertTrue(pick.isCompleted)
        assertTrue(registry.store.feedItems().any { it.id == "option-2" })
    }

    @Test
    fun a_picker_option_row_responds_with_its_id() = runTest {
        var responded: PickResult? = null
        val row = RealPickerOptionViewModel(PickerOption(3, "Cherries"), NavigationResponder { responded = it })

        row.onSelected()

        assertEquals(PickResult(3), responded)
    }
}
