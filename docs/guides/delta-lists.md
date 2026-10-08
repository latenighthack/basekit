# DeltaList collections and child viewmodels

Use DeltaList for every observable collection in the shared client: repository
observations, use-case projections, screen children, static menus, selected chips,
pickers and timelines. Use SectionedDeltaList when section structure is part of
the contract. This guide covers construction, composition, identity, delivery,
soft loading, bindings and verification under the
[architecture](../multiplatform-architecture.md).

All identities follow the [ID guide](ids.md). Repositories own content above
[passive stores](storage-and-repositories.md); the [testing guide](testing.md)
explains component tests and full-server journeys. Snippets omit imports and
application fixture/factory definitions unless those details are central. They
are implementation patterns, not a compiled sample application.

The [child-viewmodel guide](child-viewmodels.md) expands stable composition,
draft/selection ownership and child disposal. The [navigation guide](navigation.md)
specifies how row actions reach scoped destinations and consume typed results.

Viewmodels are driven by state updates or directly composed state Flows. The
[state/Flow guide](viewmodels-state-and-flows.md) has complete search and filtering
implementations with scalar input state, DeltaList child rows, empty-state children and
[runnable tests](viewmodel-testing.md). Cold list/state pipelines belong to the
coroutine of the caller that binds them. Compose them directly; do not add locks,
mutexes, semaphores or a hidden scope to keep a list synchronized with input.

## Observable collections have one contract

```kotlin
// Library vocabulary; import these aliases rather than redeclaring them.
typealias DeltaList<T> = Flow<Delta<T>>
typealias SectionedDeltaList<S, T> = Flow<SectionedDelta<S, T>>
```

Do not expose `Flow<List<Row>>`, `StateFlow<List<Row>>`, arrays of children or
`State.rows` as competing presentation collection contracts. Parent state carries
scalar metadata, input and eligibility; the list stream carries children. A zero/one
collection remains a DeltaList when used for observable optional content.

Do not manage list snapshots in viewmodels or turn a DeltaList into copied lists
to filter, sort, cache or rediff. This is an antipattern even for small collections.
Compose the DeltaList directly. Ordinary lists can occur in wire messages, passive
store results, fixed construction inputs and test expectations; they do not become
observable application list state. Stores remain passive.

## List content and changes

A `Delta<T>` carries the current `items: SoftList<T>` and a `Change`. The change
explains how to reach that content from the preceding
emission received by that particular collector:

- `Change.Reload` replaces the adapter's previous content. Initial delivery, query
  replacement, lost history and structural discontinuities can legitimately reload.
- `Change.Mutations` contains ordered insert/remove/update/move operations. Apply
  each against the running list after the earlier operations in that batch.

For `[A, B, C] → [C, D, A]`, remove index 1, move index 1 to 0, then insert D at
index 1. The second operation addresses C at its new index. Do not reorder operations
or pass these coordinates unchanged to a UI API that uses pre-batch coordinates.

If mutations cannot reconstruct the adapter's current content, reload from the
delta's current SoftList. Never guess coordinates, drop an invalid edit or keep an inconsistent
view. A new collector cannot start with another collector's last mutation.

## Ownership from server to view

```text
RPC result / resource update
  → repository validates account generation and remote version
  → persist records + membership + checkpoint atomically
  → await commit; update repository memory
  → DeltaList<domain item>
  → observation use case filters/composes
  → viewmodel DeltaList<typed child>
  → generated binding / platform adapter
  → production design row
```

Repositories share remote subscriptions and loads. Each presentation collector
receives a valid local history. Rows do not open sockets or fetch the entire list.
Server resource patches and local DeltaList mutation coordinates are separate
protocols. List changes are neither a durable event log nor an offline command queue.

Keep the DeltaList contract across the repository/use-case boundary:

```kotlin
interface ItemRepository {
    fun observeItems(query: ItemQuery): DeltaList<ItemSummary>
}

class ObserveItemsUseCase(private val repository: ItemRepository) {
    operator fun invoke(query: ItemQuery): DeltaList<ItemSummary> =
        repository.observeItems(query)
}
```

