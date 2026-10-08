# State-driven viewmodels, DeltaList search and caller-owned Flows

Basekit is a **coroutine- and Flow-based, state-driven system**. Drive a viewmodel
with `StatefulViewModel.update` calls, or implement `ViewModel<State>` directly with
a composed `Flow<State>`. The caller that binds a cold flow owns its collection and
structured upstream coroutine lifetime. A viewmodel needs no private scope.

**No application locks, mutexes or semaphores.** Use short pure state transitions,
Flow composition and structured cancellation. Suspending work runs in the calling
action or collected flow, outside state reducers.

**Lists are DeltaList streams, not fields of parent state.** Parent state contains
search text, filter settings and other scalar presentation state. Repository/use-case
collections stay in their list streams through filtering, child mapping and binding.
Do not keep `allItems`, `filteredItems`, list getters, copied catalog values or
snapshot caches in a viewmodel. Do not collect a list into state and then rebuild
another list flow from that state. Snapshot-and-rediff is not the search architecture.

This guide follows Fullhouse's search/filter structure in
`project/core/src/commonMain/kotlin/gg/roll/viewmodel/ISelectFriendsViewModel.kt`,
`IContactsViewModel.kt` and `ILibraryViewModel.kt`: input state selects an observation,
list operators produce child viewmodels, and an empty child belongs in the list.
Fullhouse's `SelectFriendsViewModelTests.kt` and `EmptyStateViewModelTests.kt` exercise
search and placeholder rows through those list contracts.

The [complete example source](../../basekit-viewmodel/src/commonTest/kotlin/com/latenighthack/basekit/viewmodel/examples/SearchViewModels.kt)
and [executable tests](viewmodel-testing.md) are checked into this repository.
See the [DeltaList guide](delta-lists.md) for collection and lease contracts and the
[architecture](../multiplatform-architecture.md) for layer ownership.

## State and Args belong inside the interface

