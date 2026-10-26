package com.latenighthack.basekit.demo.fake

import com.latenighthack.basekit.demo.DetailNavigationTarget
import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.FakeDetailViewModel
import com.latenighthack.basekit.demo.FakeHomeViewModel
import com.latenighthack.basekit.demo.FakeNavigator
import com.latenighthack.basekit.demo.FakeSplashViewModel
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.InMemoryDemoStore
import com.latenighthack.basekit.demo.SplashNavigator
import com.latenighthack.basekit.fake.BasekitFakes
import com.latenighthack.basekit.fake.FakeConfig
import com.latenighthack.basekit.fake.FakeDataSet
import com.latenighthack.basekit.fake.walkFakeGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The fake of a destination whose only edges are `@RedirectsTo` (SplashViewModel, in src/fakeRedirect). */
class FakeRedirectTest {
    @AfterTest
    fun reset() {
        BasekitFakes.config = FakeConfig()
        BasekitFakes.log.clear()
    }

    private fun names(navigator: FakeNavigator) = navigator.stack.entries.value.map { it.name }

    @Test
    fun startingTheSplashLandsOnItsFirstTarget() {
        val navigator = FakeNavigator()
        navigator.startSplash()
        // The redirect replaced the splash: it is not under Home.
        assertEquals(listOf("HomeViewModel"), names(navigator))
        assertIs<FakeHomeViewModel>(navigator.top)
    }

    @Test
    fun fakeRedirectChoosesTheTargetPerDataSet() {
        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.LARGE)
        val large = FakeNavigator()
        large.startSplash()
        assertEquals(listOf("DetailViewModel"), names(large))
        assertIs<FakeDetailViewModel>(large.top)

        // Naming the splash itself keeps the fake on the splash.
        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.EMPTY)
        val empty = FakeNavigator()
        empty.startSplash()
        assertEquals(listOf("SplashViewModel"), names(empty))
    }

    @Test
    fun theWalkStartsFromTheRedirectTarget() = runTest {
        val navigator = FakeNavigator()
        navigator.startSplash()
        val walk = walkFakeGraph(navigator.stack)
        assertEquals(listOf("HomeViewModel", "DetailViewModel", "PickerViewModel"), walk.screens)
    }

    /** What a generated router does: collect the screen's state, and handle the navigation it makes. */
    @Test
    fun collectingStateRedirectsOnceThroughTheInjectedNavigator() = runTest {
        val navigator = RecordingSplashNavigator()
        val component = FakeDemoComponent::class.create(InMemoryDemoStore())
        val splash = component.splashFactory(navigator)
        assertIs<FakeSplashViewModel>(splash)
        splash.state.first()
        splash.state.first()
        assertEquals(1, navigator.homes.size)
        assertTrue(navigator.details.isEmpty())
    }

    @Test
    fun theRedirectUsesTheSourceOfItsCallSite() = runTest {
        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.LARGE)
        val navigator = RecordingSplashNavigator()
        FakeSplashViewModel(BasekitFakes.data("SplashViewModel"), detailTarget = navigator, homeTarget = navigator).state.first()
        assertEquals(listOf(DetailNavigationTarget.DetailSource.SPLASH_REDIRECT), navigator.details.map { it.second })
    }

    private class RecordingSplashNavigator : SplashNavigator {
        val homes = mutableListOf<HomeViewModel.Args>()
        val details = mutableListOf<Pair<DetailViewModel.Args, DetailNavigationTarget.DetailSource>>()

        override fun navigateToHome(args: HomeViewModel.Args, context: Any?) {
            homes += args
        }

        override fun navigateToDetail(args: DetailViewModel.Args, source: DetailNavigationTarget.DetailSource, context: Any?) {
            details += args to source
        }

        override fun close(context: Any?) = Unit
    }
}