The repository owns accepted collection edits and publishes them after commit.
The observation is account/query scoped, starts each subscriber with a valid reload,
and preserves subsequent list changes. Use cases filter/compose this stream; a
viewmodel maps it to children without a whole-list state cache or rediff layer.
Keep IDs unique and typed. Content can change under a retained ID, so comparing
only counts or IDs is not sufficient. Unresolved loading is not a successful empty
collection; expose its defined loading presentation before asserting absence.

## Choosing a construction API

| Need | API | Ownership |
| --- | --- | --- |
| Fixed content | `listOf(...).toDeltaList()` | Static choices/headers converted to child contracts |
| Cold sequential edits | `deltaList(initial) { list -> ... }` | Per-collector construction with bounded work |
| Coherent edits | `list.batch { ... }` in the builder | Running-coordinate edit batch |
| Imperative flat holder | `mutableDeltaListOf(...)` | Serialized writers and subscriber-safe delivery |
| Imperative sections | `mutableSectionedDeltaListOf(...)` | Explicit section/item edits and serialized writers |

Mutable holders and paginated sources must reload on initial subscription and
after missed publications, while consecutive publications retain their mutations.
Current normalized delivery supports that bounded, conflated producer behavior.
Pin a compatible implementation and test it. A slow consumer may skip intermediate
states but must receive a correct reload; this is not lossless event delivery.

Serialize imperative writes; mutation callbacks run once, not as retryable
transactions. Do not put side effects in callbacks. Do not add `stateIn`, replay-one
`shareIn`, `conflate` or a dropping buffer to deltas and assume the library's
subscriber-history contract still holds through that extra layer.

## Basekit specs and typed row identity

At generated spec boundaries use `Flow<Delta<Child>>` explicitly and declare the
exact closed child set with `@ViewModelList`. Domain/use-case contracts can use
the alias. Each concrete child is a `@ViewModelSpec` with its own immutable state.
The shared list marker is not `ViewModel<Any>`.

Declare each `State` class inside its own spec interface (`ItemRowViewModel.State`,
`EmptyItemsViewModel.State`, `ItemsViewModel.State`). Destination `Args` classes
follow the same interface nesting rule. Implementations use those nested types.

```kotlin
interface ItemListChild

@ViewModelSpec
interface ItemRowViewModel : ItemListChild, ViewModel<ItemRowViewModel.State> {
    data class State(val itemId: ItemId, val title: String, val selected: Boolean)
    suspend fun open()
}

@ViewModelSpec
interface EmptyItemsViewModel : ItemListChild, ViewModel<EmptyItemsViewModel.State> {
    data class State(val reason: EmptyReason)
    suspend fun clearFilters()
}

@ViewModelSpec
interface ItemsViewModel : ViewModel<ItemsViewModel.State> {
    data class State(val refreshing: Boolean, val canLoadNext: Boolean)
    @ViewModelList(ItemRowViewModel::class, EmptyItemsViewModel::class)
    val rows: Flow<Delta<ItemListChild>>
}
```

This narrow spec covers resolved content/empty rows. A real screen also declares
every emitted loading/error/header/paging child type. No undeclared variant may
reach a generated binder. Static action choices become child viewmodels too.

Give rows concrete presentation ID classes:

```kotlin
sealed interface ItemRowId {
    data class Content(val sectionId: ItemSectionId, val itemId: ItemId) : ItemRowId
    data class Header(val sectionId: ItemSectionId) : ItemRowId
    data class Empty(val sectionId: ItemSectionId) : ItemRowId
}
```

Section identity is itself a named value. Placeholders do not acquire fake ItemIds.
The same item in two sections has distinct row IDs. Positions and string
concatenations are not row identities. Adapter encodings remain inside the adapter.

Declare `@ViewModelIdentity` where appropriate and prove actual platform equality/
hashing. A typed Kotlin property alone does not guarantee Swift Hashable bridging,
React keys or wrapper retention. Retain children/binding wrappers or use a tested
typed key adapter; never weaken domain IDs to strings to accommodate a binder.

## Mapping and child lifetime

