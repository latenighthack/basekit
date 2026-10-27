package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.Route
import com.latenighthack.basekit.navigation.annotations.RouteArg

/**
 * `/teams/new/{section}` and `/teams/{teamId}/settings` both match `/teams/new/settings`, and each is
 * literal where the other is not. Compiled only with `-Pbasekit.demo.crossingRoutes`, where
 * scripts/verify-binding-diagnostics.py expects KSP to reject them.
 */
@Destination
interface NewTeamSectionViewModel : NavigationDestination<NewTeamSectionViewModel.Args> {
    @Route("/teams/new/{section}")
    class Args : NavigatorArgs() {
        @RouteArg var section: String by storedProperty()
    }
}

@Destination
interface TeamSettingsViewModel : NavigationDestination<TeamSettingsViewModel.Args> {
    @Route("/teams/{teamId}/settings")
    class Args : NavigatorArgs() {
        @RouteArg var teamId: String by storedProperty()
    }
}