Every viewmodel's `State` class is declared inside its specification interface:
`SearchViewModel.State`, `CatalogItemViewModel.State`, `SearchEmptyViewModel.State`.
A destination's `Args` class is always declared inside that same interface, such as
`ItemDetailViewModel.Args` in the [navigation example](navigation.md#declare-destinations-and-permitted-edges).
These are nested Kotlin classes; use `class` or `data class` inside the interface.
Never declare a viewmodel's State or Args at the top level or inside its implementation.

The interface extends `ViewModel<ItsOwnInterface.State>` and, for a destination,
`NavigationDestination<ItsOwnInterface.Args>`. Implementations, factories, bindings
and tests reference those interface-owned types. Both stateful and direct-Flow
implementations use the same nested State; do not create a second equivalent input
state class alongside it.

## The search pipeline

```text
updateText / updateFavoritesOnly
  → update scalar input state
  → derive distinct search criteria
  → flatMapLatest selects the current catalog observation
  → filterItems applies the criteria within DeltaList
  → lazyMap creates typed row viewmodels when acquired
  → ifEmpty supplies the retained empty child
  → list binding applies deltas and binds visible children
```

`flatMapLatest` cancels the old observation when criteria change. A repository must
provide a valid initial reload for the new subscriber. `filterItems` filters list
items while preserving the DeltaList contract; ordinary `Flow.filter` filters whole
emissions. Never copy loaded items to a plain list, filter them, and treat that copy
as the observable collection. That discards soft loading and incremental behavior.

The source remains live under unchanged criteria: inserts, edits, removals and moves
flow through without another text update. This example uses Fullhouse's query-switching
shape with DeltaList's filtering operator. No extra synchronization collector, list
cache or manual state-to-list publication loop is needed.

## Complete example

The screen searches a catalog and optionally limits results to favorites. Blank
text shows all eligible items. Matching trims whitespace and ignores case, while
`state.text` preserves the user's raw input. The parent owns only those inputs.
The use case exposes `DeltaList<CatalogItem>`; the screen exposes the annotated
`Flow<Delta<SearchListItem>>` consumed by generated list bindings.

The item row has an explicit action with a typed ID. The empty row is a real child
with its own Flow state and a working clear-filters action. It is constructed once
per screen and reused whenever the filtered list is empty, as in Fullhouse.

Both parent implementations below use the same pipeline and satisfy the same tests.
The whole block is a complete Kotlin file, including imports:

```kotlin
package com.latenighthack.basekit.viewmodel.examples

import com.latenighthack.basekit.viewmodel.StatefulViewModel
import com.latenighthack.basekit.viewmodel.ViewModel
import com.latenighthack.basekit.viewmodel.annotations.ViewModelList
import com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.operators.filterItems
import com.latenighthack.deltalist.operators.ifEmpty
import com.latenighthack.deltalist.operators.lazyMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// The example's domain ID; a product uses its existing canonical ID type.
data class CatalogItemId(val value: Int)

data class CatalogItem(val id: CatalogItemId, val title: String, val favorite: Boolean)

// The use case exposes the repository's live DeltaList. The VM never copies it into state.
fun interface ObserveCatalog {
    fun observe(): DeltaList<CatalogItem>
}

fun interface OpenCatalogItem {
    suspend fun open(id: CatalogItemId)
}

interface SearchListItem

@ViewModelSpec
interface CatalogItemViewModel : SearchListItem, ViewModel<CatalogItemViewModel.State> {
    data class State(val title: String, val favorite: Boolean)
    val id: CatalogItemId
    suspend fun open()
}

@ViewModelSpec
interface SearchEmptyViewModel : SearchListItem, ViewModel<SearchEmptyViewModel.State> {
    data class State(val text: String = "", val favoritesOnly: Boolean = false)
    suspend fun clearFilters()
}

@ViewModelSpec
interface SearchViewModel : ViewModel<SearchViewModel.State> {
    // State belongs to the spec interface and contains only scalar input.
    data class State(val text: String = "", val favoritesOnly: Boolean = false)

    suspend fun updateText(text: String)
    suspend fun updateFavoritesOnly(enabled: Boolean)
    suspend fun clearFilters()

    @ViewModelList(CatalogItemViewModel::class, SearchEmptyViewModel::class)
    val items: Flow<Delta<SearchListItem>>
}

// Rows have no local draft. A changed domain item is remapped by lazyMap.
private class CatalogRow(
    item: CatalogItem,
    private val openItem: OpenCatalogItem,
) : CatalogItemViewModel {
    override val id = item.id
    override val initialState = CatalogItemViewModel.State(item.title, item.favorite)
    override val state: Flow<CatalogItemViewModel.State> = emptyFlow()
    override suspend fun open() = openItem.open(id)
}

// One real child reused whenever this screen's filtered list is empty.
// Its state is a direct Flow; the row binding owns that collection.
private class EmptySearchRow(private val owner: SearchViewModel) : SearchEmptyViewModel {
    override val initialState = SearchEmptyViewModel.State(
        owner.initialState.text, owner.initialState.favoritesOnly,
    )
    override val state: Flow<SearchEmptyViewModel.State> = owner.state
        .map { SearchEmptyViewModel.State(it.text, it.favoritesOnly) }
        .distinctUntilChanged()
    override suspend fun clearFilters() = owner.clearFilters()
}

private data class SearchCriteria(val query: String, val favoritesOnly: Boolean)

// Fullhouse's shape: state -> flatMapLatest(observation/filter) -> lazyMap -> ifEmpty.
// filterItems preserves DeltaList coordinates and soft/lazy access.
@OptIn(ExperimentalCoroutinesApi::class)
private fun SearchViewModel.searchItems(
    catalog: ObserveCatalog,
    openItem: OpenCatalogItem,
): Flow<Delta<SearchListItem>> {
    val emptyItem = EmptySearchRow(this)
    return state
        .map { SearchCriteria(it.text.trim(), it.favoritesOnly) }
        .distinctUntilChanged()
        .flatMapLatest { criteria ->
            catalog.observe().filterItems { item ->
                (!criteria.favoritesOnly || item.favorite) &&
                    (criteria.query.isEmpty() || item.title.contains(criteria.query, ignoreCase = true))
            }
        }
        .lazyMap<CatalogItem, SearchListItem> { CatalogRow(it, openItem) }
        .ifEmpty { emptyItem }
}

class StatefulSearchViewModel(
    catalog: ObserveCatalog,
    openItem: OpenCatalogItem,
) : StatefulViewModel<SearchViewModel.State>(SearchViewModel.State()), SearchViewModel {
    override suspend fun updateText(text: String) = update { copy(text = text) }
    override suspend fun updateFavoritesOnly(enabled: Boolean) = update { copy(favoritesOnly = enabled) }
    override suspend fun clearFilters() = update { SearchViewModel.State() }

    override val items: Flow<Delta<SearchListItem>> = searchItems(catalog, openItem)
}

class FlowSearchViewModel(
    catalog: ObserveCatalog,
    openItem: OpenCatalogItem,
) : SearchViewModel {
    override val initialState = SearchViewModel.State()
    private val input = MutableStateFlow(initialState)
    override val state: Flow<SearchViewModel.State> = input.asStateFlow()

    override suspend fun updateText(text: String) = input.update { it.copy(text = text) }
    override suspend fun updateFavoritesOnly(enabled: Boolean) = input.update { it.copy(favoritesOnly = enabled) }
    override suspend fun clearFilters() = input.update { SearchViewModel.State() }

    override val items: Flow<Delta<SearchListItem>> = searchItems(catalog, openItem)
}
```

## Stateful updates and direct state Flows

`StatefulSearchViewModel` retains scalar state in the base class. Its mutators call
`update { copy(...) }`. Updating text works even before a binding exists; the next
list collector starts from the current criteria. There is no `bindCatalog` driver
and no catalog-update method. The repository owns collection changes.

`FlowSearchViewModel` implements the interface directly and holds the same
`SearchViewModel.State` in a private MutableStateFlow. It exposes a read-only Flow
with `asStateFlow()`; no duplicate input model, constructor coroutine or `stateIn`
is needed. This particular implementation is hot. The interface also accepts cold
compositions, as the empty child below demonstrates. A more involved scalar state
implementation can compose use-case observations with `combine` while keeping
observable collections in DeltaList properties.

The empty row is another direct implementation: it maps the parent's state to its
own state. Changing one unmatched search to another updates the existing empty
row's text binding even when list structure remains unchanged. Ordinary item rows
here are immutable presentations of the current domain item; `lazyMap` remaps an
updated item. Their constant state is rendered from `initialState`, with `emptyFlow`
for subsequent changes. Fullhouse also uses directly observed child state for rows
whose detail changes independently of membership.

`initialState` is a synchronous construction fallback, not a current-state accessor.
The stateful base replays current state and conflates equal/intermediate changes.
A direct Flow follows its declared operators. Bindings render `initialState`, then
collect `state`; constant children may have no later emissions. State carries
current presentation values, not one-shot navigation requests.

## Empty content is a child, not a parent flag

`ifEmpty` follows filtering and child mapping, so the list contains either item
children or one `SearchEmptyViewModel`. The placeholder does not coexist with content.
A nonmatching search and a genuinely empty source use the same child contract here,
as in Fullhouse's select-friends screen. Its state supplies the active filters for
platform-owned feedback and its action clears those filters through the parent.

Do not derive a second `isEmpty`, item-count cache or result array in parent state.
If product behavior distinguishes no source content from no matches, express those
variants within the list pipeline; the [DeltaList search guide](delta-lists.md#distinguishing-empty-source-from-search-feedback)
shows the two placeholder boundaries. For sectioned screens, put `ifEmpty` on the
content section, as Fullhouse does for contacts; an intentionally hidden actions
section must remain empty.

An unresolved source is not resolved-empty. The use case must not emit a fabricated
empty catalog during loading. A product with loading/error rows expresses those
states explicitly in its collection contract. This sample waits for source delivery;
its tests hold initial resolution and prove that no empty row is invented early.
Soft/paginated sources retain their unloaded positions and loading protocol. A
locally empty subset is not proof that the whole server has no matching content.

## Generated bindings own collection and children

Expose `state` and the annotated `items` Flow to the generated platform bindings.
They own collection; their DeltaList adapters bind visible children and release
their leases. Cancelling a list binding cancels its active query observation.

Android Activity bindings collect while STARTED; Compose bindings use the host
lifecycle; React effects clean up on unmount; Apple bindings/tasks own their
subscriptions. Use their DeltaList adapters
for visible-range requests, acquisition and release. Keep the VM and its list
property stable across render/recomposition. Do not launch another collector each
render, and do not use `collectLatest` to interrupt applying a list mutation batch.

Collecting only parent state does not start `items`. Each list collector owns its
own upstream observation. Cancelling one binding leaves another binding active.
An account-owned repository can share expensive transport beneath those observations;
unbinding one screen does not cancel shared account work. Query changes can recreate
stateless row presentations; `lazyMap` is not a persistent draft registry. Keep any
required draft ownership explicit rather than promising retention across reloads.

Cold upstream work inherits its collector's coroutine context. `flowOn` may change
upstream execution without detaching its lifetime. `stateIn`, `shareIn` and `launchIn`
assign work to their explicitly supplied scope, so use them only with a documented
owner. Never apply replay-one sharing, conflation or dropping buffers to already
computed list mutations. See [Kotlin Flow](https://kotlinlang.org/docs/coroutines-flow.html)
for collection and context semantics.

## Update reducers are pure; actions may suspend

The protected API is `suspend fun update(updater: State.() -> State)`. The function
remains declared `suspend`, but the reducer itself cannot suspend. It must be a
short pure state transformation. It can be evaluated again under contention,
following [StateFlow's atomic update contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/update.html).
Do not perform I/O, navigation, launches, ID allocation or effects inside it.

Actions and observed Flows can suspend under their callers. Capture an intent,
perform work outside the reducer, and merge a scalar result only if the intent is
still current. For replaceable search observations, the example's `flatMapLatest`
provides structured cancellation. Preserve cancellation exceptions instead of
presenting them as failures. A cancelled command does not undo accepted remote work.

`withState` reads current state without reserving it; if its inspector suspends,
other updates can occur. Do not treat it as a lock around a read/modify/write action.
“No locks” is the application coordination rule, not a claim about the internal
implementation of coroutine-library primitives.

Migration from the earlier suspending reducer: move suspension/effects outside
`update`, then pass their immutable scalar result to a pure reducer. Recompile
subclasses because the reducer parameter changed from a suspend function type to
a regular function type. This does not authorize moving list ownership into state.

## Test the actual binding contract

The [testing guide](viewmodel-testing.md) collects real deltas and acquires actual
child viewmodels. It tests text and favorite filters, source edits, mutation validity,
row actions, empty-child identity/state/actions, initial resolution, query cancellation,
multiple collectors and rebinding. Plain expected lists exist only inside assertions;
the tested viewmodel never owns or reconstructs a list copy.