| Operator | Behavior | Use |
| --- | --- | --- |
| `mapItems` | Pure on-access mapping preserving shape; no retained instance cache | Cheap immutable projections |
| `deferredMap` | Deferred uncached mapping | Work safe to repeat |
| `lazyMap` | Acquired-value caching with positional/lease lifecycle | Child binding or expensive repeated access |
| `Flow.map` | Transforms entire emissions | Metadata/projection changes that preserve valid delta semantics |

Build pipelines once per destination or collector-owned construction, not in a
property getter, SwiftUI body, recomposition or React render. A mapper must not
issue commands, navigate or launch an unowned network subscription on access.

`lazyMap` caching is not durable ownership. Reload, update and final release can
replace/evict mapped objects. Children with drafts, selection or retained behavior
belong to a destination-owned registry keyed by `ItemRowId`. Get-or-create returns
the existing child, whose content observes use cases. Retire removed children,
cancel owned jobs and clear the registry on destination disposal.

Filtering a child out of the viewport or list need not discard a draft when policy
requires retaining it. Store off-page draft/selection state in its explicit owner.
Keep that lifetime ownership separate from collection membership. Continue to
publish membership through DeltaList; do not introduce a copied list/rediff layer
to retain children.

Composition preserves acquisition/release when the pinned library supports the
required lifecycle contract. Two useful patterns are:

```kotlin
val rows = items
    .lazyMap<ItemSummary, ItemListChild> { retainedRows.item(it.itemId) }
    .ifEmpty { retainedRows.empty() }
    .header(retainedRows.header())

// Alternatively compose immutable row descriptions first, then map once at the end.
val mapped = combinedDescriptions.lazyMap<RowDescription, ItemListChild> {
    retainedRows.resolve(it)
}
```

Do not insert a placeholder before a mapper that assumes every element is a domain
entity. Description-first composition is safe because its sealed description type
explicitly includes content and placeholders.

## Empty, loading and failure are children

Do not make platforms inspect count and invent their own empty overlay. Empty,
initial-loading and full-list failure presentations are typed list children with
their own state/actions. Parent metadata can still express refresh and page eligibility.

`ifEmpty` inserts one placeholder when total size is zero. Its factory runs at most
once per collection and the instance is reused across empty → content → empty.
Repeated empty-to-empty emissions are suppressed. Placeholder/content transitions
reload; ordinary nonempty changes preserve upstream delta/lazy behavior.

Apply it after filtering and child mapping, before header/footer/concat/sections.
A header makes a combined list nonempty, so a trailing `ifEmpty` cannot represent
an empty body. Apply per-body/per-section. Optional zero/one footers intentionally
permit zero rows; do not substitute a placeholder for their absence.

| Condition | Presentation |
| --- | --- |
| Initial unresolved read without cache | Loading child or unloaded slots |
| Complete resolved empty source | Empty-source child |
| Resolved query with no matches | No-matches child |
| Initial read fails without content | Error child with real retry action |
| Cached content refreshing | Retained rows + scalar refresh metadata |
| Refresh fails with usable content | Retained rows + selected inline failure presentation |
| Next page fails | Existing rows + page-error/retry child |
| Access revoked | Typed permission/session transition, not known empty |

Gate empty presentation on resolved load state. The observation contract should
publish loading/content/error consistently; compose those typed child streams and
apply `ifEmpty` only to resolved content. Do not collect the source into a whole-list
state object to derive feedback. This avoids empty flashes and a second list authority.
Retry calls the load use case; an error row emitted by an already completed cold
flow does not make that flow restart itself. Prefer recoverable typed observation
state rather than terminating content observation for ordinary network failures.

Cached placeholders must not capture a query's initial text forever. Their state
observes the current typed reason, or query replacement owns a new pipeline. A
changed message can update child state even when repeated emptiness emits no delta.

## Filtering and search

Search text and filter settings are state. A suspend `updateText(text)` mutator
performs a pure `update { copy(text = text) }`, or updates an input StateFlow for a
direct `ViewModel.state` pipeline. `filters` below is derived from that state with
`map` (and `distinctUntilChanged` where useful), not maintained by a second background
collector. The binding collects the resulting list; cancelling that cold collection
cancels its upstream work. Use the [complete example](viewmodels-state-and-flows.md)
for all definitions, binding ownership and text/empty-state tests.

