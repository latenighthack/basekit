# Child viewmodels, identity and ownership

Use child viewmodels for independently bound pieces of presentation that have
their own state, actions or lifecycle. A parent composes children; it does not turn
every label into a viewmodel or duplicate repository truth in a tree of mutable
objects. Children consume use cases under the same strict MVVM rules as screens.

This guide extends the [architecture](../multiplatform-architecture.md) and
[DeltaList guide](delta-lists.md). Read the [navigation guide](navigation.md) for
children that open destinations or participate in result flows. Examples are
application specifications, not a compiled starter; named factories, registries
and lifetime owners are application code unless stated otherwise.

## Choose the right composition mechanism

| Presentation | Recommended contract | Ownership |
| --- | --- | --- |
| Permanent independently bound section | Stable non-null `@ChildViewModel val` | Parent lifetime |
| Homogeneous or polymorphic rows | `@ViewModelList` with `Flow<Delta<Child>>` | Destination registry and explicit collection policy |
| Optional/replaced inline panel | Zero/one DeltaList with exact concrete variants | Explicit selected-panel owner |
| Detail/editor with back, address or result semantics | Typed destination and scoped navigator | Navigation entry lifetime |
| Plain immutable labels or formatting inputs | Scalar immutable state, where no child behavior is needed | Owning viewmodel |
| Animation, focus, layout and local visual disclosure | Native design component state | Native view lifetime |

An optional authenticated root is not a nullable annotated child. Startup selects
and constructs the account/root graph. A selected detail may be rendered inline on
a wide screen and as a sheet on a phone while remaining one semantic destination.
Choose composition according to behavior, not the current layout breakpoint.

## Declare stable child specs

Every child's `State` class is nested inside its specification interface, as
`ItemSummaryViewModel.State` below. This is the rule for every viewmodel, including
parents and empty-state children. A destination's `Args` class also belongs inside
its owning interface. Neither class belongs at the top level or in an implementation.

Basekit requires an annotated child to be a non-null, read-only property whose
declared type is a generated `@ViewModelSpec`. A `val` getter that constructs a
new child each time violates stability even if it passes the property-shape check.

```kotlin
@ViewModelSpec
interface ItemSummaryViewModel : ViewModel<ItemSummaryViewModel.State> {
    data class State(val itemId: ItemId, val title: String, val canOpen: Boolean)
    suspend fun open()
}

@ViewModelSpec
interface ItemEditorViewModel : ViewModel<ItemEditorViewModel.State> {
    data class State(val title: String, val saving: Boolean, val error: EditFailure?)

    @ChildViewModel
    val summary: ItemSummaryViewModel

    suspend fun setTitle(title: String)
    suspend fun save()
}
```

Construct `summary` once in the parent factory/constructor. Update the child's
state when its observed projection changes; retain the instance while its identity
and semantic role remain the same. Do not put the child object inside the parent's
scalar `State`, or publish a second scalar copy of its entire state.

Each spec supplies immutable `initialState` and `Flow<State>`. Drive a child with
`StatefulViewModel.update { copy(...) }`, or implement `ViewModel` directly with a
composed state Flow. The stateful base provides replaying, conflated current state;
the interface also supports cold flows whose work belongs to the binding collector.
Neither requires a private coroutine scope or supplies disposal/an event queue.
Use state, Flow composition and structured cancellation: no application locks,
mutexes or semaphores. Actions are explicit suspend calls in their caller's coroutine.
Do not encode navigation or other one-shot effects as Boolean state toggles. See the
[complete state/Flow examples](viewmodels-state-and-flows.md) and
[tests](viewmodel-testing.md).

Prefer zero-argument suspend actions and one-argument suspend mutators, which the
current generated bindings support. Capture a row's ID at construction instead of
requiring `open(itemId)` on every render. Where an action needs several values,
use one immutable typed command parameter and qualify its actual platform binding.

## Give children typed presentation identity

Every identifier is a concrete wrapper or concrete composite ID class. Domain
identity and presentation identity have separate jobs:

```kotlin
sealed interface ItemChildId {
    data class Row(val sectionId: ItemSectionId, val itemId: ItemId) : ItemChildId
    data class Header(val sectionId: ItemSectionId) : ItemChildId
    data class Empty(val sectionId: ItemSectionId) : ItemChildId
    data class Retry(val sectionId: ItemSectionId) : ItemChildId
}
```

The same `ItemId` in two sections has two presentation identities. A retry row has
no fabricated domain ID. Index, display text, object address and concatenated raw
strings are not keys. Scope retained registries to an account/destination; include
typed scope identity in a composite key when the registry spans those scopes.

Use `@ViewModelIdentity` where appropriate for generated bindings, and test the
actual Apple/Android/web key behavior. An annotation does not establish cross-
language equality, immutable byte identity or wrapper retention by itself. If a
native API requires an encoded key, confine encoding to its tested adapter; keep
the registry and action contracts typed. See [IDs](ids.md).

## Retain stateful children in a bounded registry

For children with drafts, selection, pending work or independently observed state,
use a destination-owned registry keyed by typed presentation identity. Get-or-create
returns the existing child when the key and concrete semantic type survive.

The reconciliation protocol is:

1. Derive authoritative row descriptions from use-case projections and presentation
   policy. Preserve server order and resolved loading/empty/error distinctions.
2. Resolve each description to an existing or newly constructed child. Install its
   owned lifetime before starting observations.
