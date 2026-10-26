package com.latenighthack.basekit.demo.fake

import com.latenighthack.basekit.demo.FakeCredentialItemViewModel
import com.latenighthack.basekit.demo.FakeNoCredentialsItemViewModel
import com.latenighthack.basekit.demo.FakeOverviewSectionViewModel
import com.latenighthack.basekit.demo.FakeViewModels
import com.latenighthack.basekit.fake.BasekitFakes
import com.latenighthack.basekit.fake.FakeConfig
import com.latenighthack.basekit.fake.FakeDataSet
import com.latenighthack.basekit.fake.FakeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** List sizes and placeholder rows, on OverviewViewModel (in src/fakeFixtures), which has no size annotations. */
class FakeListsTest {
    /** The rows and children a fake shows. */
    private val Any.embedded: List<FakeViewModel> get() = (this as FakeViewModel).fakeEmbedded

    @AfterTest
    fun reset() {
        BasekitFakes.config = FakeConfig()
    }

    @Test
    fun anUnannotatedListHasOneRowAndSoDoesTheListInsideIt() = runTest {
        val overview = FakeViewModels.overview()
        assertEquals(1, overview.sections.first().items.size)
        val section = overview.embedded.single() as FakeOverviewSectionViewModel
        assertEquals(1, section.items.first().items.size)
        assertIs<FakeCredentialItemViewModel>(section.embedded.single())
    }

    @Test
    fun thePlaceholderIsNeverARowOfAListWithRows() {
        BasekitFakes.config = FakeConfig(listMin = 1, listMax = 20)
        repeat(50) { seed ->
            val section = BasekitFakes.using(BasekitFakes.config.copy(seed = seed.toLong())) { FakeViewModels.overviewSection() }
            val rows = section.embedded
            assertTrue(rows.isNotEmpty() && rows.all { it is FakeCredentialItemViewModel }, "seed $seed: $rows")
        }
        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.LARGE)
        assertTrue(FakeViewModels.overviewSection().embedded.all { it is FakeCredentialItemViewModel })
    }

    @Test
    fun anEmptyListShowsOnlyThePlaceholder() = runTest {
        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.EMPTY)
        val section = FakeViewModels.overviewSection()
        assertIs<FakeNoCredentialsItemViewModel>(section.embedded.single())
        assertEquals(1, section.items.first().items.size)
        // A list without a placeholder is simply empty.
        assertEquals(0, FakeViewModels.overview().sections.first().items.size)
    }

    @Test
    fun largeScalesTheDefaultToTenRows() = runTest {
        BasekitFakes.config = FakeConfig(dataSet = FakeDataSet.LARGE)
        val overview = FakeViewModels.overview()
        assertEquals(10, overview.sections.first().items.size)
        val section = overview.embedded.first() as FakeOverviewSectionViewModel
        assertEquals(10, section.items.first().items.size)
    }

    @Test
    fun configBoundsApplyWhenTheBuildSetsNone() = runTest {
        BasekitFakes.config = FakeConfig(listMin = 3, listMax = 3)
        assertEquals(3, FakeViewModels.overview().sections.first().items.size)
    }
}
