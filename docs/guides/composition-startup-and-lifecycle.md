# Client composition, startup and lifecycle

The client composition root connects the prescribed layers into one owned runtime.
Shared startup resolves session access and selects a semantic entry. A platform
host installs the selected root and acknowledges it before revealing the UI.
These are separate responsibilities with separate failure and cancellation rules.

This guide implements the [architecture](../multiplatform-architecture.md) together
with [platform providers](platform-providers.md), [navigation](navigation.md),
[child viewmodels](child-viewmodels.md) and [storage/repositories](storage-and-repositories.md).
All lifecycle types, factories and coordinator APIs below are illustrative
application code. Basekit does not supply a built-in startup DSL, universal client
container or automatic graph disposal.

## Build one layered composition root

`:client` is the public construction and lifecycle entry point. Native apps and
browser shells supply configuration, platform providers, execution dependencies
and presentation hosts through this facade. They do not construct repositories
or repeat startup policy independently.

Preserve `:client → :viewmodel → :usecase → :repository → :api`. Each module owns
its implementation graph and exposes a narrow construction/lifecycle factory.
Passing construction dependencies through a layer is not permission for feature
code to skip that layer.

| Module | Factory responsibility | Exposed result |
| --- | --- | --- |
| `:api` | Generated RPC services and configured transports | Typed API contracts and owned transport close handle |
| `:repository` | Paired stores, synchronization, identity/session owner and capability bindings | Repository contracts, preparation and account lifecycle operations |
| `:usecase` | Application workflows, launch resolution and projections | Use-case contracts and bounded execution entry points |
| `:viewmodel` | Destination factories, root mapping, scoped navigation and child ownership | Curated viewmodel factories and shared presentation coordination |
| `:client` | Forward supplied dependencies, own runtime transitions and export host-facing contracts | Client, startup, host attachment, headless work and shutdown |

Use constructor injection and module-local kotlin-inject components. Bind supplied
provider instances explicitly; do not construct hidden duplicate SDK adapters in
DI bindings. Scope components according to their actual owners. An annotation is
not a substitute for a close protocol or job ownership.

```text
Platform root
  → Client.create(configuration, providers, execution)
    → viewmodel module factory
      → usecase module factory
        → repository module factory
          → API transport and storage factories

Returned handles carry contracts and explicit lifecycle operations upward.
Feature dependencies and action calls follow the prescribed downward chain.
```

Separate construction from activation. Construction validates immutable
configuration and creates owned handles; preparation opens/migrates persistence;
activation starts observers/workers allowed for the current access mode. Creating
a screen or resolving a DI binding must not unexpectedly start another socket,
ask permission or launch interactive authentication.

Supply dispatchers/executors, an owned coroutine parent, wall clock, monotonic
time/scheduling, transport configuration and storage configuration at construction.
Keep IDs wrapped in configuration, factory arguments and registries. Use concrete
`ClientInstanceId`, `AccountId`, `PresentationHostId`, `LaunchAttemptId` and
`NavigationEntryId` types; generations/revisions also have named semantic types.
Avoid globals, mutable service overrides and unowned `GlobalScope` jobs.

## Define scopes and resource ownership

| Scope | Owns | Must survive | Must end on |
| --- | --- | --- | --- |
| Application/client | Prepared infrastructure, session owner, app preferences, app-wide observers | Ordinary host remount and warm resume | Explicit client shutdown |
| Account | Account-bound repositories/caches, use cases, authenticated transport subscriptions | Destination changes and ordinary credential refresh | Logout, account retirement or client shutdown |
| Destination/entry | Viewmodel, child registry, drafts, collectors, timers and result waits | Rerender/recomposition of that entry | Pop, replacement or account retirement |
| Host/scene | Presentation attachment, navigator adapter and view binding subscriptions | Allowed view updates | Host detach/replacement |
| Attempt/work item | Startup attempt, credential interaction or bounded background action | Its explicitly supported suspension | Completion, cancellation, expiry or owner retirement |

Host scope and destination scope need not have identical lifetimes. A recreated
Activity can reattach to a retained entry. Conversely, a popped entry must close
even if its scene remains alive. Keep jobs under an explicit owning parent and
document supervisor/error reporting policy; supervised failures still need recovery.

