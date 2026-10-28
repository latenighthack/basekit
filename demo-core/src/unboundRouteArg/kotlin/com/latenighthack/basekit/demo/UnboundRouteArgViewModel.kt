package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.navigation.annotations.RouteArg

/**
 * An alias that doesn't capture the non-null route arg `id`, which only a `String?` route arg allows.
 * Compiled only with `-Pbasekit.demo.unboundRouteArg`, where scripts/verify-binding-diagnostics.py
 * expects KSP to reject it.
 */
@Destination
interface UnboundRouteArgViewModel : NavigationDestination<UnboundRouteArgViewModel.Args> {
    @Route("/unbound/{id}", aliases = ["/unbound"])
    class Args : NavigatorArgs() {
        @RouteArg
        var id: String by storedProperty()
    }
}
