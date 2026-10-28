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
repository, or store navigator closures in immutable state. A list row or child
declares `@NavigateTo` on its own action and receives its destination's navigator;
see [child viewmodels](child-viewmodels.md#declare-navigation-on-the-row-that-triggers-it).

### Redirects

Some navigation happens without a user action: an entry screen that sends the user
to the right first step, or a screen that leaves when what it shows is deleted.
Declare those targets on the destination with `@RedirectsTo`:

```kotlin
@Destination
@RedirectsTo(SignInPasskeyViewModel::class, SignInEmailViewModel::class)
@ViewModelSpec
interface SignInViewModel : NavigationDestination<SignInViewModel.Args>, ViewModel<SignInViewModel.State>
```

Each target joins the destination's generated navigator like a `@NavigateTo` edge.
Its call site is the destination's `redirect`, so where the target has several call
sites the source entry is `<SCREEN>_REDIRECT`:

```kotlin
navigator.navigateToSignInPasskey(SignInPasskeyViewModel.Args(), SignInPasskeyNavigationTarget.SignInPasskeySource.SIGN_IN_REDIRECT)
```

Which target to open, and when, is the ViewModel's decision; like `@NavigateTo`, the
annotation only permits the edge. `@RedirectsTo` belongs on a `@Destination`; on any
other type it has no effect and the build warns. The catalog records it as
`redirects_to`.

### Hosted components

Some components are on every screen without being part of any one of them: a
sidebar, a header, a status banner. Screens don't list such a component under a
`@ChildViewModel`, and the app creates one instance for all of them. Mark its spec
with `@HostedComponent`:

```kotlin
@HostedComponent
@ViewModelSpec
interface ShellViewModel : ViewModel<ShellViewModel.State> {
    @ChildViewModel
    val sidebar: SidebarViewModel
}
```

The `@NavigateTo` edges of the component, and of the rows and children it embeds,
get their own navigator, `ShellNavigator`, which extends the `<X>NavigationTarget`
of every screen they open. It has no `close()`: the component is not a screen.
Inject it like a destination's navigator; the generated `ObservingShellNavigator`,
`HostShellNavigator` and test navigator implement it. The app's platform host
decides which screen stack a call from the shell opens on, usually the one on top.

The edges keep their call sites: a sidebar row's tap is `SIDEBAR_ON_ACTIVITY_TAPPED`
in `ActivitySource`, the same as if a screen embedded the sidebar. A
`NavigationEvent.NavigatedTo` from the shell records that call site, not the
screen that was showing; screen events report the current screen. A
`@HostedComponent` can't also be a `@Destination`, and `navName` overrides its
derived name as it does on `@Destination`. The catalog records it as
`hosted: true`.

## Keep arguments and results typed

Arguments carry minimal typed context such as `ItemId`, not an entire mutable
entity snapshot or live repository object. The destination resolves current data
and eligibility through use cases. Every ID remains a concrete canonical wrapper
in viewmodel constructor inputs, state, callbacks, results and application-facing
navigation helpers.

The typed `storedProperty<ItemId>()` shape above needs a qualified platform codec.
Current Android `NavigatorArgs` stores primitive supported values directly and
falls back to `Serializable` for other types. A nullable argument stores and reads
back null on every platform, and reading an argument that was never set fails
with its name on every platform. A generated ID class is not
automatically Serializable. Current Apple arguments use in-memory storage; that
does not establish process restoration. Prove the chosen wrapper's transport or
provide a narrow adapter before claiming the Args work on every target.

The current `@RouteArg` binding requires `String` or `String?`. Confine any such generated
encoded field to a route adapter, immediately decode it into `ItemId`, and pass
only typed arguments to the application factory. Use distinct adapter types if
needed; never add a public raw-string ID overload or weaken domain Args to satisfy
the annotation. If the generator cannot express the required separation, use an
application route resolver or extend the generator rather than exposing raw IDs.
See [ID encoding boundaries](ids.md#encoding-boundaries).

A screen with more than one URL, such as an old path kept working after a rename,
lists the others as aliases:

```kotlin
@Route("/apps", aliases = ["/connections"])
class Args() : NavigatorArgs()
```

A template can also capture query parameters. Each `key={name}` part after `?`
binds the URL's `key` parameter to the `@RouteArg` named `name`:

```kotlin
@Route("/requests?request={requestId}")
class Args() : NavigatorArgs() {
    @RouteArg var requestId: String by storedProperty()
}
```

`/requests?request=r1` opens it with `requestId = "r1"`. The value is decoded as
a form value (`+` is a space, `%XX` an escaped byte). The template matches only
when every key it captures has a non-empty value. Query parameters it does not
name are ignored, and a repeated key gives its first value.

A `key={name?}` capture is optional: the template also matches a URL without the
key, and leaves the `String?` argument null. Only a query capture can be optional.

```kotlin
@Route("/agents/{agentId}/connections/{connectionId}?resource={resourceId?}")
class Args() : NavigatorArgs() {
    @RouteArg var agentId: String by storedProperty()
    @RouteArg var connectionId: String by storedProperty()
    @RouteArg var resourceId: String? by storedProperty() // null without ?resource=
}
```

`RouteTable.match` takes the address as it appears in a URL. It splits the path
at `/` and then decodes each segment (`%XX` is an escaped byte; `+` stays a `+`),
so a path capture can hold an escaped `/`, `?` or `#`: `/files/a%2Fb` captures
`a/b` for `/files/{name}`. `Route.address(values)` builds the address the other
way, escaping each value as `encodeURIComponent` does.

When several templates match one URL, the most specific one opens, whatever order
they were registered in. At the first path segment where two templates differ, a
literal wins over a parameter: `/agents/add` opens the add screen and
`/agents/{agentId}` every other agent. With the same literals and parameters in the
same places, the one with more required query captures wins, so a plain `/requests`,
`/requests?request={requestId}` and `/requests?task={taskId}` can open three
destinations.

The build fails on templates that can match one address with neither more
specific: two of the same shape, such as `/agents/{agentId}` and `/agents/{id}`,
and two whose paths cross, each literal where the other has a parameter, such as
`/teams/new/{section}` and `/teams/{teamId}/settings` (both match
`/teams/new/settings`). Make one literal wherever the other is. Optional captures
don't count toward a template's shape, so `/burners` and `/burners?burner={id?}`
have the same shape. Templates on the
same path that capture different query keys are allowed: a URL carrying both,
such as `/requests?request=r1&task=t1`, opens the one the generated
`GeneratedRoutes` registers first, which is by destination name. A query part that is not
`key={name}` or `key={name?}`, a `{name?}` in the path, a name captured twice, a
capture that is not a `@RouteArg`, or a `@RouteArg` its routes do not capture is a
build error.

A route sets every argument of its `Args`, so a destination opened from its address
never reads an unset one. An argument it does not capture gets a default: `false`
for a `Boolean`, `null` for a nullable type. Any other argument that the route does
not capture is a build error; capture it, or make it nullable or a `Boolean`.

```kotlin
@Route("/sign-up")
class Args() : NavigatorArgs() {
    var inviteCode: String? by storedProperty() // null when opened from /sign-up
    var isAdmin: Boolean by storedProperty()    // false when opened from /sign-up
}
```

`GeneratedRoutes` registers each template, the path and each alias, with its own
args factory. A factory binds the `@RouteArg`s its template captures and sets a
`String?` one it does not capture to null. So every template must capture each
non-null `@RouteArg` (not as an optional `{name?}`), and a `String?` one only needs
capturing by some of them; the build fails otherwise. This gives a screen one
address for each way it opens:

```kotlin
// The list opens at /burners, and one burner's chat at /burners/{burnerSessionId}.
@Route("/burners", aliases = ["/burners/{burnerSessionId}"])
class Args() : NavigatorArgs() {
    @RouteArg var burnerSessionId: String? by storedProperty()
}
```

The web router gives an entry the address of the template requiring the most
captures whose required captures all have a value in its args, and the path
before an alias that requires as many. Above, a burner's chat is
`/burners/b1` and the list `/burners`. An entry no template fits has no address,
and keeps the current one. The catalog records aliases as `route_aliases`.

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

The generated routers (Apple, Compose and web) give every entry they present other than
by push (sheet, cover or custom) a router of its own, and that entry's navigator
reports to it: `router.host(for: entry)`, which the stack host passes to its
closures. A sheet's own pushes and sheets therefore stay over the sheet, and
sheets stack. Each router has one sheet slot; an entry that leaves takes
everything presented over it, answering their responders with null. The router
only records where each entry went. The policy and the screen's navigation
handler still choose the presentation.

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

The generated web router (`basekit.viewmodel.react`) leaves one owner of history:
`BasekitBrowserHistory` mirrors the top router's path. Each pushed entry adds a
history entry at its `@Route` address; browser back closes the entries over the
one it returns to, answering their responders with null; and entries the router
closes are popped from history. Sheets and covers add no history entry, and
forward is undone because the entries it would return to have closed. Addresses
are matched still escaped, so a path `@RouteArg` can contain `/`.

`BasekitWebAddresses` (Kotlin only) holds what the web router matches addresses
against and builds them from. `BasekitWebAddresses.routes` is the `RouteTable`
that `open`, `root` and the history match against; it holds `GeneratedRoutes`,
and the app can register addresses in it that its `@Route`s don't declare.
`BasekitWebAddresses.url` is an optional function that gives a route its
address before its `@Route` templates do; returning null uses them. Set both
before the history attaches. Query-only selection, replace, leave guards,
scroll restoration and modified clicks are still the host's. See the
[README](../../README.md#web-navigation).

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
