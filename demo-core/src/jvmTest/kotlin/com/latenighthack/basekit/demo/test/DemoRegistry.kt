package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.CloseNavigationTarget
import com.latenighthack.basekit.demo.DemoStore
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.HomeNavigator
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.InMemoryDemoStore
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerViewModel
import com.latenighthack.basekit.demo.RealDetailViewModel
import com.latenighthack.basekit.demo.RealHomeViewModel
import com.latenighthack.basekit.demo.RealPickerViewModel
import com.latenighthack.basekit.navigation.NavigationResponder

/**
 * The one-line-per-destination bridge from the generated [TestViewModelRegistry] to the demo's real,
 * production ViewModels (the exact classes the TUI runs). A single [store] is shared across every
 * ViewModel it builds, so a navigation journey sees consistent data — mirroring the running app.
 */
class DemoRegistry(val store: DemoStore = InMemoryDemoStore()) : TestViewModelRegistry {
    override fun createHomeViewModel(args: HomeViewModel.Args, navigator: HomeNavigator): HomeViewModel =
        RealHomeViewModel(store, navigator)

    override fun createDetailViewModel(args: DetailViewModel.Args, navigator: CloseNavigationTarget): DetailViewModel =
        RealDetailViewModel(store, args)

    override fun createPickerViewModel(
        args: PickerViewModel.Args,
        navigator: CloseNavigationTarget,
        responder: NavigationResponder<PickResult>,
    ): PickerViewModel = RealPickerViewModel(store, responder)
}
