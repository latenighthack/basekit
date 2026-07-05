package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.navigation.annotations.RouteArg

@Destination
interface DetailScreen : NavigationDestination<DetailScreen.Args> {
    @Route("/detail/{id}")
    class Args : NavigatorArgs() {
        @RouteArg
        var id: String by storedProperty()
    }
}