```kotlin
val visible = items.filterItems { it.visible }
val predicates: Flow<(ItemSummary) -> Boolean> = filters.map { filter ->
    { item: ItemSummary -> filter.matches(item) }
}
val dynamic = items.filterItemsDynamic(predicates)
```

`filterItems` filters items and translates their coordinates. `Flow.filter` filters
emissions; it is appropriate for test waits, not removing individual rows. Never
replace delta.items with fewer items while retaining the original mutations.

Provide an initial dynamic predicate so source delivery does not wait indefinitely.
Predicate changes re-evaluate and can reload. Debounce search text before deriving
predicates if needed; never debounce deltas. `flatMapLatest` is appropriate for a
query stream selecting a new observation, with a new initial reload and generation.
It is not permission to cancel half-applied list mutations.

Local search on a partially loaded source cannot establish whole-server absence.
Filtered soft lists may load through gaps to find a requested matching position.
Declare partial coverage and bounded loading policy. Filtering hides content; it
does not establish authorization or silently delete retained selection.

### Distinguishing empty source from search feedback

A search-only picker has four states:

| Source | Search | Rows |
| --- | --- | --- |
| Resolved empty | Any | No-items child |
| Resolved nonempty | Blank | Enter-search child |
| Resolved nonempty | Nonblank with no matches | No-matches child |
| Resolved nonempty | Nonblank with matches | Matching item children |

Use immutable descriptions and two deliberate placeholder boundaries:

```kotlin
sealed interface SearchEntry {
    data class Item(val summary: ItemSummary) : SearchEntry
    data object NoItems : SearchEntry
    data object Feedback : SearchEntry
}

val rows: Flow<Delta<ItemListChild>> = resolvedItems()
    .mapItems<ItemSummary, SearchEntry> { SearchEntry.Item(it) }
    .ifEmpty { SearchEntry.NoItems }
    .filterItemsDynamic(normalizedSearch.map { text ->
        { entry: SearchEntry ->
            when (entry) {
                is SearchEntry.Item -> text.isNotBlank() &&
                    entry.summary.title.contains(text, ignoreCase = true)
                SearchEntry.NoItems, SearchEntry.Feedback -> true
            }
        }
    })
    .ifEmpty { SearchEntry.Feedback }
    .lazyMap<SearchEntry, ItemListChild> { entry ->
        when (entry) {
            is SearchEntry.Item -> retainedRows.item(entry.summary.itemId)
            SearchEntry.NoItems -> noItemsChild
            SearchEntry.Feedback -> searchFeedbackChild
        }
    }
```

`resolvedItems`, normalized search, registry and retained children are application
dependencies initialized once in the screen. Feed only resolved content here;
initial loading has its own presentation. The first placeholder survives filtering,
so an empty source is never relabeled no matches. The feedback child observes
normalized search and emits `EnterSearch` or `NoMatches`; raw input remains in
parent state for the text control. Search text is unstructured text, not an ID.

Test blank → matching → unmatched → cleared text, source insert/remove without
another edit, repeated unmatched edits updating the same feedback child, empty
source under any query and no empty flash before resolution.

## Ordering, concatenation and sections

Preserve server ordering for authoritative feeds. Request the required order from
the repository/use-case collection contract, with a deterministic typed tie-break.
Order changes must produce correct DeltaList moves/reloads in that projection.
Do not copy a viewmodel's list into a sorted snapshot and rediff it, or reorder a
delta's items while keeping its previous mutation coordinates.

`withStableIds` provides adapter-local integer bookkeeping that can regenerate on
reload. Those values remain internal; retained selection/navigation/drafts use
the canonical typed row IDs.

```kotlin
val combined = firstBody.concat(secondBody)
val decorated = firstBody.header(headerChild).footer(footerChild)
val sections = sectionedDeltaList(
    firstSectionId to firstBody,
    secondSectionId to secondBody,
)
```

Each source has a defined initial presentation. Combination waits for all sources;
an unresolved body can expose a loading child instead of fabricating empty content.
Each body
owns its load/empty policy before composition. `concat` offsets second-source edits
by the first source's current size and does not reapply stale edits from another
source. Soft snapshots, simultaneous changes or unreconstructable edits can reload.
Accept correct reloads; do not demand mutations at the expense of correctness.

