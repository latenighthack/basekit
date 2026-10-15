package com.latenighthack.basekit.viewmodel.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.groupBy
import com.latenighthack.deltalist.operators.lazyMap
import com.latenighthack.deltalist.operators.lazyMapItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Lazily mapped rows of a type with no identity property: the generated identity is the row object,
 * so keys must not come from rows built by reading the list. A row keeps its `remember` state only
 * while it shows the same cached row object.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ComposeTestApplication::class)
class LazyMappedRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Entry(val id: String, val category: String, val version: Int = 0)

    /** A child ViewModel stand-in: built once per cached row, no logical identity. */
    private class Row(val entry: Entry)

    private var remembered = 0

    private fun row(entry: Entry): Row = Row(entry)

    /** Shows `{id}v{version}@{n}`, where n is held in `remember` and so survives only while the row keeps its state. */
    @androidx.compose.runtime.Composable
    private fun RowText(row: Row) {
        val slot = remember { remembered++ }
        BasicText("${row.entry.id}v${row.entry.version}@$slot")
    }

    private fun assertRows(vararg texts: String) = texts.forEach { compose.onNodeWithText(it).assertExists() }

    @Test fun sectionedRowsKeepStateWhenSectionsComeAndGoAndRowsAreInsertedAbove() {
        val entries = mutableDeltaListOf(listOf(Entry("a1", "personal"), Entry("a2", "personal")))
        val sections = entries.groupBy(keySelector = { it.category }).lazyMapItems(::row)
        compose.setContent {
            DeltaSections(sections, identity = { it }, header = { BasicText("header:$it") }) { RowText(it) }
        }
        assertRows("header:personal", "a1v0@0", "a2v0@1")

        // A section appears above the row's section.
        compose.runOnIdle { entries.insert(0, Entry("w1", "work")) }
        assertRows("header:work", "w1v0@2", "a1v0@0", "a2v0@1")
        // A row is inserted above, inside the same section.
        compose.runOnIdle { entries.insert(1, Entry("a0", "personal")) }
        assertRows("w1v0@2", "a0v0@3", "a1v0@0", "a2v0@1")
        // The section above disappears.
        compose.runOnIdle { entries.removeAt(0) }
        compose.onNodeWithText("header:work").assertDoesNotExist()
        assertRows("a0v0@3", "a1v0@0", "a2v0@1")

        // An Update rebuilds that cached row only, and only that row reconnects.
        compose.runOnIdle { entries.set(1, Entry("a1", "personal", version = 1)) }
        assertRows("a0v0@3", "a1v1@4", "a2v0@1")
        compose.onNodeWithText("a1v0@0").assertDoesNotExist()
    }

    @Test fun flatLazyRowsKeepStateOnInsertAboveAndReconnectOnlyUpdatedRows() {
        val entries = mutableDeltaListOf(listOf(Entry("a1", "x"), Entry("a2", "x")))
        val rows = entries.lazyMap(::row)
        compose.setContent { DeltaRows(rows, identity = { it }) { RowText(it) } }
        assertRows("a1v0@0", "a2v0@1")

        compose.runOnIdle { entries.insert(0, Entry("a0", "x")) }
        assertRows("a0v0@2", "a1v0@0", "a2v0@1")
        compose.runOnIdle { entries.move(0, 2) }
        assertRows("a1v0@0", "a2v0@1", "a0v0@2")

        compose.runOnIdle { entries.set(1, Entry("a2", "x", version = 1)) }
        assertRows("a1v0@0", "a2v1@3", "a0v0@2")
    }

    @Test fun unloadedLazyRowsShowPlaceholdersAndRequestTheirPage() {
        val requested = mutableListOf<Int>()
        val page = object : AbstractSoftList<Entry>() {
            override val size = 3
            override fun softGet(index: Int): SoftValue<Entry>? = when (index) {
                0 -> SoftValue.Present(Entry("a1", "x"))
                1, 2 -> SoftValue.NotLoaded { requested += index }
                else -> null
            }
        }
        val sections = flowOf(SectionedDelta(listOf(Section("x", page)), SectionedChange.Reload)).lazyMapItems(::row)
        compose.setContent {
            DeltaSections(sections, identity = { it }, header = { BasicText("header:$it") },
                placeholder = { BasicText("placeholder:$it") }) { RowText(it) }
        }
        assertRows("header:x", "a1v0@0", "placeholder:1", "placeholder:2")
        compose.runOnIdle { assertEquals(listOf(1, 2), requested.sorted()) }
    }
}
