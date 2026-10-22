package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.Route

/**
 * A route that leaves `count` uncaptured; an `Int` has no default. Compiled only with
 * `-Pbasekit.demo.uncapturedRouteArg`, where scripts/verify-binding-diagnostics.py expects KSP to reject it.
 */
@Destination
interface UncapturedRouteArgViewModel : NavigationDestination<UncapturedRouteArgViewModel.Args> {
    @Route("/uncaptured")
    class Args : NavigatorArgs() {
        var count: Int by storedProperty()
    }
}
