package com.latenighthack.basekit.demo

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec

/**
 * A destination with no `@ViewModelInject` implementation. Compiled only with
 * `-Pbasekit.demo.orphanDestination`, where scripts/verify-binding-diagnostics.py expects KSP to reject it.
 */
@Destination
@ViewModelSpec
interface OrphanViewModel : NavigationDestination<OrphanViewModel.Args>, ViewModel<OrphanViewModel.State> {
    class Args : NavigatorArgs()

    data class State(val title: String)
}
