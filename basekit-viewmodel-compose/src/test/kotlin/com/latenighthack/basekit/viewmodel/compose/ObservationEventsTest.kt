package com.latenighthack.basekit.viewmodel.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.latenighthack.basekit.demo.*
import com.latenighthack.basekit.navigation.NavigationEvent
import com.latenighthack.basekit.navigation.NavigationObserver
import com.latenighthack.basekit.navigation.NavigationPresentationEvent
import com.latenighthack.basekit.navigation.NavigationPresentationObserver
import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.viewmodel.ViewModelEvent
import com.latenighthack.basekit.viewmodel.ViewModelObserver
import com.latenighthack.basekit.viewmodel.viewModelObserver
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A [ViewModelObserver] reaches the generated Compose bindings, their rows, and the router's destinations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class ObservationEventsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<ViewModelEvent>()
    private val observer = ViewModelObserver { events += it }

    private fun bindings(kind: String) = events.filter { it::class.simpleName == kind }
        .map { "${it.operation.owner.substringAfterLast('.')}.${it.operation.name}" }

    @Test fun aHostedComponentReportsItsStateListsAndRows() {
        val raw = RealBindingProbeViewModel()
        raw.replaceRows()
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) {
                BindingProbeViewModelHost(
                    ownerKey = "observed", storeOwner = compose.activity,
                    prepare = { PreparedViewModel(raw) {} },
                    onActionError = {}, loading = {}, failure = { _, _ -> },
                    observer = observer,
                ) { model ->
                    Column {
                        BasicText("note:${model.state.note}")
                        model.RowsForEach(bindingChild = { child -> BasicText("row:${child.state.title}") })
                    }
                }
            }
        }
        compose.onNodeWithText("row:Row child").assertExists()

        assertEquals(
            setOf("BindingProbeViewModel.state", "BindingProbeViewModel.rows", "BindingChildViewModel.state"),
            bindings("Attached").toSet(),
        )
        // One platform collection is one upstream collection.
        assertEquals(1, raw.activeStateCollectors)
        assertEquals(1, raw.activeRowCollectors)
        assertTrue(events.any { it is ViewModelEvent.ListDelta && it.size == 1 })

        shown = false
        compose.waitForIdle()

        assertEquals(bindings("Attached").toSet(), bindings("Detached").toSet())
        assertEquals(0, raw.activeStateCollectors)
    }

    private class Factories : GeneratedViewModelFactories {
        private val store = InMemoryDemoStore()
        override val detailFactory: (DetailViewModel.Args) -> RealDetailViewModel = { RealDetailViewModel(store, it) }
        override val homeFactory: (HomeNavigator) -> RealHomeViewModel = { RealHomeViewModel(store, it) }
        override val pickerFactory: (NavigationResponder<PickResult>) -> RealPickerViewModel = { RealPickerViewModel(store, it) }
    }

    @Test fun routerDestinationsAndTheirRoutersUseTheRoutersObserver() {
        val router = BasekitNavigationRouter()
        router.viewModelObserver = observer
        val root = router.root(BasekitRoute.Home(HomeViewModel.Args(), edge = null))

        val built = basekitViewModel(root, router, Factories())

        assertSame(observer, built.model.viewModelObserver())
        assertSame(observer, BasekitNavigationRouter(router).viewModelObserver)
        assertSame(null, BasekitNavigationRouter().viewModelObserver)
    }

    private class Recording : NavigationObserver, NavigationPresentationObserver {
        val lines = mutableListOf<String>()
        override fun onNavigation(event: NavigationEvent) {
            lines += when (event) {
                is NavigationEvent.NavigatedTo -> "requested ${event.screen}"
                is NavigationEvent.Closed -> "close ${event.screen}"
                is NavigationEvent.Responded -> "responded ${event.screen}"
            }
        }
        override fun onPresentation(event: NavigationPresentationEvent) {
            lines += when (event) {
                is NavigationPresentationEvent.Presented -> "presented ${event.screen} ${event.presentation}"
                is NavigationPresentationEvent.Dismissed -> "dismissed ${event.screen} ${event.presentation}"
            }
        }
    }

    @Test fun routersReportPresentationApartFromRequests() {
        val recording = Recording()
        val router = BasekitNavigationRouter(navigationObserver = recording, actionObserver = com.latenighthack.basekit.viewmodel.ViewModelActionObserver.NoOp)
        val root = router.root(BasekitRoute.Home(HomeViewModel.Args(), edge = null))

        router.didShow(root)
        router.open(DetailViewModel.Args().apply { id = "7" })
        router.reconcilePath(emptyList())

        assertEquals(
            listOf(
                "presented HOME ROOT", "requested HOME",
                "requested DETAIL", "presented DETAIL PUSH",
                "close DETAIL", "dismissed DETAIL PUSH",
            ),
            recording.lines,
        )
    }
}
