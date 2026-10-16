package com.latenighthack.basekit.viewmodel.compose

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionedChange
import com.latenighthack.deltalist.SectionedDelta
import com.latenighthack.deltalist.SectionedDeltaList
import com.latenighthack.deltalist.SectionedIds
import com.latenighthack.deltalist.SectionedStableIds
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.android.compose.collectAsSectionedDeltaState
import com.latenighthack.deltalist.android.compose.rememberItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Rows of a [Delta] flow. Neither keys nor rendering flatten the list.
 *
 * A row's key is [identity] of its value when the list holds its values. A lazily mapped list
 * ([LazyList]) builds a value on every read, so its rows, like unloaded positions, are keyed by
 * position ids that follow inserts, removes and moves and are regenerated on a reload. Rows render
 * the lazy cache's row through `rememberItem`, and reconnect when that row is replaced.
 */
@Composable
public fun <T : Any> DeltaRows(
    source: Flow<Delta<T>>,
    identity: (T) -> Any,
    modifier: Modifier = Modifier,
    placeholder: @Composable (Int) -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    val keys = remember { RowKeys() }
    val tracked = remember(source) {
        flow {
            // Every emission must reach the tracker in order, before state collection conflates them.
            val ids = SectionedStableIds()
            source.collect { delta -> emit(TrackedDelta(delta.items, ids.apply(delta.asOneSection()))) }
        }
    }
    val current by tracked.collectAsStateWithLifecycle(initialValue = null)
    LazyColumn(modifier) {
        val snapshot = current ?: return@LazyColumn
        val items = snapshot.items
        val key = { index: Int -> rowKey(items, index, keys, identity) { "tracked:" + snapshot.ids.itemId(0, index) } }
        items(items.size, key = key) { index -> DeltaRow(items, index, key(index), placeholder, row) }
    }
}

/**
 * The sectioned peer of [DeltaRows]: one [header] row per section, given the section's position as
 * a UIKit header provider gets its index path, followed by its rows, in the
 * order the Kotlin list emits them. Section keys follow sections through moves. Rows are keyed as in
 * [DeltaRows], with the section's key in front of [identity]; position ids come from
 * [com.latenighthack.deltalist.android.compose.SectionState.itemKey]. Unloaded rows show
 * [placeholder] and request their page.
 */
