package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.navigation.annotations.RouteArg

/**
 * `/detail/{key}` has the shape of DetailViewModel's `/detail/{id}`, so neither is more specific.
 * Compiled only with `-Pbasekit.demo.indistinctRoute`, where scripts/verify-binding-diagnostics.py
 * expects KSP to reject it.
 */
@Destination
interface IndistinctRouteViewModel : NavigationDestination<IndistinctRouteViewModel.Args> {
    @Route("/detail/{key}")
    class Args : NavigatorArgs() {
        @RouteArg var key: String by storedProperty()
    }
}
