package com.latenighthack.basekit.demo

/**
 * A navigation stack with no UI, holding the fake of each destination it opens. Start it at any
 * destination with `start…`; every action that navigates then pushes the fake of its target. A
 * destination with `@RedirectsTo` redirects when it is opened, replacing itself with its target.
 */
public class FakeNavigator(
    public val stack: com.latenighthack.basekit.fake.FakeStack = com.latenighthack.basekit.fake.FakeStack(),
) : HomeNavigator, SplashNavigator, DetailNavigationTarget, HomeNavigationTarget, PickerNavigationTarget, CloseNavigationTarget, com.latenighthack.basekit.fake.FakeRedirector {
    /** The view model on top of the stack. */
    public val top: Any? get() = stack.top

    public fun startDetail(args: com.latenighthack.basekit.demo.DetailViewModel.Args = FakeArgs.detail()): com.latenighthack.basekit.demo.DetailViewModel {
        val viewModel = com.latenighthack.basekit.demo.FakeDetailViewModel(com.latenighthack.basekit.fake.BasekitFakes.data("DetailViewModel"), fakeArgs = args)
        stack.root("DetailViewModel", viewModel)
        viewModel.fakeRedirect()
        return viewModel
    }

    public fun startHome(args: com.latenighthack.basekit.demo.HomeViewModel.Args = FakeArgs.home()): com.latenighthack.basekit.demo.HomeViewModel {
        val viewModel = com.latenighthack.basekit.demo.FakeHomeViewModel(com.latenighthack.basekit.fake.BasekitFakes.data("HomeViewModel"), fakeArgs = args, detailTarget = this, pickerTarget = this)
        stack.root("HomeViewModel", viewModel)
        viewModel.fakeRedirect()
        return viewModel
    }

    public fun startPicker(args: com.latenighthack.basekit.demo.PickerViewModel.Args = FakeArgs.picker()): com.latenighthack.basekit.demo.PickerViewModel {
        val viewModel = com.latenighthack.basekit.demo.FakePickerViewModel(com.latenighthack.basekit.fake.BasekitFakes.data("PickerViewModel"), fakeArgs = args)
        stack.root("PickerViewModel", viewModel)
        viewModel.fakeRedirect()
        return viewModel
    }

    public fun startSplash(args: com.latenighthack.basekit.demo.SplashViewModel.Args = FakeArgs.splash()): com.latenighthack.basekit.demo.SplashViewModel {
        val viewModel = com.latenighthack.basekit.demo.FakeSplashViewModel(com.latenighthack.basekit.fake.BasekitFakes.data("SplashViewModel"), fakeArgs = args, detailTarget = this, homeTarget = this)
        stack.root("SplashViewModel", viewModel)
        viewModel.fakeRedirect()
        return viewModel
    }

    override fun navigateToDetail(args: com.latenighthack.basekit.demo.DetailViewModel.Args, source: DetailNavigationTarget.DetailSource, context: Any?) {
        val viewModel = com.latenighthack.basekit.demo.FakeDetailViewModel(com.latenighthack.basekit.fake.BasekitFakes.data("DetailViewModel"), fakeArgs = args)
        stack.push("DetailViewModel", viewModel)
        viewModel.fakeRedirect()
    }

    override fun navigateToHome(args: com.latenighthack.basekit.demo.HomeViewModel.Args, context: Any?) {
        val viewModel = com.latenighthack.basekit.demo.FakeHomeViewModel(com.latenighthack.basekit.fake.BasekitFakes.data("HomeViewModel"), fakeArgs = args, detailTarget = this, pickerTarget = this)
        stack.push("HomeViewModel", viewModel)
        viewModel.fakeRedirect()
    }

    override suspend fun navigateToPicker(args: com.latenighthack.basekit.demo.PickerViewModel.Args, context: Any?): com.latenighthack.basekit.demo.PickResult? =
        stack.pushForResult<com.latenighthack.basekit.demo.PickResult>("PickerViewModel") { responder ->
            com.latenighthack.basekit.demo.FakePickerViewModel(com.latenighthack.basekit.fake.BasekitFakes.data("PickerViewModel"), fakeArgs = args, fakeResponder = com.latenighthack.basekit.fake.FakeResponder { data -> responder.respond(com.latenighthack.basekit.demo.FakeValues.pickResult(data.child("response"))) })
        }

    override fun redirecting(block: () -> Unit) {
        stack.replacing(block)
    }

    override fun close(context: Any?) {
        stack.close()
    }
}