Operator bookkeeping belongs inside each collection. Compose operators directly;
application wrappers are not needed merely to isolate history. Share repository
work under its owner while preserving per-subscriber delivery. Concat/sections do not
sort or deduplicate overlapping entity pages; repositories do that by typed identity.

A SectionedDelta distinguishes section insert/remove/move/header changes from
item mutations inside a section. Their coordinate spaces are different. Fixed
`sectionedDeltaList` inputs establish a fixed section source set; dynamic groups
need a destination/repository-owned section projection or an appropriate mutable
section holder with safe delivery.

`groupBy` produces groups from the contiguous loaded prefix. For
`[Present(A), NotLoaded, Present(B)]`, only A participates until the gap is filled.
It does not fetch the missing page. Group counts/order are partial unless the
source is fully loaded; do not present them as authoritative whole-feed totals.
Use typed grouping values, such as `ItemDay`, not formatted heading strings.

### Binding sections through Basekit

Generated `@ViewModelList` bindings are flat. Flatten headings/items into a closed
polymorphic list for ordinary grouping:

```kotlin
val rows: Flow<Delta<ItemListChild>> = sections.flatten(
    header = { sectionId -> retainedHeaders.getOrCreate(sectionId) },
    item = { child -> child },
)
```

Header children are concrete specs in possibleTypes; the header registry is
application-owned. `flattenItems` intentionally removes headings. Footer mappers
receive available loaded-prefix content, not a server total. Current composition
must preserve lazy acquisition/release; verify that contract in the pinned consumer.

True sticky sections need a dedicated reusable platform adapter for SectionedDeltaList.
Test section and item operations, soft access and ownership independently. If both
flat and sectioned views are exposed, explicitly share upstream work and own both
subscriptions. Do not replace section semantics with ad hoc arrays in feature views.

## Soft access, paging and leases

`SoftList.size` counts all positions, including unloaded ones. `softGet(index)`
returns Present, NotLoaded or null outside bounds. Pure soft access does not fetch;
requesting a current NotLoaded slot asks for load. Unloaded positions mean the list
is nonempty even if no child is currently available.

Request the visible/prefetch window, not every position. Paging policy belongs to
repositories/use cases: typed cursor, generation, overlap deduplication, replacement
versus append, failure retry and no-more-pages. A page-error child is actionable
content; an unloaded slot is an item not yet available.

Lazy acquisition pins mapped values. Prefer owned ItemLease handles where supported:
release is idempotent, follows moves and cannot evict a replacement at the same index
after removal/reload. Acquire a successor before releasing a previous lease when
handoff requires retaining the mounted child. Legacy positional acquire/release
adapters still need their own correct index bookkeeping.

Current concat/header/footer/flatten/stable-ID composition preserves lifecycle
forwarding. A custom wrapper must do the same. Superseded snapshots can remain
readable while load/acquisition side effects are disabled; existing leases can still
release. Do not issue work from a stale snapshot after query/account replacement.
Normal completion of a finite source should leave its final snapshot usable.

`loadedItems` and `softLoadedItems` are explicit partial materializations for bounded
assertions/exports. They omit unloaded positions and can evaluate mapping. They are
not a production rendering strategy for a soft list. Do not convert to arrays to
discover emptiness, eagerly acquire children or claim complete grouped counts.

## Platform bindings

| Platform | Binding contract |
| --- | --- |
| SwiftUI | Generated child bindings into DeltaListView/DeltaLazyListView; container-owned collection for embedded DeltaForEach |
| UIKit/native collection views | Production DeltaList data source, concrete child classifier/cell mapping and owned row leases |
| Android Compose | Generated Basekit host/list binding or DeltaList Compose adapter; owned rememberItem/rememberLazyItemState lifecycle |
| Android RecyclerView | Lifecycle-bound DeltaAdapter with running-coordinate translation and row release |
| React | Generated viewmodel hook and closed child handles; viewport-aware list binding, stable typed key encoding and cleanup |

An initially empty SwiftUI Form/List must start collection from the mounted
container, not from a row that does not yet exist:

