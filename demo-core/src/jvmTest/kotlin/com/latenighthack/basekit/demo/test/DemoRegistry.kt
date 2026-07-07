package com.latenighthack.basekit.demo.test

import com.latenighthack.basekit.demo.CloseNavigationTarget
import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailScreen
import com.latenighthack.basekit.demo.HomeNavigator
import com.latenighthack.basekit.demo.HomeScreen
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.demo.PickerScreen
import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.viewmodel.StatefulViewModel

// Concrete ViewModels for the two demo destinations, wired with the generated navigator interfaces.
// These stand in for a real consumer's DI-produced ViewModels; the registry below builds them.

class RealHomeScreen(
    private val navigator: HomeNavigator,
) : HomeScreen, StatefulViewModel<HomeScreen.State>(HomeScreen.State(title = "Home", subtitle = "welcome")) {

    // Records what came back from the responding picker flow, so tests can assert on it.
    var lastPick: PickResult? = null
    var pickCount: Int = 0

    override suspend fun onOpenDetail() {
        navigator.navigateToDetail(
            DetailScreen.Args().apply { id = "detail-1" },
            DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL,
        )
    }

    override suspend fun onOpenDetailFromBanner() {
        navigator.navigateToDetail(
            DetailScreen.Args().apply { id = "banner" },
            DetailNavigationTarget.DetailSource.HOME_ON_OPEN_DETAIL_FROM_BANNER,
        )
    }

    override suspend fun onPickTapped() {
        lastPick = navigator.navigateToPicker(PickerScreen.Args())
        pickCount += 1
    }
}

class RealPickerScreen(
    private val responder: NavigationResponder<PickResult>,
) : PickerScreen {
    override suspend fun onItemSelected(id: Int) {
        responder.respond(PickResult(id))
    }
}

class RealDetailScreen(
    args: DetailScreen.Args,
    @Suppress("unused") navigator: CloseNavigationTarget,
) : DetailScreen, StatefulViewModel<DetailScreen.State>(DetailScreen.State(id = args.id, body = "body for ${args.id}"))

/**
 * The one-line-per-destination bridge from the generated [TestViewModelRegistry] to the demo's concrete
 * ViewModels. This is the entire per-consumer cost of adopting the harness.
 */
class DemoRegistry : TestViewModelRegistry {
    override fun createHomeScreen(args: HomeScreen.Args, navigator: HomeNavigator): HomeScreen =
        RealHomeScreen(navigator)

    override fun createDetailScreen(args: DetailScreen.Args, navigator: CloseNavigationTarget): DetailScreen =
        RealDetailScreen(args, navigator)

    override fun createPickerScreen(
        args: PickerScreen.Args,
        navigator: CloseNavigationTarget,
        responder: NavigationResponder<PickResult>,
    ): PickerScreen = RealPickerScreen(responder)
}
