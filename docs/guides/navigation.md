# Typed navigation, results and platform hosts

Shared viewmodels declare navigation intent through Basekit destinations and scoped
navigators. Platform hosts choose push, sheet, dialog, inline detail and browser
history presentation. Use cases own application decisions; repositories own data;
navigation does not become another authority or data store.

This guide supplements the [architecture](../multiplatform-architecture.md),
[child-viewmodel guide](child-viewmodels.md), [provider guide](platform-providers.md)
and [testing guide](testing.md). Examples use illustrative application types and
factories; generated names depend on the actual destination graph.

The [composition/startup/lifecycle guide](composition-startup-and-lifecycle.md)
defines runtime preparation, shared root resolution, host acknowledgement and
account transitions that own these navigation entries.

## Declare destinations and permitted edges

A destination can also be its viewmodel spec. **`State` and `Args` classes always
belong inside the owning specification interface.** Use `ItemDetailViewModel.State`
and `ItemDetailViewModel.Args` from implementations and call sites; never declare
these classes at the top level or inside the implementation. The nested `Args`
extends `NavigatorArgs`; a normal destination implements
`NavigationDestination<ItemDetailViewModel.Args>`.

```kotlin
@Destination
@ViewModelSpec
interface ItemDetailViewModel :
    NavigationDestination<ItemDetailViewModel.Args>,
    ViewModel<ItemDetailViewModel.State> {

    class Args() : NavigatorArgs() {
        var itemId: ItemId by storedProperty()

        constructor(itemId: ItemId) : this() {
            this.itemId = itemId
        }
    }

    data class State(val itemId: ItemId, val loading: Boolean, val error: ReadFailure?)
}

@Destination
@ViewModelSpec
interface ItemsViewModel :
    NavigationDestination<ItemsViewModel.Args>,
    ViewModel<ItemsViewModel.State> {

    class Args() : NavigatorArgs()
    data class State(val refreshing: Boolean)

    @NavigateTo(ItemDetailViewModel::class)
    suspend fun openSelected()

    @NavigateTo(ItemDetailViewModel::class)
    suspend fun openFeatured()
}
```

Every Args class must declare a public default (zero-argument) constructor. For
Args with parameters, also declare explicit typed constructors that delegate to
the default constructor and initialize the delegated properties. Initialize all
required arguments in those constructors; provide overloads only for meaningful,
valid argument combinations. The default constructor remains available for
generated/platform construction, but parameterized application call sites must
use the typed constructors. Do not use `apply` to populate Args or construct them
through a sequence of property assignments at navigation call sites.

`@NavigateTo` declares an allowed source edge. It does not automatically navigate
when the action runs. The implementation explicitly invokes the generated scoped
navigator after checking eligibility:

```kotlin
navigator.navigateToItemDetail(
    ItemDetailViewModel.Args(itemId = selectedItemId),
    ItemDetailNavigationTarget.ItemDetailSource.ITEMS_OPEN_SELECTED,
)
```

With multiple call sites targeting one destination, the current generator emits a
target source enum distinguishing those calls. With one call site it omits that
source parameter. Inspect generated output rather than hardcoding a guessed name.
Preserve distinct sources for row, banner, notification and other meaningful
entries, even when they share a target. Source informs presentation/diagnostics;
it never substitutes for authorization.

Inject the destination's scoped navigator, commonly through an assisted factory.
Do not pass a global unrestricted navigator into every viewmodel, navigate from a
repository, or store navigator closures in immutable state. A child row can invoke
a typed owner action port so its parent owns the declared edge and source.

## Keep arguments and results typed

Arguments carry minimal typed context such as `ItemId`, not an entire mutable
entity snapshot or live repository object. The destination resolves current data
and eligibility through use cases. Every ID remains a concrete canonical wrapper
in viewmodel constructor inputs, state, callbacks, results and application-facing
navigation helpers.

The typed `storedProperty<ItemId>()` shape above needs a qualified platform codec.
Current Android `NavigatorArgs` stores primitive supported values directly and
falls back to `Serializable` for other types. A generated ID class is not
automatically Serializable. Current Apple arguments use in-memory storage; that
does not establish process restoration. Prove the chosen wrapper's transport or
provide a narrow adapter before claiming the Args work on every target.

