package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.AppleHomeNavigator
import com.latenighthack.basekit.demo.AppleNavigationEdge
import com.latenighthack.basekit.demo.AppleNavigationHost
import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerViewModel
import com.latenighthack.basekit.navigation.NavigationResponder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AppleHostedNavigatorTest {
    // Generated Apple navigators dispatch host calls to Main. JVM tests have no UI dispatcher.
    @BeforeTest
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class Host : AppleNavigationHost {
        val detailEdges = mutableListOf<AppleNavigationEdge>()
        var closedOwner: String? = null
        var pickerResponder: NavigationResponder<PickResult>? = null

        override fun close(ownerId: String, context: Any?) {
            closedOwner = ownerId
        }

        override fun showDetail(
            ownerId: String,
            args: DetailViewModel.Args,
            edge: AppleNavigationEdge,
            context: Any?,
        ) {
            detailEdges += edge
        }

        override fun showPicker(
            ownerId: String,
            args: PickerViewModel.Args,
            edge: AppleNavigationEdge,
            context: Any?,
            responder: NavigationResponder<PickResult>,
        ) {
            assertEquals(AppleNavigationEdge.HOME_ON_PICK, edge)
            pickerResponder = responder
        }
    }

    @Test
    fun preserves_every_call_site_and_owner_identity() {
        val host = Host()
        val navigator = AppleHomeNavigator("home-instance", host)
        val args = DetailViewModel.Args().apply { id = "42" }

        navigator.navigateToDetail(args, DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL)
        navigator.navigateToDetail(args, DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL_FROM_BANNER)
        navigator.close()

        assertEquals(
            listOf(AppleNavigationEdge.HOME_ON_OPEN_DETAIL, AppleNavigationEdge.HOME_ON_OPEN_DETAIL_FROM_BANNER),
            host.detailEdges,
        )
        assertEquals("home-instance", host.closedOwner)
    }

    @Test
    fun responding_navigation_is_resolved_by_the_host_channel() = runTest {
        val host = Host()
        val navigator = AppleHomeNavigator("home-instance", host)
        val result = async { navigator.navigateToPicker(PickerViewModel.Args()) }

        testScheduler.runCurrent()
        assertNotNull(host.pickerResponder).respond(PickResult(9))

        assertEquals(9, result.await()?.selectedId)
    }

    @Test
    fun responding_navigation_can_be_dismissed() = runTest {
        val host = Host()
        val navigator = AppleHomeNavigator("home-instance", host)
        val result = async { navigator.navigateToPicker(PickerViewModel.Args()) }

        testScheduler.runCurrent()
        assertNotNull(host.pickerResponder).respond(null)

        assertNull(result.await())
    }
}
