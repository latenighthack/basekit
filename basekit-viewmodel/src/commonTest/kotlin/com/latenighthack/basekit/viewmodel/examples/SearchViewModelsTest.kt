package com.latenighthack.basekit.viewmodel.examples

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.ItemLease
import com.latenighthack.deltalist.acquireItemAt
import com.latenighthack.deltalist.applyChange
import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val apple = CatalogItem(CatalogItemId(1), "Apple", favorite = true)
private val apricot = CatalogItem(CatalogItemId(2), "Apricot", favorite = false)
private val banana = CatalogItem(CatalogItemId(3), "Banana", favorite = true)

// A use-case fake with real DeltaList edits, confined to the test scheduler.
// Only the repository/fake owns domain collection mutations; the VM only observes.
private class FakeCatalog(vararg initial: CatalogItem, resolved: Boolean = true) : ObserveCatalog {
    val items = mutableDeltaListOf(initial.toList())
    private val ready = CompletableDeferred<Unit>().also { if (resolved) it.complete(Unit) }
    var starts = 0
        private set
    var active = 0
        private set
    var stops = 0
        private set

    fun resolve() { ready.complete(Unit) }

    override fun observe(): DeltaList<CatalogItem> = flow {
        starts++
        active++
        try {
            ready.await() // no fabricated empty result while unresolved
            emitAll(items)
        } finally {
            active--
            stops++
        }
    }
}

private sealed interface RenderedRow {
    data class Item(val id: CatalogItemId, val title: String, val favorite: Boolean) : RenderedRow
    data object Empty : RenderedRow
}

// Test-only list adapter. Plain lists here are assertion values, never viewmodel state.
// Acquire visible rows, apply every Change in running coordinates, release old leases.
private class ListProbe {
    private var leases: List<ItemLease<SearchListItem>> = emptyList()
    var rendered: List<RenderedRow> = emptyList()
        private set
    val changes = mutableListOf<Change>()

    fun accept(delta: Delta<SearchListItem>) {
        val next = (0 until delta.items.size).map { index ->
            requireNotNull(delta.acquireItemAt(index)) { "Fixture rows must be fully loaded" }
        }
        try {
            val expected = next.map { lease ->
                when (val row = lease.item) {
                    is CatalogItemViewModel -> RenderedRow.Item(row.id, row.initialState.title, row.initialState.favorite)
                    is SearchEmptyViewModel -> RenderedRow.Empty
                    else -> error("Unexpected child type")
                }
            }
            if (changes.isEmpty()) assertIs<Change.Reload>(delta.change)
            val applied = applyChange(rendered, delta.change, expected)
            assertEquals(expected, applied, "Every delta must reconstruct the bound rows")
            rendered = applied
            changes += delta.change
        } catch (error: Throwable) {
            next.forEach { it.release() }
            throw error
        }
        leases.forEach { it.release() }
        leases = next
    }

    fun rows(): List<CatalogItemViewModel> = leases.map { it.item }.filterIsInstance<CatalogItemViewModel>()
    fun empty(): SearchEmptyViewModel = assertIs<SearchEmptyViewModel>(leases.single().item)
    fun ids(): List<CatalogItemId> = rows().map { it.id }
    fun close() {
        leases.forEach { it.release() }
        leases = emptyList()
    }
}

private enum class Mode { STATEFUL, FLOW }

private class BoundSearch(val vm: SearchViewModel) {
    val list = ListProbe()
    var state = vm.initialState
    lateinit var job: Job
}

