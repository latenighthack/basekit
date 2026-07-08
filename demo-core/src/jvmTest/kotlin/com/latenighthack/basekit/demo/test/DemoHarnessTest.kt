package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.RealHomeViewModel
import com.latenighthack.basekit.navigation.test.NavigationEvent
import com.latenighthack.basekit.navigation.test.awaitViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end proof of the generated harness against the real demo ViewModels: launch a root, await it,
 * drive a navigation, await the target, close — all recorded in order with the right source enum.
 */
class DemoHarnessTest {
    @Test
    fun launch_navigate_and_close_are_recorded_in_order() = runTest {
        val navigator = TestClientNavigator(DemoRegistry())

        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()
        assertIs<RealHomeViewModel>(home)

        home.onOpenDetail()
        val detail = navigator.awaitViewModel<DetailViewModel>()
        assertEquals("first", detail.initialState.id)

        navigator.close()

        val history = navigator.navigationHistory.first()
        assertEquals(3, history.size)
        assertEquals(HomeViewModel::class, (history[0] as NavigationEvent.NavigatedTo).destination)
        val toDetail = history[1] as NavigationEvent.NavigatedTo
        assertEquals(DetailViewModel::class, toDetail.destination)
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL, toDetail.source)
        assertTrue(history[2] is NavigationEvent.Closed)
    }

    @Test
    fun banner_call_site_records_its_own_source() = runTest {
        val navigator = TestClientNavigator(DemoRegistry())

        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()
        home.onOpenDetailFromBanner()

        val toDetail = navigator.navigationHistory.first().last() as NavigationEvent.NavigatedTo
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL_FROM_BANNER, toDetail.source)
    }

    // The two-type-param arity keeps old-lib call sites (awaitViewModel<Vm, Vm.Args>()) source-identical.
    @Test
    fun compat_two_type_param_await_resolves() = runTest {
        val navigator = TestClientNavigator(DemoRegistry())
        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel, HomeViewModel.Args>()
        assertIs<RealHomeViewModel>(home)
    }

    @Test
    fun two_navigators_do_not_cross_talk() = runTest {
        val nav1 = TestClientNavigator(DemoRegistry())
        val nav2 = TestClientNavigator(DemoRegistry())

        nav1.launch(HomeViewModel.Args())
        val home1 = nav1.awaitViewModel<HomeViewModel>()
        home1.onOpenDetail()

        nav2.launch(HomeViewModel.Args())

        assertEquals(2, nav1.navigationHistory.first().size)
        val nav2History = nav2.navigationHistory.first()
        assertEquals(1, nav2History.size)
        assertEquals(HomeViewModel::class, (nav2History[0] as NavigationEvent.NavigatedTo).destination)
    }
}
