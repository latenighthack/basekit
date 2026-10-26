package com.latenighthack.basekit.demo.fake

import com.latenighthack.basekit.demo.DetailViewModel
import com.latenighthack.basekit.demo.FakeArgs
import com.latenighthack.basekit.demo.FakeFeedItemViewModel
import com.latenighthack.basekit.demo.FakeHomeViewModel
import com.latenighthack.basekit.demo.FakeNavigator
import com.latenighthack.basekit.demo.FakePickerEmptyViewModel
import com.latenighthack.basekit.demo.FakePickerOptionViewModel
import com.latenighthack.basekit.demo.FakePickerViewModel
import com.latenighthack.basekit.demo.FakeViewModels
import com.latenighthack.basekit.demo.HomeViewModel
import com.latenighthack.basekit.demo.InMemoryDemoStore
import com.latenighthack.basekit.demo.PickResult
import com.latenighthack.basekit.fake.BasekitFakes
import com.latenighthack.basekit.fake.FakeConfig
import com.latenighthack.basekit.fake.FakeDataSet
import com.latenighthack.basekit.fake.FakeEvent
import com.latenighthack.basekit.fake.walkFakeGraph
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FakeDemoTest {
    @AfterTest
    fun reset() {
        BasekitFakes.config = FakeConfig()
        BasekitFakes.log.clear()
    }

    @Test
    fun theWalkReachesEveryScreenFromHome() = runTest {
        val navigator = FakeNavigator()
        navigator.startHome()
        val walk = walkFakeGraph(navigator.stack)
        assertEquals(listOf("HomeViewModel", "DetailViewModel", "PickerViewModel"), walk.screens)
        assertTrue("HomeViewModel -> PickerViewModel" in walk.edges)
        assertTrue("FakeFeedItemViewModel.onSelected" in walk.actions)
        assertTrue("FakePickerOptionViewModel.onSelected" in walk.actions)
    }

    @Test
    fun aRowOpensTheDetailOfItsOwnId() = runTest {
        val navigator = FakeNavigator()
        val home = navigator.startHome() as FakeHomeViewModel
        val row = home.fakeEmbedded.first() as FakeFeedItemViewModel
        row.onSelected()
        val detail = navigator.top as DetailViewModel
        assertEquals(row.initialState.id, detail.initialState.id)
    }

    @Test
    fun aPickerOptionAnswersTheCaller() = runTest {
        val navigator = FakeNavigator()
        val home = navigator.startHome()
        val pick = launch(start = CoroutineStart.UNDISPATCHED) { home.onPick() }
        val picker = navigator.top as FakePickerViewModel
        val option = picker.fakeEmbedded.first() as FakePickerOptionViewModel
        option.onSelected()
        pick.join()
        assertIs<FakeHomeViewModel>(navigator.top)
        val answer = BasekitFakes.log.events.value.last { it.member == "onPick" }.value
        assertIs<PickResult>(answer)
    }

    @Test
    fun theSeedSetsTheData() {
        fun title(seed: Long) = BasekitFakes.using(FakeConfig(seed = seed)) { FakeNavigator().startHome().initialState }
        assertEquals(title(1), title(1))
        assertNotEquals(title(1), title(2))
    }

    @Test
    fun dataSetsChangeListSizesAndRows() = runTest {
        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.EMPTY)
        val emptyHome = FakeNavigator().startHome()
        assertEquals(0, emptyHome.items.first().items.size)
        val emptyPicker = FakeNavigator().startPicker() as FakePickerViewModel
        assertEquals(1, emptyPicker.options.first().items.size)
        assertIs<FakePickerEmptyViewModel>(emptyPicker.fakeEmbedded.single())

        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.LARGE)
        assertEquals(300, FakeNavigator().startHome().items.first().items.size)

        BasekitFakes.config = FakeConfig()
        val typical = FakeNavigator().startHome().items.first().items.size
        assertTrue(typical in 3..8)
        val picker = FakeNavigator().startPicker() as FakePickerViewModel
        assertTrue(picker.fakeEmbedded.all { it is FakePickerOptionViewModel })
    }

    @Test
    fun theUnchangedComponentBuildsFakes() {
        val component = FakeDemoComponent::class.create(InMemoryDemoStore())
        val navigator = FakeNavigator()
        assertIs<FakeHomeViewModel>(component.homeFactory(navigator))
        assertIs<FakePickerViewModel>(component.pickerFactory { })
        assertEquals("x", component.detailFactory(FakeArgs.detail().apply { id = "x" }).initialState.id)
    }

    @Test
    fun actionsAreLoggedAndBoundMutatorsSetState() = runTest {
        val home: HomeViewModel = FakeNavigator().startHome()
        home.onRefresh()
        assertEquals(FakeEvent("HomeViewModel", "onRefresh"), BasekitFakes.log.events.value.last())

        val probe = FakeViewModels.bindingProbe()
        probe.setNote("hello")
        assertEquals("hello", probe.state.first().note)

        val detail = FakeNavigator().startDetail()
        detail.onSetNote("typed")
        assertEquals("typed", detail.state.first().note)
    }
}
