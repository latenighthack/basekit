@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationEvent
import com.latenighthack.basekit.navigation.NavigationObserver
import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.viewmodel.ViewModelActionObserver

/** The demo's view models over one in-memory store, as an app's DI graph would build them. */
private class BrowserViewModelFactories(private val store: DemoStore = InMemoryDemoStore()) : GeneratedViewModelFactories {
    override val detailFactory: (DetailViewModel.Args) -> RealDetailViewModel = { args -> RealDetailViewModel(store, args) }
    override val homeFactory: (HomeNavigator) -> RealHomeViewModel = { navigator -> RealHomeViewModel(store, navigator) }
    override val pickerFactory: (NavigationResponder<PickResult>) -> RealPickerViewModel = { responder -> RealPickerViewModel(store, responder) }
}

/**
 * The generated web router over the demo graph, with its destinations and a home root, for the browser
 * acceptance fixture. The picker opens as a sheet, or is pushed when [pushPicker] is set, and detail is
 * pushed. [events] records what the router reports to its navigation observer.
 */
@kotlin.js.JsExport
class BrowserNavigationProbe {
    private val recorded = mutableListOf<String>()
    /** Push the picker, a responding destination without a `@Route`, instead of showing it as a sheet. */
    var pushPicker: Boolean = false
    private val router = BasekitNavigationRouter(
        policy = { request ->
            if (request.route is BasekitRoute.Picker && !pushPicker) BasekitNavigationDecision(BasekitPresentation.Sheet) else BasekitNavigationDecision()
        },
        navigationObserver = NavigationObserver { event ->
            recorded += when (event) {
                is NavigationEvent.NavigatedTo -> "NavigatedTo ${event.screen}"
                is NavigationEvent.Closed -> "Closed ${event.screen}"
                is NavigationEvent.Responded -> "Responded ${event.screen}"
            }
        },
        actionObserver = ViewModelActionObserver.NoOp,
    )
    val web: BasekitWebRouter = router.web
    val destinations: BasekitWebDestinations = basekitWebDestinations(BrowserViewModelFactories())
    val root: BasekitWebEntry = web.entry(router.root(BasekitRoute.Home(HomeViewModel.Args(), edge = null)))
    /** Each navigation event as `Kind SCREEN`. */
    val events: Array<String> get() = recorded.toTypedArray()
}
