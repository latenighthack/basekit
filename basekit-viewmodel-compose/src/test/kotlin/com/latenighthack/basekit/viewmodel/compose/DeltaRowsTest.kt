package com.latenighthack.basekit.viewmodel.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.groupBy
import com.latenighthack.deltalist.operators.lazyMapItems
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class DeltaRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Row(val title: String) {
        override fun toString() = "same description"
    }
    private class Snapshot(val rows: List<Row>) : LazyList<Row> {
        val acquired = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        override val size get() = rows.size
        override fun softGet(index: Int) = rows.getOrNull(index)?.let { SoftValue.Present(it) }
        override fun acquire(index: Int): SoftValue<Row> { acquired += index; return SoftValue.Present(rows[index]) }
        override fun release(index: Int) { released += index }
        override fun releaseAll() { rows.indices.forEach(::release) }
        override fun isAcquired(index: Int) = index in acquired && index !in released
    }

    @Test fun objectKeysDoNotCollideAndAcquisitionsFollowSnapshotOwnership() {
        val first = Snapshot(listOf(Row("first"), Row("second")))
        val source = MutableStateFlow(Delta<Row>(first, Change.Reload))
        compose.setContent { DeltaRows(source, identity = { it }) { BasicText(it.title) } }
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.runOnIdle { assertEquals(listOf(0, 1), first.acquired.sorted()) }
        val replacement = Snapshot(listOf(Row("replacement")))
        compose.runOnIdle { source.value = Delta(replacement, Change.Reload) }
        compose.onNodeWithText("replacement").assertExists()
        compose.runOnIdle {
            assertEquals(listOf(0, 1), first.released.sorted())
            assertEquals(listOf(0), replacement.acquired)
            source.value = Delta(emptyList(), Change.Reload)
        }
        compose.onNodeWithText("replacement").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(0), replacement.released) }
    }

    @Test fun inlineRowsRenderInPlaceAmongOtherContentAndFollowChanges() {
        val source = MutableStateFlow(Delta<Row>(Snapshot(listOf(Row("first"), Row("second"))), Change.Reload))
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BasicText("header")
                DeltaForEach(source, identity = { it }) { BasicText(it.title) }
                BasicText("footer")
            }
        }
        compose.onNodeWithText("header").assertExists()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.onNodeWithText("footer").assertExists()
        compose.runOnIdle { source.value = Delta(Snapshot(listOf(Row("only"))), Change.Reload) }
        compose.onNodeWithText("only").assertExists()
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("footer").assertExists()
    }

    @Test fun sectionHeadersKnowTheirPosition() {
        val sections = mutableDeltaListOf(listOf("first" to "a", "second" to "b"))
            .groupBy(keySelector = { it.first })
            .lazyMapItems { it.second }
        compose.setContent {
            Column {
                DeltaSectionsForEach(sections, identity = { it }, header = { header, index -> BasicText("$index:$header") }) { BasicText(it) }
            }
        }
        compose.onNodeWithText("0:first").assertExists()
        compose.onNodeWithText("1:second").assertExists()
    }
}
