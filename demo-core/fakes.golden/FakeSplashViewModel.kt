package com.latenighthack.basekit.demo

@com.latenighthack.basekit.fake.GeneratedFake(com.latenighthack.basekit.demo.SplashViewModel::class)
@com.latenighthack.basekit.viewmodel.annotations.ViewModelInject
public class FakeSplashViewModel(
    override val fakeData: com.latenighthack.basekit.fake.FakeData,
    fakeArgs: com.latenighthack.basekit.demo.SplashViewModel.Args? = null,
    private val detailTarget: com.latenighthack.basekit.demo.DetailNavigationTarget? = null,
    private val homeTarget: com.latenighthack.basekit.demo.HomeNavigationTarget? = null,
) : com.latenighthack.basekit.demo.SplashViewModel, com.latenighthack.basekit.fake.FakeViewModel {

    @me.tatarka.inject.annotations.Inject
    public constructor(
        @me.tatarka.inject.annotations.Assisted navigator: com.latenighthack.basekit.demo.SplashNavigator,
    ) : this(com.latenighthack.basekit.fake.BasekitFakes.data("SplashViewModel"), detailTarget = navigator, homeTarget = navigator)

    override val initialState: com.latenighthack.basekit.demo.SplashViewModel.State = com.latenighthack.basekit.demo.FakeValues.splashViewModelState(fakeData.child("state"), fakeArgs)

    private val fakeState: kotlinx.coroutines.flow.MutableStateFlow<com.latenighthack.basekit.demo.SplashViewModel.State> =
        kotlinx.coroutines.flow.MutableStateFlow(initialState)

    override val state: kotlinx.coroutines.flow.Flow<com.latenighthack.basekit.demo.SplashViewModel.State>
        get() = kotlinx.coroutines.flow.flow {
            fakeRedirect()
            fakeState.collect(this)
        }

    private var fakeRedirected = false

    override fun fakeRedirect() {
        if (fakeRedirected) return
        fakeRedirected = true
        when (when (fakeData.dataSet.firstOf("large", "empty")) { "large" -> 1; "empty" -> -1; else -> 0 }) {
            0 -> {
                val navigator = homeTarget
                if (navigator == null) {
                    fakeData.unreachable("redirect", "HomeViewModel")
                } else {
                    fakeData.record("redirect", "HomeViewModel")
                    com.latenighthack.basekit.fake.fakeRedirecting(navigator) { navigator.navigateToHome(com.latenighthack.basekit.demo.FakeValues.homeViewModelArgs(fakeData.child("redirect"))) }
                }
            }
            1 -> {
                val navigator = detailTarget
                if (navigator == null) {
                    fakeData.unreachable("redirect", "DetailViewModel")
                } else {
                    fakeData.record("redirect", "DetailViewModel")
                    com.latenighthack.basekit.fake.fakeRedirecting(navigator) { navigator.navigateToDetail(com.latenighthack.basekit.demo.FakeValues.detailViewModelArgs(fakeData.child("redirect")), com.latenighthack.basekit.demo.DetailNavigationTarget.DetailSource.SPLASH_REDIRECT) }
                }
            }
            else -> fakeData.record("redirect", "stays")
        }
    }

    override val fakeActions: List<com.latenighthack.basekit.fake.FakeAction> get() = emptyList()

    override val fakeEmbedded: List<com.latenighthack.basekit.fake.FakeViewModel> get() = emptyList()
}
