package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.NavigateTo
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModel as ViewModelAnnotation

// A screen is both a navigation destination AND a view model. The two annotations are independent
// slices: `@Destination` drives navigator/route codegen, `@ViewModel` drives the platform binding
// codegen (Android activity / iOS Kvo / React hook). Marking the screen with both makes the same
// screen participate in the view model generation on its own, not just the navigation graph.
@Destination
@ViewModelAnnotation
interface HomeScreen :
    NavigationDestination<HomeScreen.Args>,
    ViewModel<HomeScreen.State> {

    @Route("/")
    class Args : NavigatorArgs()

    data class State(val title: String, val subtitle: String)

    // Two call sites to DetailScreen -> the generated DetailNavigationTarget gets a DetailSource enum.
    // As suspend actions they are also bound by the view model generation (Kvo async / React callback).
    @NavigateTo(DetailScreen::class)
    suspend fun onOpenDetail()

    @NavigateTo(DetailScreen::class)
    suspend fun onOpenDetailFromBanner()
}
