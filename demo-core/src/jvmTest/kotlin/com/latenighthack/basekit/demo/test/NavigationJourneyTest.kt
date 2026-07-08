package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailScreen
import com.latenighthack.basekit.demo.HomeScreen
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerScreen
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
import kotlin.test.assertNotSame

/**
 * Longer, more realistic journeys driven purely through the generated [TestClientNavigator] and
 * [awaitViewModel] — the API a real ViewModel test uses. Complements the focused DemoHarnessTest /
 * PickerHarnessTest with multi-step flows, args→state flow-through, and multi-client isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationJourneyTest {
    private fun navigator() = TestClientNavigator(DemoRegistry())

    @Test
    fun awaited_view_model_reflects_the_args_it_was_navigated_with() = runTest {
        val navigator = navigator()
        navigator.launch(HomeScreen.Args())
        val home = navigator.awaitViewModel<HomeScreen>()
        assertIs<RealHomeScreen>(home)

        home.onOpenDetail()

        val detail = navigator.awaitViewModel<DetailScreen>()
        assertEquals("detail-1", detail.initialState.id)
        assertEquals("body for detail-1", detail.initialState.body)
    }

    @Test
    fun each_await_instantiates_a_fresh_view_model() = runTest {
        val navigator = navigator()
        navigator.launch(HomeScreen.Args())

        val first = navigator.awaitViewModel<HomeScreen>()
        val second = navigator.awaitViewModel<HomeScreen>()

        // awaitViewModel calls the registry factory on every match, so it never hands back a cached instance.
        assertNotSame(first, second)
    }

    @Test
    fun sequential_navigations_to_the_same_destination_track_each_call_site() = runTest {
        val navigator = navigator()
        navigator.launch(HomeScreen.Args())
        val home = navigator.awaitViewModel<HomeScreen>()

        home.onOpenDetail()
        val fromRow = navigator.awaitViewModel<DetailScreen>()
        assertEquals("detail-1", fromRow.initialState.id)

        home.onOpenDetailFromBanner()
        val fromBanner = navigator.awaitViewModel<DetailScreen>()
        assertEquals("banner", fromBanner.initialState.id)

        val navs = navigator.navigationHistory.first().filterIsInstance<NavigationEvent.NavigatedTo>()
        assertEquals(
            listOf(HomeScreen::class, DetailScreen::class, DetailScreen::class),
            navs.map { it.destination },
        )
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL, navs[1].source)
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL_FROM_BANNER, navs[2].source)
    }

    @Test
    fun two_independent_users_navigate_with_isolated_args_and_history() = runTest {
        val userA = navigator()
        val userB = navigator()

        userA.launch(HomeScreen.Args())
        userB.launch(HomeScreen.Args())
        val homeA = userA.awaitViewModel<HomeScreen>()
        val homeB = userB.awaitViewModel<HomeScreen>()

        homeA.onOpenDetail()
        homeB.onOpenDetailFromBanner()

        val detailA = userA.awaitViewModel<DetailScreen>()
        val detailB = userB.awaitViewModel<DetailScreen>()

        assertEquals("detail-1", detailA.initialState.id)
        assertEquals("banner", detailB.initialState.id)
        assertEquals(2, userA.navigationHistory.first().size)
        assertEquals(2, userB.navigationHistory.first().size)
    }

    @Test
    fun a_journey_can_mix_a_responding_step_with_fire_and_forget_navigation() = runTest {
        val navigator = navigator()
        navigator.launch(HomeScreen.Args())
        val home = navigator.awaitViewModel<HomeScreen>()
        assertIs<RealHomeScreen>(home)

        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPickTapped() }
        val picker = navigator.awaitViewModel<PickerScreen>()
        picker.onItemSelected(5)
        assertEquals(PickResult(5), home.lastPick)

        home.onOpenDetail()
        val detail = navigator.awaitViewModel<DetailScreen>()
        assertEquals("detail-1", detail.initialState.id)

        val navs = navigator.navigationHistory.first().filterIsInstance<NavigationEvent.NavigatedTo>()
        assertEquals(
            listOf(HomeScreen::class, PickerScreen::class, DetailScreen::class),
            navs.map { it.destination },
        )
        pick.cancel()
    }
}
