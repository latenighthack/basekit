package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.navigation.annotations.RouteArg
import com.latenighthack.basekit.viewmodel.ViewModel

// Both a destination and a view model (see [HomeScreen] for the rationale).
@Destination
@com.latenighthack.basekit.viewmodel.annotations.ViewModel
interface DetailScreen :
    NavigationDestination<DetailScreen.Args>,
    ViewModel<DetailScreen.State> {

    @Route("/detail/{id}")
    class Args : NavigatorArgs() {
        @RouteArg
        var id: String by storedProperty()
    }

    data class State(val id: String, val body: String)
}