3. Update retained children's content through their defined observation/update
   contract. Do not reset drafts merely because refreshed content arrived.
4. Publish valid structural deltas for inserts, removals, moves and replacements.
   Child scalar state changes need not become structural list replacements.
5. Retire removed/replaced children according to the explicit retention policy;
   cancel jobs, detach callbacks and release resources when they become unreachable.

Do not launch network commands inside a list mapper or create a fresh registry in
a property getter. Build the pipeline once. `lazyMap` manages acquired mapped values;
it is not an identity registry and does not promise durable draft retention across
reloads or final release. The [DeltaList guide](delta-lists.md) specifies mutation
validity, leases, composition and bridge publication in detail.

| Change | Child behavior |
| --- | --- |
| Title/status update, same key and role | Keep instance; update observed scalar state |
| Reordering, same key and role | Keep instance; publish move/order change |
| Item appears in another section | Resolve a distinct presentation child |
| Same row position acquires another entity | Replace child; never retarget an in-flight action |
| Concrete row kind changes | Replace typed binding/child and retire the prior role |
| View leaves viewport | Release binding lease; retain child if policy requires it |
| Filter/page hides a draft | Retain draft in its declared bounded owner, if required |
| Authoritative deletion/account change | Retire affected identity and stop its work |
| Destination closes | Dispose registry, child jobs and pending result waits |

An append-only registry leaks during long paging/search sessions. Specify retention
bounds, when hidden children retire, and whether off-page drafts/selection move to
a separate typed draft owner. Domain removal, filtering and viewport release are
different events. A child removed from a binding must no longer accept stale UI
actions once its owning lifetime is retired.

## Define draft, selection and mutation ownership

There should be one owner for each editable draft. Choose either the parent/editor
or a dedicated child workflow and expose its immutable projection; do not keep two
independent mutable versions synchronized with callbacks.

Initialize the draft from a known resource revision. On remote updates, keep a dirty
draft and expose conflict/rebase policy rather than silently overwriting it. Save
through a use case with typed target, expected revision and operation identity.
Success updates repository truth; the draft owner then adopts the confirmed state.
Failure retains user input. Cancel/discard affects the local draft; it is not a
rollback of a mutation already accepted by the server.

Selection that must survive paging belongs to a destination/workflow owner keyed
by `ItemId`, with row children projecting it. Selection specific to one occurrence
uses presentation identity instead. Do not infer selection from currently bound
cells, and do not make a second row copy of the authoritative selected-ID set.

Share aggregate observations through use cases/repositories. A hundred visible
children must not imply a hundred sockets or duplicate polling loops. Children
may observe per-item projections of shared state; the repository owns transport,
cache and synchronization. Fence late completions with account/resource identity
and generation; cancellation cannot reverse accepted remote effects.

## Route actions through a scoped owner

A child can receive a narrow action port implemented by its owning destination:

```kotlin
fun interface OpenItemAction {
    suspend fun open(itemId: ItemId)
}
```

The row captures `ItemId`; the owner validates current eligibility and calls its
generated navigator. Keep the action port outside immutable state. This preserves
the destination's declared edge and source, without a global navigator accessible
to every row. Different occurrences may use distinct ports to preserve distinct
navigation call sites.

A child that independently owns a navigated workflow can instead be a declared
destination with its own scoped navigator. Avoid inventing destinations solely to
obtain a navigator for a decorative child. A result responder belongs to the
specific presented entry, never a singleton injected into all children.

## Own jobs and binding subscriptions explicitly

For directly exposed cold state Flows, the binding already owns the collection
and its structured upstream work. Do not create a child scope just because an object
is a viewmodel. If extra work must outlive one binding, the application factory
supplies explicit destination/child execution ownership. Create a child job under
the destination job only for that owned work; parent disposal cancels descendants.
A supervisor policy, if used, must still
report failures and expose recovery rather than swallow exceptions.

Basekit's `ViewModel` contract has no universal `dispose()` method. Define an
application lifetime owner/handle and document whether the factory, registry or
navigation host closes it. Do not assume generated bindings close arbitrary
repository observers or nested jobs for you.

Native views own their binding subscriptions and visible-range leases. SwiftUI
body evaluation, Compose recomposition and React render/remount must not create
replacement application children. A binding release cancels that binding's
collection; it does not cancel an account-wide use case or shared repository.
Qualify wrapper retention and mount/unmount behavior on actual generated bindings.

Museums construct deterministic child graphs with fake use cases and the same
specs as production. They exercise loading/error/empty children, dirty drafts,
selection, disabled actions and conflicting updates without real credentials or
uncontrolled background work.

## Acceptance tests

- Verify stable annotated child identity across parent state updates and repeated
  property access; reject nullable/mutable/non-spec annotated properties.
- Verify retained row identity on refresh, reorder and equal decoded IDs; replace
  on semantic-kind change and distinguish repeated entity occurrences.
- Verify drafts and selection survive the declared filter/page policy, while
  deleted/account-retired children cannot accept stale actions.
- Verify binding release, registry retirement and destination disposal separately:
  owned jobs stop, leases release and shared repository observations remain valid.
- Drive child actions through real parent viewmodels and generated navigators;
  assert target ID, source, result, cancellation and mutation recovery.
- Qualify Apple/Android/web keys, wrappers and generated concrete variant bindings;
  use full-server multi-client tests for conflicting edits and remote deletion.

See [testing](testing.md) for production-graph and full-server journey setup.