Scopes describe ownership, not a requirement to inject a new scope into every
viewmodel. Viewmodels are driven through pure state updates or directly expose
`Flow<State>`. Cold upstream work belongs to whoever collects/binds that flow:
collection starts it, and cancellation stops it. An action similarly runs in its
caller's coroutine. Prefer direct Flow composition and structured child work over
constructor-launched collectors. `stateIn`/`shareIn` are explicit exceptions: their
producer belongs to the supplied scope. A stateful VM's list properties are directly
composed DeltaList pipelines, with no driver collecting list copies into state. See the
complete
[state and binding examples](viewmodels-state-and-flows.md).

Record whether each resource is owned or borrowed. The owner closes it exactly
once. Client shutdown closes client-owned transports/storage/provider registrations;
host-owned SDK objects remain the host's responsibility. A child view releasing
its binding must not close an account-wide repository. See the provider and child
guides for presentation delegates and collection leases.

Factories must clean up partially constructed graphs. Track successful allocations
and close them in reverse dependency order if a later allocation fails. Register
cleanup before starting work. Do not rely on DI containers or process exit to
release databases, observers, sockets or pending result channels.

## Make preparation and activation idempotent

For one owned client instance, concurrent preparation callers share one attempt
and outcome. Do not open a second database, rerun migrations concurrently or attach
duplicate app observers. Model explicit states such as constructed, preparing,
prepared, closing and closed; define how a failed preparation can retry safely.

Preparation registers storage schemas, opens delegates, applies required migrations
and loads application preferences/session metadata. It does not certify server
authorization or construct protected screens. Readiness means those dependencies
are usable; a failed migration is not an empty successful database.

Activation starts only the services allowed by the resolved access mode. App-level
identity observation may start before an account exists; protected subscriptions
start after an eligible account graph exists. Offline reading starts local
observations without waiting for a successful socket handshake.

Use no application locks, mutexes or semaphores for lifecycle coordination. Confine
lifecycle decisions to their owning coroutine/executor, represent phases in state,
and use structured jobs plus generation checks to reject stale completions. Keep
state transitions short and pure. Network refresh, provider presentation, database
migration and host installation suspend outside transitions, so cancellation,
shutdown and host replacement remain responsive. An owner can share one in-flight
job/Deferred explicitly; merely marking a function `suspend` does not serialize calls.

## Resolve launch through use cases

The shared launch use case consumes the repository-owned session result and typed
incoming intent. It returns application access/entry state, not screen classes,
native UI objects or `NavigatorArgs`:

```kotlin
sealed interface LaunchResolution {
    data class SignedOut(
        val returnDestination: SafeReturnDestination?,
    ) : LaunchResolution

    data class Authenticated(
        val accountId: AccountId,
        val entry: AuthenticatedEntry,
    ) : LaunchResolution

    data class OfflineRead(
        val accountId: AccountId,
        val entry: CachedEntry,
    ) : LaunchResolution

    data class RecoveryRequired(
        val failure: StartupFailure,
    ) : LaunchResolution
}
```

The viewmodel/navigation coordinator maps this resolution to a typed root and
entry arguments. The client facade composes that coordinator through the owned
factories. Use cases never import destinations merely to choose the next screen.

| Observed condition | Recommended resolution |
| --- | --- |
| No session, explicit logout or confirmed revocation | Signed out; retain only permitted safe-return context |
| Usable server-verified session | Authenticated account graph and permitted entry |
| Required server/profile prerequisite | Authenticated prerequisite entry; do not misclassify as signed out |
| Inconclusive network failure with eligible retained cache | Explicit offline-read mode, under the declared offline policy |
| Locked secure storage or recoverable local access failure | Recovery/unlock/retry; do not erase the session as a default fallback |
| Persistence corruption or failed preparation | Typed startup recovery; no protected root or successful-empty state |
| Incoming link/notification | Decode, resolve session and validate resource/access before presenting |

Token presence, cached profile identity and readable local key material do not
prove current authorization. Distinguish definitively unauthenticated from unable
to determine authentication. A transient refresh failure must not silently become
logout. Adopt sessions only after the authoritative authentication workflow verifies
them; native SDK success alone is insufficient.

The prototype default permits eligible cached reading and requires authenticated
online execution for writes. It has no deferred offline command queue. Offline
mode makes uncached reads and unavailable actions explicit; cached identity does
not claim current server authorization. Known logout/revocation disallows retained
protected reading. Any expanded offline policy needs its own consistency and
security contract.