@Composable
public fun <H, T : Any> DeltaSections(
    source: SectionedDeltaList<H, T>,
    identity: (T) -> Any,
    header: @Composable (header: H, sectionIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (Int) -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    val keys = remember { RowKeys() }
    // No itemKey: deltalist would read it from a peeked value, which maps lazily mapped rows.
    val state = source.collectAsSectionedDeltaState()
    LazyColumn(modifier) {
        for ((sectionIndex, section) in state.sections.withIndex()) {
            item(key = section.key) { header(section.header, sectionIndex) }
            val items = section.items
            val key = { index: Int -> rowKey(items, index, keys, identity, prefix = "${section.key}|") { section.itemKey(index) } }
            items(section.size, key = key) { index -> DeltaRow(items, index, key(index), placeholder, row) }
        }
    }
}

/**
 * The rows of a [Delta] flow in place, inside whatever lays them out, as SwiftUI's `DeltaForEach` does
 * in a stack: for a list among a page's other content, in a popup or a menu. Every row composes; use
 * [DeltaRows] for a list that scrolls by itself. Rows are keyed and rendered as in [DeltaRows].
 */
@Composable
public fun <T : Any> DeltaForEach(
    source: Flow<Delta<T>>,
    identity: (T) -> Any,
    placeholder: @Composable (Int) -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    val keys = remember { RowKeys() }
    val tracked = remember(source) {
        flow {
            val ids = SectionedStableIds()
            source.collect { delta -> emit(TrackedDelta(delta.items, ids.apply(delta.asOneSection()))) }
        }
    }
    val current by tracked.collectAsStateWithLifecycle(initialValue = null)
    val snapshot = current ?: return
    val items = snapshot.items
    for (index in 0 until items.size) {
        val itemKey = rowKey(items, index, keys, identity) { "tracked:" + snapshot.ids.itemId(0, index) }
        key(itemKey) { DeltaRow(items, index, itemKey, placeholder, row) }
    }
}

/**
 * The sectioned peer of [DeltaForEach]: one [header] per section, followed by its rows, in place.
 * Sections and rows are keyed as in [DeltaSections].
 */
@Composable
public fun <H, T : Any> DeltaSectionsForEach(
    source: SectionedDeltaList<H, T>,
    identity: (T) -> Any,
    header: @Composable (header: H, sectionIndex: Int) -> Unit,
    placeholder: @Composable (Int) -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    val keys = remember { RowKeys() }
    val state = source.collectAsSectionedDeltaState()
    for ((sectionIndex, section) in state.sections.withIndex()) {
        key(section.key) {
            header(section.header, sectionIndex)
            val items = section.items
            for (index in 0 until section.size) {
                val itemKey = rowKey(items, index, keys, identity, prefix = "${section.key}|") { section.itemKey(index) }
                key(itemKey) { DeltaRow(items, index, itemKey, placeholder, row) }
            }
        }
    }
}

/**
 * [identity] of the value at [index] when reading it is free, otherwise [tracked]. Reading a
 * [LazyList] position runs its mapper and returns a new value each time, so its keys are always
 * tracked.
 */
private inline fun <T : Any> rowKey(
    items: SoftList<T>,
    index: Int,
    keys: RowKeys,
    identity: (T) -> Any,
    prefix: String = "",
    tracked: () -> Any,
): Any {
    if (items is LazyList<*>) return tracked()
    return when (val item = items.softGet(index)) {
        is SoftValue.Present -> prefix + keys.key(identity(item.value))
        is SoftValue.NotLoaded, null -> tracked()
    }
}

/**
 * Renders the row at [index] through deltalist's [rememberItem], which returns the lazy cache's row.
 * The row reconnects when that object changes, such as a lazy row rebuilt by an `Update`. Unloaded
 * positions show [placeholder] and request their page.
 *
 * deltalist has no public load-state check, so telling a loaded position from an unloaded one reads
 * it with `softGet`, which runs the mapper of a lazily mapped list; that row is discarded.
 */
@Composable
private fun <T : Any> DeltaRow(
    items: SoftList<T>,
    index: Int,
    itemKey: Any,
    placeholder: @Composable (Int) -> Unit,
    row: @Composable (T) -> Unit,
) {
    when (val item = items.softGet(index)) {
        is SoftValue.NotLoaded -> {
            LaunchedEffect(ReferenceIdentity(items), index) { item.request() }
            placeholder(index)
        }
        null -> Unit
        is SoftValue.Present -> {
            val remembered = items.rememberItem(index, itemKey)
            // Replacements must reconnect even if a handwritten ViewModel implements logical equality.
            key(ReferenceIdentity(remembered)) { row(remembered) }
        }
    }
}

/** One emission with its position ids. Compared by reference, so state updates never read rows. */
private class TrackedDelta<T>(val items: SoftList<T>, val ids: SectionedIds)

private fun <T> Delta<T>.asOneSection(): SectionedDelta<Unit, T> = SectionedDelta(
    listOf(Section(Unit, items)),
    when (val change = change) {
        is Change.Reload -> SectionedChange.Reload
        is Change.Mutations -> SectionedChange.Items(0, change.operations)
    },
)

/** Compares by reference: snapshots and ViewModels may implement equality that reads or maps rows. */
private class ReferenceIdentity(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is ReferenceIdentity && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

/** Saveable positional keys for legacy object identity, without retaining removed ViewModels. */
private class RowKeys {
    private val queue = ReferenceQueue<Any>()
    private val objects = mutableMapOf<WeakIdentity, Long>()
    private var next = 0L
    fun key(value: Any): String {
        if (value is String || value is Number || value is Boolean || value is Char) {
            return "loaded:${value.javaClass.name}:$value"
        }
        while (true) { val expired = queue.poll() ?: break; objects.remove(expired) }
        return "object:" + objects.getOrPut(WeakIdentity(value, queue)) { next++ }
    }
    private class WeakIdentity(value: Any, queue: ReferenceQueue<Any>) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            (other is WeakIdentity && get() != null && get() === other.get())
    }
}
