package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.AppleHomeNavigator
import com.latenighthack.basekit.demo.AppleNavigationEdge
import com.latenighthack.basekit.demo.AppleNavigationHost
import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.NavigationScreen
import com.latenighthack.basekit.demo.ObservingHomeNavigator
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerViewModel
import com.latenighthack.basekit.navigation.NavigationEvent
import com.latenighthack.basekit.navigation.NavigationObserver
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservingNavigatorTest {
    private class Host : AppleNavigationHost {
        var pickerResponder: com.latenighthack.basekit.navigation.NavigationResponder<PickResult>? = null

        override fun close(ownerId: String, context: Any?) {}

        override fun showDetail(
            ownerId: String,
            args: DetailViewModel.Args,
            edge: AppleNavigationEdge,
            context: Any?,
        ) {}

        override fun showPicker(
            ownerId: String,
            args: PickerViewModel.Args,
            edge: AppleNavigationEdge,
            context: Any?,
            responder: com.latenighthack.basekit.navigation.NavigationResponder<PickResult>,
        ) {
            pickerResponder = responder
        }
    }

    /** Collects events, and proves the generated `NavigationScreen` enum drives a compiler-exhaustive `when`. */
    private class Recorder : NavigationObserver {
        val events = mutableListOf<NavigationEvent>()
        val screens = mutableListOf<String>()

        override fun onNavigation(event: NavigationEvent) {
            events += event
            val screen = when (event) {
                is NavigationEvent.NavigatedTo -> event.screen
                is NavigationEvent.Responded -> event.screen
                is NavigationEvent.Closed -> event.screen
            } as NavigationScreen
            screens += when (screen) {
                NavigationScreen.HOME -> "home"
                NavigationScreen.DETAIL -> "detail"
                NavigationScreen.PICKER -> "picker"
            }
        }
    }

    @Test
    fun observes_every_navigation_and_close_before_delegating() {
        val recorder = Recorder()
        val navigator = ObservingHomeNavigator(AppleHomeNavigator("home-instance", Host()), recorder)
        val args = DetailViewModel.Args().apply { id = "42" }

        navigator.navigateToDetail(args, DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL)
        navigator.close()

        val navigatedTo = recorder.events[0] as NavigationEvent.NavigatedTo
        assertEquals(NavigationScreen.DETAIL, navigatedTo.screen)
        assertEquals(DetailViewModel::class, navigatedTo.destination)
        assertEquals(args, navigatedTo.args)
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL, navigatedTo.source)

        val closed = recorder.events[1] as NavigationEvent.Closed
        assertEquals(NavigationScreen.HOME, closed.screen)

        assertEquals(listOf("detail", "home"), recorder.screens)
    }

    @Test
    fun observes_a_responding_navigation_and_its_result() = runTest {
        val recorder = Recorder()
        val host = Host()
        val navigator = ObservingHomeNavigator(AppleHomeNavigator("home-instance", host), recorder)

        val result = async { navigator.navigateToPicker(PickerViewModel.Args()) }
        while (host.pickerResponder == null) testScheduler.runCurrent()
        host.pickerResponder!!.respond(PickResult(9))

        assertEquals(9, result.await()?.selectedId)

        val navigatedTo = recorder.events[0] as NavigationEvent.NavigatedTo
        assertEquals(NavigationScreen.PICKER, navigatedTo.screen)

        val responded = recorder.events[1] as NavigationEvent.Responded
        assertEquals(NavigationScreen.PICKER, responded.screen)
        assertEquals(9, (responded.response as PickResult).selectedId)
    }
}
