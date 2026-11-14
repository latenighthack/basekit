package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationResponder

/**
 * The demo's view models over one in-memory store, as an app's DI graph would build them. Swift hands it
 * to the generated destination models; the factories themselves are hidden from Objective-C.
 * [onHomeBuilt] runs each time the home view model is built.
 */
class DemoViewModelFactories(
    private val store: DemoStore = InMemoryDemoStore(),
    private val onHomeBuilt: () -> Unit = {},
) : GeneratedViewModelFactories {
    override val detailFactory: (DetailViewModel.Args) -> RealDetailViewModel = { args -> RealDetailViewModel(store, args) }
    override val homeFactory: (HomeNavigator) -> RealHomeViewModel = { navigator ->
        onHomeBuilt()
        RealHomeViewModel(store, navigator)
    }
    override val pickerFactory: (NavigationResponder<PickResult>) -> RealPickerViewModel = { responder -> RealPickerViewModel(store, responder) }
}
