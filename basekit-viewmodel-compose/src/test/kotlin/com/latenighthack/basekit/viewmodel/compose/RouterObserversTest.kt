package com.latenighthack.basekit.viewmodel.compose

import android.os.Looper
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import com.latenighthack.basekit.demo.AppleDetailNavigator
import com.latenighthack.basekit.demo.AppleNavigationEdge
import com.latenighthack.basekit.demo.BasekitDestinationModel
import com.latenighthack.basekit.demo.BasekitDestinationView
import com.latenighthack.basekit.demo.BasekitNavigationDecision
import com.latenighthack.basekit.demo.BasekitNavigationRouter
import com.latenighthack.basekit.demo.BasekitNavigationStackHost
import com.latenighthack.basekit.demo.BasekitPresentation
import com.latenighthack.basekit.demo.BasekitRoute
import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.GeneratedViewModelFactories
import com.latenighthack.basekit.demo.HomeNavigator
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.InMemoryDemoStore
import com.latenighthack.basekit.demo.NavigationScreen
import com.latenighthack.basekit.demo.ObservingDetailNavigator
import com.latenighthack.basekit.demo.ObservingHomeViewModel
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerViewModel
import com.latenighthack.basekit.demo.RealDetailViewModel
import com.latenighthack.basekit.demo.RealHomeViewModel
import com.latenighthack.basekit.demo.RealPickerViewModel
import com.latenighthack.basekit.demo.basekitViewModel
import com.latenighthack.basekit.navigation.NavigationEvent
import com.latenighthack.basekit.navigation.NavigationObserver
import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.viewmodel.ViewModelActionEvent
import com.latenighthack.basekit.viewmodel.ViewModelActionObserver
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The generated Compose router reports through its observers, once per screen, as the Swift one does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class RouterObserversTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<NavigationEvent>()
    private val actions = mutableListOf<ViewModelActionEvent>()
    private val navigationObserver = NavigationObserver { events += it }
    private val actionObserver = ViewModelActionObserver { actions += it }

    private class Factories : GeneratedViewModelFactories {
        private val store = InMemoryDemoStore()
        override val detailFactory: (DetailViewModel.Args) -> RealDetailViewModel = { RealDetailViewModel(store, it) }
        override val homeFactory: (HomeNavigator) -> RealHomeViewModel = { RealHomeViewModel(store, it) }
        override val pickerFactory: (NavigationResponder<PickResult>) -> RealPickerViewModel = { RealPickerViewModel(store, it) }
    }

    private fun observedRouter() = BasekitNavigationRouter(navigationObserver = navigationObserver, actionObserver = actionObserver)
    private fun home() = BasekitRoute.Home(HomeViewModel.Args(), edge = null)
    private fun detail() = DetailViewModel.Args().apply { id = "7" }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun screens() = events.map { event ->
        when (event) {
            is NavigationEvent.NavigatedTo -> "to ${event.screen}"
            is NavigationEvent.Closed -> "closed ${event.screen}"
            is NavigationEvent.Responded -> "responded ${event.screen}"
        }
    }

    @Test fun routersMadeForEntriesReportToTheParentsObservers() {
        val router = BasekitNavigationRouter(
            { BasekitNavigationDecision(BasekitPresentation.Sheet) },
            navigationObserver = navigationObserver,
            actionObserver = actionObserver,
        )
        router.showPicker("home", PickerViewModel.Args(), AppleNavigationEdge.HOME_ON_PICK, null) {}
        val child = router.host(router.sheet!!)
        assertSame(navigationObserver, child.navigationObserver)
        assertSame(actionObserver, child.actionObserver)
        val explicit = BasekitNavigationRouter(child)
        assertSame(navigationObserver, explicit.navigationObserver)
        assertSame(actionObserver, explicit.actionObserver)
        // Without observers, nothing is wrapped: the factory's model is used as it is.
        val plain = BasekitNavigationRouter()
        assertSame(NavigationObserver.NoOp, plain.navigationObserver)
        val model = basekitViewModel(plain.root(home()), plain, Factories()) as BasekitDestinationModel.Home
        assertIs<RealHomeViewModel>(model.model)
    }

    @Test fun eachScreenIsReportedOnceByWhoeverShowedOrClosedIt() {
        val router = observedRouter()
        val root = router.root(home())
        // The root is the host's: the router reports it the first time it shows.
        router.didShow(root)
        router.didShow(root)
        val rootArgs = (root.route as BasekitRoute.Home).args
        assertEquals(listOf<NavigationEvent>(NavigationEvent.NavigatedTo(NavigationScreen.HOME, HomeViewModel::class, rootArgs, null, null)), events)

        // A navigator built by basekitViewModel reports its own navigation; the router doesn't repeat it.
        val home = basekitViewModel(root, router, Factories()) as BasekitDestinationModel.Home
        assertIs<ObservingHomeViewModel>(home.model)
        runBlocking { home.model.onOpenDetail() }
        idle()
        val pushed = router.path.single()
        router.didShow(pushed)
        assertEquals(listOf("to HOME", "to DETAIL"), screens())
        assertEquals(DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL, (events[1] as NavigationEvent.NavigatedTo).source)
        assertEquals(listOf("onOpenDetail"), actions.map { it.actionName })

        // System back is the host's: the router reports the screen it removes.
        router.reconcilePath(emptyList())
        assertEquals(listOf("to HOME", "to DETAIL", "closed DETAIL"), screens())

        // A navigator's close is reported by its decorator only.
        router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null)
        val again = router.path.single()
        ObservingDetailNavigator(AppleDetailNavigator(again.ownerId, router), router.navigationObserver).close(null)
        idle()
        assertTrue(router.path.isEmpty())
        assertEquals(listOf("to HOME", "to DETAIL", "closed DETAIL", "closed DETAIL"), screens())
    }

    @Test fun deepLinksAndDismissedSheetsAreReported() {
        val router = BasekitNavigationRouter(
            { request -> if (request.edge == AppleNavigationEdge.HOME_ON_PICK) BasekitNavigationDecision(BasekitPresentation.Sheet) else BasekitNavigationDecision() },
            navigationObserver = navigationObserver,
            actionObserver = actionObserver,
        )
        assertTrue(router.open(detail()))
        router.didShow(router.path.single())
        val opened = events.single() as NavigationEvent.NavigatedTo
        assertEquals(NavigationScreen.DETAIL, opened.screen)
        assertEquals(DetailViewModel::class, opened.destination)
        assertEquals("7", (opened.args as DetailViewModel.Args).id)

        router.showPicker("home", PickerViewModel.Args(), AppleNavigationEdge.HOME_ON_PICK, null) {}
        router.updateSheet(null)
        router.updateSheet(null)
        assertEquals(listOf("to DETAIL", "closed PICKER"), screens())
    }

    @Test fun theStackHostAndDestinationViewReportTheRootOnce() {
        val router = observedRouter()
        val root = router.root(home())
        val factories = Factories()
        compose.setContent {
            val scope = rememberCoroutineScope()
            BasekitNavigationStackHost(
                router = router,
                rootEntry = root,
                root = { entry, host -> BasekitDestinationView(entry, host, factories, { scope.launch { it() } }) { BasicText("root") } },
                destination = { _, _ -> BasicText("destination") },
            )
        }
        compose.waitForIdle()
        assertEquals(listOf("to HOME"), screens())
        compose.runOnIdle { router.showDetail("home", detail(), AppleNavigationEdge.HOME_ON_OPEN_DETAIL, null) }
        compose.waitForIdle()
        // Shown by a navigator, so its decorator reported it.
        assertEquals(listOf("to HOME"), screens())
        compose.runOnIdle { router.reconcilePath(emptyList()) }
        compose.waitForIdle()
        assertEquals(listOf("to HOME", "closed DETAIL"), screens())
    }
}
