package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.NavigateTo
import com.latenighthack.basekit.navigation.annotations.Route

@Destination
interface HomeScreen : NavigationDestination<HomeScreen.Args> {
    @Route("/")
    class Args : NavigatorArgs()

    // Two call sites to DetailScreen -> the generated DetailNavigationTarget gets a DetailSource enum.
    @NavigateTo(DetailScreen::class)
    suspend fun onOpenDetail()

    @NavigateTo(DetailScreen::class)
    suspend fun onOpenDetailFromBanner()
}