## Install the root before revealing it

```text
Host attaches and displays a launch cover
  → prepare shared runtime
  → resolve session and incoming intent
  → prepare the selected account/access graph
  → map resolution to typed root
  → ask the current host to install root
  → await installation acknowledgement
  → reveal that host's installed UI
```

A host-facing contract can make the acknowledgement explicit:

```kotlin
interface StartupHost {
    val hostId: PresentationHostId

    // Returns only after installation; throws on failure or cancellation.
    suspend fun installRoot(request: RootInstallation)

    suspend fun reveal(installationId: RootInstallationId)
    suspend fun showRecovery(failure: StartupFailure)
}
```

`RootInstallation` carries typed root data, installation identity and validity
context. Its IDs are canonical wrappers. This contract is application-owned;
current fire-and-forget platform navigator delivery is not an installation
acknowledgement. Execute native presentation on the required UI executor and
acknowledge actual installation, not enqueueing a callback.

Fence every install/reveal with launch attempt, account generation and host
attachment identity. If any is obsolete, discard the completion and close its
unadopted candidate graph. An obsolete host must never reveal another host's UI.
Multiple scenes share session policy but acknowledge their own roots independently.

Ready means a usable root is installed. It does not mean all lists, images or
feature data have loaded; destination state handles that loading. A pre-install
failure leaves a usable recovery surface with bounded retry, not an endless cover
or a brief flash of a protected screen. Recovery presentation must work even when
normal persistence or the account graph could not be constructed.

Sign-in is created only when resolution requires interaction. The OS launch screen
and app cover are not sign-in viewmodels. No startup path requests notification
permission, opens a credential sheet or executes an incoming consequential action
without the corresponding user/workflow authorization.

## Serialize authentication and account transitions

IdentityRepository is the session owner. The shared coordinator observes its typed
session transitions through use cases; sign-in screens do not separately push home
on each platform. An ordinary credential refresh updates the current account graph
without replacing the navigation root. A meaningful access/account transition can
require root replacement.

The default is one active account. If account switching is added, retire the old
account before accepting the new one; do not overlap private caches/subscriptions
under an ambiguous active-account variable. A transition follows this protocol:

1. Allocate a new typed transition/generation and fence prior completions. Reject
   new actions against the retiring graph.
2. Detach protected presentation and cancel old entry/result/child work. Coordinate
   host removal so back/history cannot reveal the previous account.
3. Stop account subscriptions/workers, retire account caches/checkpoints and apply
   the transition's session-persistence cleanup policy.
4. Resolve and construct the eligible next graph through the same factories.
5. Install its typed root on current hosts, acknowledge and reveal.

Callbacks, repository publications, network replies and queued UI calls check the
generation that authorized them. Cancellation alone is insufficient: an external
effect may already have completed, and an SDK callback may arrive after cancellation.
Reconcile accepted commands using operation identity and authoritative state; never
let old-account completion populate the new graph.

Logout must immediately make the local protected graph unusable, even if remote
revocation is unreachable. Attempt server revocation under an explicit bounded
policy, clear local session material and report unresolved remote invalidation
appropriately; do not retain a usable local credential solely to retry revocation.
Retire account data and private image caches. Declare which non-account application
preferences survive; the prototype restores no old drafts or native stacks.

Coordinate other tabs, workers and database handles before destructive account
cleanup. Use an explicit durable cleanup marker if interruption can otherwise
leave resumable protected metadata; next preparation completes that cleanup before
session resolution. Cross-runtime notification is a hint, not the sole safety
mechanism. Each runtime independently checks session/account validity. Never delete
shared data underneath a still-active owner without a coordination protocol.

## Distinguish cold launch, warm resume and host replacement

The default fresh-process behavior is normal startup without restoring prior native
stacks, sheets, drafts, filters or scroll positions. Explicit incoming intents and
the browser's current address still pass through normal resolution. Restoring data
caches is distinct from restoring UI. A product that restores UI must define
versioned, minimal metadata and revalidation; it cannot restore live responders,
coroutine scopes or viewmodel instances.

Warm foreground resume preserves the allowed graph and navigation. Revalidate
session/resources and reconnect owned subscriptions under shared policy. Do not
repeat migrations or replace the root for every visibility event. Coalesce noisy
resume/connectivity events so they cannot create duplicate refresh loops.

