package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerViewModel
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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

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
        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()
        assertIs<com.latenighthack.basekit.demo.RealHomeViewModel>(home)

        home.onOpenDetail()

        val detail = navigator.awaitViewModel<DetailViewModel>()
        assertEquals("first", detail.initialState.id)
        assertEquals("Body for First", detail.initialState.body)
    }

    @Test
    fun each_await_instantiates_a_fresh_view_model() = runTest {
        val navigator = navigator()
        navigator.launch(HomeViewModel.Args())

        val first = navigator.awaitViewModel<HomeViewModel>()
        val second = navigator.awaitViewModel<HomeViewModel>()

        // awaitViewModel calls the registry factory on every match, so it never hands back a cached instance.
        assertNotSame(first, second)
    }

    @Test
    fun sequential_navigations_to_the_same_destination_track_each_call_site() = runTest {
        val navigator = navigator()
        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()

        home.onOpenDetail()
        val fromRow = navigator.awaitViewModel<DetailViewModel>()
        assertEquals("first", fromRow.initialState.id)

        home.onOpenDetailFromBanner()
        val fromBanner = navigator.awaitViewModel<DetailViewModel>()
        assertEquals("second", fromBanner.initialState.id)

        val navs = navigator.navigationHistory.first().filterIsInstance<NavigationEvent.NavigatedTo>()
        assertEquals(
            listOf(HomeViewModel::class, DetailViewModel::class, DetailViewModel::class),
            navs.map { it.destination },
        )
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL, navs[1].source)
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL_FROM_BANNER, navs[2].source)
    }

    @Test
    fun two_independent_users_navigate_with_isolated_args_and_history() = runTest {
        val userA = navigator()
        val userB = navigator()

        userA.launch(HomeViewModel.Args())
        userB.launch(HomeViewModel.Args())
        val homeA = userA.awaitViewModel<HomeViewModel>()
        val homeB = userB.awaitViewModel<HomeViewModel>()

        homeA.onOpenDetail()
        homeB.onOpenDetailFromBanner()

        val detailA = userA.awaitViewModel<DetailViewModel>()
        val detailB = userB.awaitViewModel<DetailViewModel>()

        assertEquals("first", detailA.initialState.id)
        assertEquals("second", detailB.initialState.id)
        assertEquals(2, userA.navigationHistory.first().size)
        assertEquals(2, userB.navigationHistory.first().size)
    }

    @Test
    fun a_journey_can_mix_a_responding_step_with_fire_and_forget_navigation() = runTest {
        val registry = DemoRegistry()
        val navigator = TestClientNavigator(registry)
        navigator.launch(HomeViewModel.Args())
        val home = navigator.awaitViewModel<HomeViewModel>()

        val pick = launch(UnconfinedTestDispatcher(testScheduler)) { home.onPick() }
        navigator.awaitViewModel<PickerViewModel>()
        val event = navigator.navigationHistory.first().last() as NavigationEvent.NavigatedTo
        @Suppress("UNCHECKED_CAST")
        (event.responder as NavigationResponder<PickResult>).respond(PickResult(2))
        assertTrue(pick.isCompleted)
        assertTrue(registry.store.feedItems().any { it.id == "option-2" })

        home.onOpenDetail()
        val detail = navigator.awaitViewModel<DetailViewModel>()
        assertEquals("first", detail.initialState.id)

        val navs = navigator.navigationHistory.first().filterIsInstance<NavigationEvent.NavigatedTo>()
        assertEquals(
            listOf(HomeViewModel::class, PickerViewModel::class, DetailViewModel::class),
            navs.map { it.destination },
        )
    }
}
