package com.latenighthack.basekit.demo

import com.latenighthack.basekit.fake.annotations.FakeRedirect
import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigatorArgs
import com.latenighthack.basekit.navigation.annotations.Destination
import com.latenighthack.basekit.navigation.annotations.RedirectsTo
import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

/**
 * A launch screen whose only edges are redirects, like an app's splash. Compiled only in fake builds
 * (`-Pbasekit.fakes=true`), as the fixture for redirecting fakes: by default its fake redirects to Home,
 * in the `large` data set to Detail, and in the `empty` data set it stays.
 */
@Destination
@ViewModelSpec
@RedirectsTo(HomeViewModel::class, DetailViewModel::class)
@FakeRedirect(DetailViewModel::class, dataSet = "large")
@FakeRedirect(SplashViewModel::class, dataSet = "empty")
interface SplashViewModel : NavigationDestination<SplashViewModel.Args>, ViewModel<SplashViewModel.State> {
    class Args : NavigatorArgs()

    data class State(val message: String)
}

/** Redirects to Home while its state is collected, as a real splash does once it has checked the session. */
@ViewModelInject
class RealSplashViewModel @Inject constructor(
    @Assisted private val navigator: SplashNavigator,
) : SplashViewModel, StatefulViewModel<SplashViewModel.State>(SplashViewModel.State(message = "Loading")) {
    override val state: Flow<SplashViewModel.State> = flow {
        navigator.navigateToHome(HomeViewModel.Args().apply { welcome = true; referrer = null })
        emitAll(super.state)
    }
}