```swift
@StateObject private var rows = DeltaList<ItemListChild>()

var body: some View {
    Form {
        DeltaForEach(model.rows, observing: rows) { child in
            ItemChildView(model: child)
        }
    }
    .task(id: ObjectIdentifier(model.rows)) {
        await model.rows.collect(into: rows)
    }
}
```

Use the generated concrete child union/type in a real Swift consumer. Observe
child state as well as list structure; mounted rows update without recreation.
Keep the binding stable until its source changes. Self-contained list views own
their own collection. Marshal snapshot/mutation application onto the UI executor.

React's DeltaList proxy can retain reference identity while its revision changes.
Virtualizers must observe revision and declare visibleRange, request soft slots and
release offscreen items. A stable proxy reference alone does not prove no update.
Do not inspect sparse holes as absence of remote content. Call generated child
hooks inside stable row components; the platform view does not own domain filtering.

## Delivery rules

1. New/replaced collectors initialize with reload; missed producer history recovers
   by subscriber-safe reload or per-collector rediff.
2. Apply deltas sequentially. Do not conflate/sample/debounce them or use collectLatest
   to cancel a partially applied edit. Backpressure recovery belongs to the source/adapter.
3. Subscribe once per owned binding; avoid an extra count collector for feedback.
4. Keep destination/row lifecycle distinct from shared repository/account lifecycle.
5. Atomically apply snapshot and edits; translate native animation coordinates or reload.
6. Retain child IDs and owned state across moves/reloads according to policy.
7. Reload is valid recovery; dropping an edit or fabricated empty content is not.

## Verification and museum coverage

Drive real DeltaList edits and verify every delivered change. Plain lists are only
assertion values inside the oracle. The complete [search tests](viewmodel-testing.md)
also acquire/release real children and test their actions and binding lifetimes.

```kotlin
// Fixtures return domain values with concrete ItemIds, never raw identifier keys.
val source = deltaList<ItemSummary> { items ->
    items.add(Fixtures.itemA())
    items.add(Fixtures.itemB())
    items.set(1, Fixtures.updatedItemB())
    items.move(1, 0)
    items.removeAt(1)
    items.clear()
}
var previous = emptyList<ItemSummary>()
var first = true
source.collect { delta ->
    val expected = delta.items.softLoadedItems()
    if (first) assertEquals(Change.Reload, delta.change)
    assertEquals(expected, applyChange(previous, delta.change, expected))
    previous = expected
    first = false
}
```

Import `applyChange`/soft-list helpers from the core library, operators from its
operators package and coroutine/test APIs normally. This oracle is only for fully
loaded data. Soft-list tests assert total size, holes, explicit request callbacks
and lease counts without turning the source into loaded arrays.

| Test family | Required cases |
| --- | --- |
| Feedback | Loading does not flash empty; resolved empty; placeholder reused; empty/content transitions; real retry/clear action |
| Identity | Retained child/draft, move/selection, content-only state change, variant replacement and removed-child disposal |
| Filtering | Initial predicate order, source edits, changed predicate, cleared search, incomplete paging and hidden selection |
| Composition | Alternate source edits, simultaneous updates, offsets, reloads, late subscribers and independent recollection |
| Sections | Independent feedback, header changes, section/item coordinate spaces, dynamic ordering and flattened agreement |
| Delivery | Slow collector, burst writes, different attach times, normalized reload after skips and no account/query leakage |
| Soft/lazy | Visible requests, gaps, lease move/removal/reload, multiple mounts, stale snapshot inertness and final finite snapshot |
| Paging | Replace/append, overlap, end, retained retry cursor, obsolete response rejection and off-page selection |
| Binding | Every declared variant, actual key semantics, row state, empty-container startup, focus/scroll and unmount cleanup |

Assert placeholder factory count and object identity across empty → populated →
empty. A finite test flow may use `toList()`; never wait for completion of a live
observation. Tests that need individual edits gate emissions; other tests deliberately
use bursts and delayed collectors to verify recovery.

Museums demonstrate the production adapter with deterministic static actions,
loading/empty/error rows, search, selection chips, headings, mutations, soft slots
and next-page failure. Full-server journeys verify that accepted content reaches
real viewmodel children on multiple clients. Neither museum arrays nor manually
emitting fake store notifications satisfy this contract.