The current `@RouteArg` path binding requires `String`. Confine any such generated
encoded field to a route adapter, immediately decode it into `ItemId`, and pass
only typed arguments to the application factory. Use distinct adapter types if
needed; never add a public raw-string ID overload or weaken domain Args to satisfy
the annotation. If the generator cannot express the required separation, use an
application route resolver or extend the generator rather than exposing raw IDs.
See [ID encoding boundaries](ids.md#encoding-boundaries).

Tokens, proofs, SDK objects, pending continuations and arbitrary return URLs do
not belong in navigation arguments. Use typed safe-return destinations and explicit
secret-bearing boundary types where an issued link requires them. Redact sensitive
values from navigation recordings and diagnostics.

## Treat navigation entries as lifetime owners

Each presented entry has a typed `NavigationEntryId`, decoded arguments, account
generation, scoped navigator and application-owned lifetime. Factories construct
its viewmodel and descendants once for that entry. Two entries for the same
`ItemId` may need independent drafts and result channels.

| Transition | Required behavior |
| --- | --- |
| Push/present | Construct entry and attach its eligible host |
| Native rerender/recomposition | Retain entry/viewmodel; adjust binding subscriptions |
| Host recreation | Reattach or restore under declared platform policy |
| Pop/dismiss/replace | Resolve any result, detach host and close entry-owned work |
| Logout/account switch | Retire obsolete account stack, children and waits |
| Warm resume | Preserve allowed stack and revalidate resources |

Basekit does not prescribe a universal viewmodel disposal hook. The navigation
host and application factory must agree on an explicit close handle. Closing a
destination stops its collectors, child registry, timers and pending waits; it
does not dispose shared account repositories needed by other destinations.

This does not require a private scope in each viewmodel. A direct cold `state` Flow
inherits its binding caller's lifetime, and suspend actions/result waits inherit
their callers. Detaching the binding cancels that collection's structured upstream
work. Explicit entry-owned drivers/jobs are needed only when work should outlive one
binding. Coordinate through state and coroutines, without locks, mutexes or
semaphores; see [viewmodel state and Flows](viewmodels-state-and-flows.md).

Keep platform `context: Any?` escape hatches inside adapters. They must not become
a shared string-keyed bag of IDs or a place to pass Activities/DOM objects through
domain workflows. Use typed application contracts and resolve native presentation
context at the host boundary.

## Specify responding destinations

Use `RespondingDestination<Args, Result>` for a picker/editor that returns a typed
value to its caller. The generated navigation method suspends and returns
`Result?`; `null` means dismissal without a value.

```kotlin
data class PickItemResult(val itemId: ItemId)

@Destination
@ViewModelSpec
interface PickItemViewModel :
    RespondingDestination<PickItemViewModel.Args, PickItemResult>,
    ViewModel<PickItemViewModel.State> {

    class Args() : NavigatorArgs()
    data class State(val loading: Boolean)
}

// Caller action, using its generated navigator.
val result = navigator.navigateToPickItem(PickItemViewModel.Args()) ?: return
selectItemUseCase.select(result.itemId)

// Presented entry receives its own assisted NavigationResponder<PickItemResult>.
responder.respond(PickItemResult(chosenItemId))
```

Basekit's `awaitNavigationResult` completes its result channel at most once.
Duplicate/late responses do not win a second time. Caller cancellation cancels
the pending channel and propagates coroutine cancellation. This channel behavior
does **not** itself dismiss a sheet or destroy a destination: the host must also
coordinate entry cleanup and the actual platform presentation.

Bind each responder to its entry. Resolve native back, swipe, Escape and outside
click through the declared close contract. Define result completion and presentation
removal as coordinated operations; a caller must not push a follow-up onto a host
whose dismissal transition is still unsafe. Handle nested result flows independently
and ensure closing a child cannot pop an unrelated parent.

Dispatch host presentation on the required UI executor. Current generated Apple
navigators marshal host calls to the main thread, but fire-and-forget delivery is
not a presentation-completion acknowledgement. A queued call can outlive its
originating entry; the host must check entry/account validity before applying it.
Add explicit host acknowledgements where startup or chained transitions need them.

| Flow | Recommended result/close contract |
| --- | --- |
| Select an existing item | Chosen typed ID; ordinary dismiss returns null |
| Create an item remotely | Return created ID after confirmed success; define busy dismissal and uncertain completion |
| Edit a parent-owned draft | Return explicit draft outcome; document whether ordinary close keeps or discards edits |
| Manager with immediate mutations | Return completion/next intent; dismiss does not roll back already completed mutations |
| URL-selected detail | Closing removes selection/address state; no fabricated picker result |
| Local destructive confirmation | Typed confirmation target on owner; close timing follows that workflow |

Cancellation cannot undo a server command already accepted. Reconcile response
loss or caller disappearance through repository state and operation identity.
Never turn a late success into a second navigation from an obsolete account/entry.

## Make leave guards explicit

An editor's draft owner determines whether leaving requires save, discard or keep
editing. The host retains the pending navigation intent under a typed lifetime;
the prompt returns a decision, not an unvalidated target URL.

Keep editing abandons the pending intent. Discard clears the local draft and
continues only if the same intent/entry is still valid. Save continues only after
confirmed success and revalidation; failure retains the draft and current screen.
If the selected busy-save policy requires the user to retry navigation, do not
silently replay the old intent when saving completes.

Apply the same guard to buttons, native back gestures, sheet dismissal, browser
back/forward and programmatic transitions where appropriate. Root retirement for
logout/account invalidation has a separate explicit policy. Avoid a blanket global
guard that makes every editor share one pending continuation. Browser unload is a
platform-specific best-effort prompt, not a guaranteed opportunity to save.

## Resolve links through one typed entry pipeline

Deep links, notification taps, fresh browser addresses and restored entries should
converge on the same resolver:

```text
External address / restored entry / notification intent
  → boundary validation + canonical ID decoding
  → typed semantic destination and safe-return context
  → startup/session resolution
  → access/resource eligibility through use cases
  → typed root installation or entry presentation
  → host acknowledgement and reveal
```

Validate schemes, hosts, malformed identifiers, query combinations and permitted
return targets. Handle unknown routes, missing/deleted entities and forbidden access
explicitly. A decoded ID, preview token or known route does not grant access.
Map aliases to canonical destinations instead of creating duplicate screen owners.
API continuation URLs are not screen destinations.

Startup chooses the root for authenticated, signed-out, cached-offline, recovery
and other supported states. The host installs the root and acknowledges it before
reveal. Sign-in viewmodels do not independently push a platform-specific home.
After authentication, restore only a currently permitted typed return destination.
A notification requiring login returns to review; it never replays a delayed
approval or other consequential action automatically.

For process restoration, persist only the minimum versioned semantic route/draft
metadata permitted by policy. Revalidate account and access before recreating a
destination. Do not serialize viewmodel graphs, responders, scopes or navigators;
an old coroutine result wait cannot resume after process death. Android Bundle
transport alone does not establish restoration of the whole application graph.

## Keep presentation and history in platform hosts

| Platform | Host responsibilities |
| --- | --- |
| iOS | Navigation/sheet ownership, eligible originating scene, interactive dismissal guards, transition completion and binding lifetime |
| Android | Back dispatch, screen/sheet policy, Activity recreation, argument codec and explicit state restoration |
| Web | Address/query projection, push versus replace, back/forward guards, scroll restoration, modified-click behavior and remount lifetime |

A wide inline detail and compact sheet can share semantic selected-route state.
Resizing must not create duplicate viewmodels, discard a draft or issue another
mutation. Local disclosure, scrolling to a section, tooltip and animation state
do not automatically become destinations. External authentication SDK sheets are
provider presentations; use the [provider guide](platform-providers.md).

On web, distinguish new path navigation from query-only selection, and specify
scroll preservation/restoration. Replace operations must not add an unwanted back
entry. Preserve browser behavior for modified clicks and new tabs. A blocked
back/forward transition must restore a coherent current address/entry while awaiting
a decision, then continue the approved transition exactly once. Avoid two independent
owners fighting over browser history and shared selected-route state.

## Test the graph and the actual hosts

Enable `Basekit_GenerateTestNavigator=true` and use the generated test navigator
with production module-owned factories. Launch the resolved root, drive actions,
and assert target, typed Args, source and results. Retain the returned viewmodel
for subsequent actions: current `awaitViewModel<T>()` invokes the destination
factory on each match; repeated awaits are not a cache of one existing screen.
Its replaying latest-event match also does not prove a new navigation occurred;
assert the relevant history transition/source or use explicit event gates.

Cover duplicate action taps, two entries targeting the same entity, independent
clients, nested responders, dismiss/respond races, canceled callers and disposal.
Assert both pending-channel cleanup and host-entry cleanup. Exercise draft guards,
save failures, account switches, malformed/forbidden links, response-loss recovery
and restoration with expired access.

Full-server journeys verify that detail loading, edits and follow-up navigation
reflect real repository/server state across clients. Native/browser integration
tests qualify back/swipe/Escape, root installation acknowledgement, transition
timing, real ID transport, browser history and host recreation. Generated recorder
tests cannot prove those presentation guarantees. See [testing](testing.md).
