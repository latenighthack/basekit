package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.CloseNavigationTarget
import com.latenighthack.basekit.demo.DemoStore
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.HomeNavigator
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.InMemoryDemoStore
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerViewModel
import com.latenighthack.basekit.navigation.NavigationResponder

/**
 * The one-line-per-destination bridge from the generated [TestViewModelRegistry] to the demo's real,
 * production ViewModels. Each ViewModel is built through kotlin-inject ([DemoTestComponent]) — the same
 * DI path the TUI uses — with a single [store] shared across the journey so it sees consistent data.
 */
class DemoRegistry(val store: DemoStore = InMemoryDemoStore()) : TestViewModelRegistry {
    private val component = DemoTestComponent::class.create(store)

    override fun createHomeViewModel(args: HomeViewModel.Args, navigator: HomeNavigator): HomeViewModel =
        component.homeFactory(navigator)

    override fun createDetailViewModel(args: DetailViewModel.Args, navigator: CloseNavigationTarget): DetailViewModel =
        component.detailFactory(args)

    override fun createPickerViewModel(
        args: PickerViewModel.Args,
        navigator: CloseNavigationTarget,
        responder: NavigationResponder<PickResult>,
    ): PickerViewModel = component.pickerFactory(responder)
}