private fun TestScope.bind(vm: SearchViewModel): BoundSearch = BoundSearch(vm).also { binding ->
    binding.job = backgroundScope.launch {
        launch { vm.state.collect { binding.state = it } }
        try {
            vm.items.collect { binding.list.accept(it) }
        } finally {
            binding.list.close()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelsTest {
    private suspend fun TestScope.eachMode(
        source: () -> FakeCatalog = { FakeCatalog(apple, apricot, banana) },
        check: suspend TestScope.(FakeCatalog, BoundSearch, MutableList<CatalogItemId>) -> Unit,
    ) {
        for (mode in Mode.entries) {
            val catalog = source()
            val opened = mutableListOf<CatalogItemId>()
            val openItem = OpenCatalogItem { opened += it }
            val vm = when (mode) {
                Mode.STATEFUL -> StatefulSearchViewModel(catalog, openItem)
                Mode.FLOW -> FlowSearchViewModel(catalog, openItem)
            }
            assertEquals(0, catalog.starts, "$mode construction must not launch work")
            val binding = bind(vm)
            try {
                runCurrent()
                check(catalog, binding, opened)
            } finally {
                binding.job.cancelAndJoin()
            }
            assertEquals(0, catalog.active, "$mode binding must release its source")
        }
    }

    @Test
    fun text_and_favorite_filters_drive_the_child_list() = runTest {
        eachMode { _, binding, _ ->
            assertEquals(SearchViewModel.State(), binding.state)
            assertEquals(listOf(apple.id, apricot.id, banana.id), binding.list.ids())
            binding.vm.updateText("  AP  ")
            runCurrent()
            assertEquals("  AP  ", binding.state.text)
            assertEquals(listOf(apple.id, apricot.id), binding.list.ids())

            binding.vm.updateFavoritesOnly(true)
            runCurrent()
            assertEquals(listOf(apple.id), binding.list.ids())
            binding.vm.updateText(" ")
            runCurrent()
            assertEquals(listOf(apple.id, banana.id), binding.list.ids())
            binding.vm.clearFilters()
            runCurrent()
            assertEquals(SearchViewModel.State(), binding.state)
            assertEquals(listOf(apple.id, apricot.id, banana.id), binding.list.ids())
        }
    }

    @Test
    fun query_replacement_cancels_the_previous_observation() = runTest {
        eachMode { source, binding, _ ->
            assertEquals(1, source.starts)
            binding.vm.updateText("apple")
            runCurrent()
            assertEquals(2, source.starts)
            assertEquals(1, source.stops)
            assertEquals(1, source.active)
            assertIs<Change.Reload>(binding.list.changes.last())
            assertEquals(listOf(apple.id), binding.list.ids())

            binding.vm.updateText(" apple ") // raw text changes; normalized criteria do not
            runCurrent()
            assertEquals(" apple ", binding.state.text)
            assertEquals(2, source.starts)
        }
    }

    @Test
    fun source_edits_flow_through_without_another_text_update() = runTest {
        eachMode({ FakeCatalog(banana) }) { source, binding, _ ->
            binding.vm.updateText("ap")
            binding.vm.updateFavoritesOnly(true)
            runCurrent()
            binding.list.empty()
            val subscriptions = source.starts

            source.items.append(apple)
            runCurrent()
            assertEquals(listOf(apple.id), binding.list.ids())
            source.items.set(1, apple.copy(title = "Apple pie"))
            runCurrent()
            assertEquals("Apple pie", binding.list.rows().single().initialState.title)
            source.items.set(1, apple.copy(favorite = false))
            runCurrent()
            binding.list.empty()
            source.items.set(1, apple)
            runCurrent()
            assertEquals(listOf(apple.id), binding.list.ids())
            source.items.removeAt(1)
            runCurrent()
            binding.list.empty()
            assertEquals(subscriptions, source.starts)
            assertEquals("ap", binding.state.text)
            assertEquals(true, binding.state.favoritesOnly)
        }
    }

    @Test
    fun list_edits_preserve_mutations_and_retained_unaffected_rows() = runTest {
        eachMode({ FakeCatalog(apple, banana) }) { source, binding, opened ->
            val retainedApple = binding.list.rows().first()
            source.items.insert(1, apricot)
            runCurrent()
            assertIs<Change.Mutations>(binding.list.changes.last())
            assertSame(retainedApple, binding.list.rows().first())
            source.items.move(2, 0)
            runCurrent()
            assertIs<Change.Mutations>(binding.list.changes.last())
            assertEquals(listOf(banana.id, apple.id, apricot.id), binding.list.ids())
            assertSame(retainedApple, binding.list.rows()[1])
            binding.list.rows().first().open()
            assertEquals(listOf(banana.id), opened)
        }
    }

    @Test
    fun empty_is_a_reused_child_with_flow_state_and_a_real_clear_action() = runTest {
        eachMode { _, binding, _ ->
            binding.vm.updateText("missing")
            binding.vm.updateFavoritesOnly(true)
            runCurrent()
            val empty = binding.list.empty()
            var feedback = empty.initialState
            val childBinding = backgroundScope.launch { empty.state.collect { feedback = it } }
            try {
                runCurrent()
                assertEquals(SearchEmptyViewModel.State("missing", true), feedback)
                binding.vm.updateText("still missing")
                runCurrent()
                assertSame(empty, binding.list.empty())
                assertEquals(SearchEmptyViewModel.State("still missing", true), feedback)
                empty.clearFilters()
                runCurrent()
                assertEquals(SearchViewModel.State(), binding.state)
                assertEquals(listOf(apple.id, apricot.id, banana.id), binding.list.ids())
                binding.vm.updateText("missing again")
                runCurrent()
                assertSame(empty, binding.list.empty())
            } finally {
                childBinding.cancelAndJoin()
            }
        }
    }

    @Test
    fun unresolved_source_does_not_fabricate_empty_and_resolved_empty_uses_a_child() = runTest {
        eachMode({ FakeCatalog(resolved = false) }) { source, binding, _ ->
            assertTrue(binding.list.changes.isEmpty())
            source.resolve()
            runCurrent()
            val empty = binding.list.empty()
            source.items.append(apple)
            runCurrent()
            assertEquals(listOf(apple.id), binding.list.ids())
            source.items.clear()
            runCurrent()
            assertSame(empty, binding.list.empty())
        }
    }

    @Test
    fun cancellation_releases_source_and_rebinding_uses_current_input() = runTest {
        eachMode { source, binding, _ ->
            binding.vm.updateText("ap")
            runCurrent()
            val lastRendered = binding.list.rendered
            binding.job.cancelAndJoin()
            assertEquals(0, source.active)
            source.items.removeAt(0) // remove apple while the binding is absent
            runCurrent()
            assertEquals(lastRendered, binding.list.rendered)

            val rebound = bind(binding.vm)
            try {
                runCurrent()
                assertEquals(1, source.active)
                assertEquals("ap", rebound.state.text)
                assertEquals(listOf(apricot.id), rebound.list.ids())
                assertIs<Change.Reload>(rebound.list.changes.first())
            } finally {
                rebound.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun each_list_binding_owns_its_observation() = runTest {
        eachMode { source, first, _ ->
            val second = bind(first.vm)
            try {
                runCurrent()
                assertEquals(2, source.active)
                first.job.cancelAndJoin()
                assertEquals(1, source.active)
                first.vm.updateText("ban")
                runCurrent()
                assertEquals(1, source.active)
                assertEquals(listOf(banana.id), second.list.ids())
            } finally {
                second.job.cancelAndJoin()
            }
            assertEquals(0, source.active)
        }
    }

    @Test
    fun collecting_only_state_does_not_start_the_list() = runTest {
        for (mode in Mode.entries) {
            val source = FakeCatalog(apple, banana)
            val openItem = OpenCatalogItem { }
            val vm = when (mode) {
                Mode.STATEFUL -> StatefulSearchViewModel(source, openItem)
                Mode.FLOW -> FlowSearchViewModel(source, openItem)
            }
            vm.updateText("ban")
            assertEquals(SearchViewModel.State(text = "ban"), vm.state.first())
            assertEquals(0, source.starts)
            val binding = bind(vm)
            try {
                runCurrent()
                assertEquals(listOf(banana.id), binding.list.ids())
            } finally {
                binding.job.cancelAndJoin()
            }
        }
    }
}