Activity recreation, scene attachment and React remount reattach presentation to
the owned runtime. They do not implicitly construct a second client. A host detach
removes its callbacks and binding subscriptions; whether it closes retained entries
is a declared restoration policy. React effect cleanup and development remounts
must follow the same explicit owner model rather than global singleton shortcuts.

| Host | Integration responsibility |
| --- | --- |
| iOS | Retain the app runtime, attach each scene, install on main actor, acknowledge root and resolve interactive presenters lazily |
| Android | Retain runtime across supported Activity recreation, reattach lifecycle-aware host, clear obsolete protected stacks and qualify restoration codecs |
| Web | Establish runtime outside route rerenders, gate initial route mounting, coordinate history/account transitions and dispose owned handles on runtime retirement |

## Provide headless entry points without screens

Expose bounded client work entry points that reuse preparation, session policy,
use cases and repositories through module-owned factories. A worker/background
export must not import or initialize DOM/React, create a navigator, instantiate
screen viewmodels or request interactive authentication.

```kotlin
sealed interface HeadlessOutcome {
    data object Completed : HeadlessOutcome
    data class NeedsForeground(val intent: ForegroundRecoveryIntent) : HeadlessOutcome
    data class Retryable(val failure: WorkFailure) : HeadlessOutcome
    data class Rejected(val reason: WorkRejection) : HeadlessOutcome
}
```

The work request has typed identity, account/resource context, deadline and operation
identity where required. Missing/locked session access returns a defined result;
it does not open a login sheet. No preexisting socket is required. Use worker-safe
storage/transports, short-lived execution ownership and explicit session strategy
appropriate to the environment.

Within one runtime, reuse an eligible existing graph through an owned work lease.
In a separate process/worker, construct the same infrastructure factories under
that runtime's own lifetime and coordinate persistent writes with other handles.
Do not share in-memory scopes across runtimes or assume all worker environments
can restore native secrets or browser sessions identically.

Observe platform work deadlines and cancellation. Durable operation records and
server idempotency handle interruption; an OS callback is not a guarantee of
unlimited execution. Return bounded foreground recovery context, and preserve
review intent without replaying a stale user decision.

## Shut down and retry with bounded cleanup

Shutdown is idempotent and excludes new work. Fence generations, detach hosts,
cancel entry/account work, join owned jobs under deadlines, stop subscriptions,
flush required durable records and close owned storage/transports/providers in
dependency order. Release borrowed resources only through their owner's contract.

If cancellation interrupts teardown, use a bounded cleanup context for essential
release; do not shield an entire network-dependent shutdown indefinitely. Report
cleanup failures while continuing independent releases. A closed runtime rejects
new operations; restarting means creating a new owned instance rather than
reviving closed handles.

Preparation retry, launch-resolution retry and host-installation retry are distinct.
Retry only the failed stage whose dependencies remain valid. A network retry need
not reopen storage; an installation retry may reuse the resolved graph if access
and host generation still match. Retire unused candidate graphs on failure and
avoid accumulating listeners or duplicate session observers across retries.

## Acceptance tests

- Concurrent prepare/start calls share one preparation and create one eligible
  graph; partial factory failure closes every successfully allocated owned handle.
- Cold launch resolves signed-out, authenticated, prerequisite, offline-read and
  recovery states through the same production factories.
- Gate host acknowledgement: no ready/reveal or protected screen precedes it.
  Fail installation, detach the host and deliver stale acknowledgements to prove
  they cannot reveal obsolete roots.
- Distinguish revoked session from network/secure-storage failure. Verify native
  secret restoration and browser cookie-session restoration independently.
- Login/logout/account transitions fence late replies, provider callbacks and
  queued UI work; back/history cannot expose an old protected graph.
- Warm resume/host recreation retains eligible navigation and does not duplicate
  migrations, clients, sockets or observers. Qualify actual native/browser hosts.
- Interrupt logout cleanup and restart against persistent storage; stale account
  metadata cannot bypass cleanup. Exercise multiple tabs/workers/handles.
- Run headless work without any presentation host or DOM; verify needs-foreground,
  deadlines, interruption recovery and independent runtime ownership.
- Dispose after success, failed assertion and cancellation; assert owned jobs,
  result waits, leases, storage and transports release under bounded deadlines.

Use the [testing guide](testing.md) for controlled clocks/gates, complete listening
servers, multi-client viewmodel journeys and durable restart qualification.
