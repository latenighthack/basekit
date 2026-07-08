package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.DemoStore
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.HomeNavigator
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.RealDetailViewModel
import com.latenighthack.basekit.demo.RealHomeViewModel
import com.latenighthack.basekit.demo.RealPickerViewModel
import com.latenighthack.basekit.navigation.NavigationResponder
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject component that builds the demo's real ViewModels exactly as the TUI's generated
 * component does — resolving [DemoStore] from the graph and taking each screen's assisted parameter
 * (navigator / args / responder) at the call site. The store is supplied per component so each test
 * gets an isolated backing store.
 */
@Component
abstract class DemoTestComponent(
    @get:Provides val store: DemoStore,
) {
    abstract val homeFactory: (HomeNavigator) -> RealHomeViewModel
    abstract val detailFactory: (DetailViewModel.Args) -> RealDetailViewModel
    abstract val pickerFactory: (NavigationResponder<PickResult>) -> RealPickerViewModel
}
