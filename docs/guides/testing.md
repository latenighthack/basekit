# Testing multiplatform applications end to end

Test the shared application through real repositories, use cases, viewmodels and
generated navigation, then test the same graph against a listening Kotlin server.
Run multiple independent clients against that server. Verify durable storage,
restart, failure recovery and actual platform bindings in dedicated lanes.

This guide expands the [architecture](../multiplatform-architecture.md). All test
contracts follow the [ID rules](ids.md), [passive-store ownership](storage-and-repositories.md)
and [DeltaList contract](delta-lists.md). Application harness/fixture names below
are proposed helpers, not existing Basekit APIs. Code fragments illustrate test
structure; they do not constitute an implemented test suite.

## Layers and the evidence they provide

| Layer | Real code under test | Controlled boundary / evidence |
| --- | --- | --- |
| Contracts | ktbuf generation, canonical IDs, serializers | Compatibility fixtures, negative compilation and generated target compilation |
| Stores | Typed stores, byte codecs, indexes and migrations | Memory plus actual SQLite/IndexedDB/PostgreSQL; commit/reopen/rollback/upgrade |
| Repositories | Cache, read-through, acceptance and publication | Gated passive stores/APIs/providers; versions, coverage, shared observations |
| Use cases | Workflows and observation composition | Repository fakes; decisions, partial success and cancellation |
| Viewmodels | Real specs/implementations and children | Controlled use cases, navigation recorder; drafts, guards, errors and identity |
| Client journeys | Production factories, repositories/use cases/VMs, startup | Scripted external boundaries and generated test navigator |
| Server services | Actual service modules, rules, stores and registration | Isolated providers; request context, permissions, revisions and receipts |
| Network journeys | Listening Ktor server + generated RPC clients + complete client graphs | HTTP/WebSocket framing, live changes and multiple clients |
| Durable/process journeys | Actual server executable and durable database | Startup/configuration, process death, restart, migrations and worker recovery |
| Platform hosts | Generated bindings, actual navigator and provider adapter | Native/browser lifecycle, row leases, arguments, UI execution and secure storage |

Passing one row does not prove another. An in-process handler test does not prove
engine/network behavior. A real listening server using in-memory storage does not
prove durability. A fake authentication provider does not verify its native SDK or
external verifier. Headless navigation does not prove browser/native presentation.

## Test project ownership

```text
project/
  api/src/commonTest/                 # Codecs and generated contracts
  repository/src/commonTest/          # Controlled store/API repository tests
  repository/src/jsTest/              # Actual IndexedDB/browser behavior
  repository/src/androidInstrumentedTest/
  repository/src/iosSimulatorArm64Test/
  usecase/src/commonTest/
  viewmodel/src/commonTest/
  client/src/commonTest/              # Construction/startup/disposal
  server/src/test/                    # Scoped services and server stores
  server/test/                        # Listening-server and durable harness
  server/run/                         # Actual executable exercised by process tests
  test-support/                      # Typed fixtures, fakes, controls, assertions
  integration-tests/src/jvmTest/      # Real graphs, multi-client journeys
  ios|android|web/                    # Host/provider/design/museum test targets
```

Configure executable targets, not just directories. Keep test-support and generated
test navigator dependencies out of production packaging. Compile generated API and
Basekit bindings before consumers. Separate JVM fast/network tests from real browser,
Android emulator and Apple simulator execution; report compilation and runtime
verification distinctly.

## Deterministic controls

Use deferred gates to distinguish operation entry from completion:

```kotlin
class ControlledCall<Input, Output> {
    val entered = CompletableDeferred<Input>()
    private val result = CompletableDeferred<Output>()

    suspend fun invoke(input: Input): Output {
        check(entered.complete(input)) { "Unexpected duplicate call" }
        return result.await()
    }

    fun succeed(value: Output) { check(result.complete(value)) }
    fun fail(error: Throwable) { check(result.completeExceptionally(error)) }
}
```

Imports are standard coroutine APIs. This gate controls one expected call; queue
controls for several operations and fail on unexpected calls. Gate before the
transaction or at a test adapter boundary, not by suspending arbitrarily inside a
restricted database transaction.

Use one test scheduler for pure coroutine component tests. Inject clock/randomness
where policy depends on them. Observe explicit state/phase signals rather than
sleeping or repeatedly guessing when an action completed. Record typed values and
bounded redacted state for timeout diagnostics.

Real networking, child processes and databases use wall-clock readiness/deadlines.
Run the fixture supervisor on real dispatchers. Do not put a five-second wait for
external I/O solely on a virtual scheduler that can advance past it before the
external system responds. Virtual time remains useful for isolated retry/expiry
policy tests; distinguish it from integration clocks.

Await positive conditions with bounded subscriptions. For absence/authorization,
assert a protocol denial or inspect an authoritative snapshot after an explicit
reconciliation barrier. A short delay with no observed event is weak evidence that
private data was never exposed.

## Repository and use-case tests

The [use-case/mutation-workflow guide](use-cases-and-mutation-workflows.md) defines
whole-flow reuse, grouped capabilities, operation identity and partial-outcome
contracts to exercise here.

A repository write test seeds a passive store, starts observation, gates the write,
then checks persistence and publication ordering:

```text
Observe committed original content
  → invoke repository command/acceptance
  → await write gate entry
  → assert memory still reflects original committed content
  → complete commit successfully
  → await accepted new state
  → assert store, memory, membership and checkpoint agree
```

Repeat with failed commit: no committed publication, previous content retained,
typed failure surfaced. Seed stores before repository construction or drive public
repository updates. Do not mutate a store and expect a fabricated notification.
Test optimistic overlays separately from committed content.

Cover a disk read held behind a newer live update, duplicate observers sharing
one read/subscription, query replacement, response/live-update convergence,
eviction, partial coverage and account fences. Use typed IDs and versions even in
fixtures. Tests that merely assert one delegation call add less value than tests
of decisions, sequencing and recovery.

Use-case tests inject repositories, not stores or wire transport. Exercise
multi-repository branching, partial remote success, recovery and cancellation.
Verify no local transaction is mistaken for remote atomicity. Provider interaction
is tested at its consuming repository/service boundary; native adapters have their
own platform tests.

## Viewmodel components and navigation

Begin with the [complete viewmodel testing guide](viewmodel-testing.md). It includes
compiled search/filter implementations using both `StatefulViewModel.update` and a
direct `Flow<State>`, plus the full shared test suite for DeltaList mutations, text
changes, item/empty-child actions, source edits, cancellation and recollection.
Lists stay in their DeltaList properties; tests bind those streams and their real
children. Do not test a copied result array or a list getter in parent state.

Viewmodels are state-driven coroutine/Flow components: no locks, mutexes or
semaphores. Tests act as binding callers and own collection in `backgroundScope`;
use controlled sources and `runCurrent`, then cancel/join and assert upstream cleanup.
A cold flow needs an active collector to run. Stateful updates can happen without
one; their latest value replays when bound. `initialState` is only the synchronous
construction fallback. An explicitly shared hot producer needs its own declared
owner; cancelling a binding does not stop unrelated
repository work. Assert these distinct lifetimes rather than assuming a private
scope exists in every viewmodel.

Use the [child-viewmodel](child-viewmodels.md) and [navigation](navigation.md)
guides to define the identity, disposal, result and host contracts under test.

Use real viewmodels and meaningful real child implementations. Control observations
and commands through use-case dependencies. Verify initial state, draft mutations,
validation, eligibility, pending state, duplicate action guards, success/failure,
retry and cancellation. Failure must not produce successful navigation.

Observe children through the actual DeltaList contract. Assert concrete empty/error/
selection variants and invoke their real actions. Updating one child must retain
unrelated child identity. Moves preserve owned selection/drafts; disposal cancels
owned jobs without stopping shared repositories.

Enable Basekit's generated navigator in an appropriate test build:

```kotlin
ksp {
    arg("Basekit_NavigationPackage", "example.navigation")
    arg("Basekit_GenerateTestNavigator", "true")
}
```

Implement the generated registry by delegating to production factories. Adding a
destination should require a registry factory at compile time. Do not maintain
an independent route switch or return mocked viewmodels for full journeys.

`awaitViewModel<T>()` matches the latest recorded destination and constructs an
instance through the registry. It is not a screen-instance cache and does not
consume the event. Retain its returned viewmodel; calling it repeatedly to read
state can construct extra instances. An earlier destination can also satisfy a
wait if no new event position is checked.

The application harness should therefore track a history position before each
navigation action, wait for a matching event after that position, and construct
each destination once with explicit lifetime ownership. A scope is needed for
entry-owned work; a directly exposed cold Flow uses its binding's coroutine. Cache
by an explicit typed entry identity inside the harness, not by destination type.
Preserve exact generated edge/source
enums, typed args, result responder and context. Handle multiple synchronous
navigation events without dropping intermediate destinations.

For result flows, launch the requesting action in an owned coroutine, await the
child entry, invoke the child's real selection/confirmation action and await the
requester's result. Also test dismissal/null, duplicate result, nested flows and
caller cancellation. Focused responder tests can invoke a responder directly;
full journeys should use the real child action.

The navigator recorder is not a native/browser stack or lifecycle manager.
Closing a recorded entry does not itself prove scope disposal, back behavior,
history replacement, focus or root-installation acknowledgement. Test those
responsibilities in the harness/host that owns them.

## Production-graph client harness

Build each test client through the production `:client` construction path, injecting
external boundaries rather than replacing internal application layers. The fixture owns:

- Provider fakes with explicit completion/cancellation/failure behavior.
- A distinct session/cookie/secure-storage context and typed test identity.
- Its own local store/database namespace, memory caches and account generation.
- Its own transport connection, application/account/destination scopes and navigator.
- Startup preparation/root-installation/ready barriers and a generation-aware host.
- Disposal that cancels/joins work, releases children/transports and closes storage.

Expose signed-out launch, after-prepare fixture seeding, restored cached account,
headless launch, offline transport, reconnect and restart helpers. An authentication
fixture seeds session material at an explicit boundary; it does not replace the
shared root resolver or silently certify native authentication.

Use scoped fixtures with suspending cleanup on success, failed assertion, cancellation
and partial construction failure. No global service overrides or shared mutable
delivery inboxes. If client A must stay alive while B runs, keep A inside the outer
fixture scope rather than disabling disposal or relying on the JVM to eventually exit.

## Spin up a complete listening server

Provide an application-owned server fixture that uses production service composition:

1. Allocate isolated configuration, storage namespace and provider dependencies.
2. Construct `ServerCore` with the chosen store/database owner and shared telemetry.
3. Prepare/migrate all core and extension stores before serving requests.
4. Construct the real scoped service modules and explicit installed extensions.
5. Start component/extension workers under fixture-owned scopes.
6. Bind a real Ktor engine to loopback with port `0` and read the resolved port.
7. Await application readiness: prepared dependencies, registration and required workers,
   not just a successful TCP connection.
8. Create actual generated ktbuf clients using independent RPC transports.
9. On exit, close clients, stop intake/components/workers, stop the engine, close storage
   and delete isolated resources in reverse construction order.

The ktbuf `TestServer` can provide an embedded listening engine with an OS-assigned
port. The application still owns production composition and lifecycle cleanup.
If using a convenience run helper, verify its failure path: a runner exception must
still stop the engine/components. Use `try/finally` or a fixture that guarantees it.
Do not copy a success-only cleanup path into the application harness.

Use the resolved loopback address to construct a typed endpoint; never guess a free
port by briefly opening/closing a socket before starting the actual server. A child
service should itself bind port `0` or use another reservation/handshake that retains
ownership through bind. Validate endpoint normalization against the selected transport.

An in-process Ktor test engine remains useful for route/authentication checks but is
a separate test mode. The listening-server lane exercises actual HTTP/WebSocket
framing, cancellation, upgrade behavior and concurrent clients. A fixture that
replaces service implementations with stubs is a transport test, not a complete
application-server journey.

## Multi-client journeys through real viewmodels

Run at least these actor arrangements:

| Clients | Purpose |
| --- | --- |
| Two devices for one account | Accepted changes converge; session/local cache isolation |
| Two authorized accounts sharing one resource | Collaboration, version conflicts and shared membership |
| One authorized and one unrelated account | No cross-account data or authority leakage |
| Foreground and headless/worker contexts | Shared persisted receipts with independent memory/lifecycle |

Every client has independent transport/session, stores and scopes, even when they
share an account. Sharing one repository instance between clients would bypass the
network and conceal synchronization defects. Use only domain-authorized shared
resources on the server.

The following journey pseudocode uses proposed application harness APIs. The helper
names establish responsibilities; implement them with production factories,
generated navigation and bounded real-time waits before treating it as executable:

```kotlin
@Test
fun renameConvergesAcrossTwoClients() = runListeningJourney {
    withServer(ServerFixtureConfig.durable()) { server ->
        withClient(server.endpoint, Fixtures.ownerDeviceA()) { a ->
            withClient(server.endpoint, Fixtures.ownerDeviceB()) { b ->
                a.startAndAwaitReady()
                b.startAndAwaitReady()
                val itemsA = a.destinations.awaitNewRoot<ItemsViewModel>()
                val itemsB = b.destinations.awaitNewRoot<ItemsViewModel>()
                val itemId: ItemId = Fixtures.sharedItemId
                val rowA = a.rows.awaitItem(itemsA.rows, itemId)
                val rowB = b.rows.awaitItem(itemsB.rows, itemId)
                b.awaitResourceReady(Fixtures.sharedItemsResource)

                val position = a.destinations.position()
                rowA.open()
                val editor = a.destinations.awaitAfter<EditItemViewModel>(position)
                editor.setTitle("Updated")
                editor.save()

                a.rows.awaitTitle(itemsA.rows, itemId, "Updated")
                b.rows.awaitTitle(itemsB.rows, itemId, "Updated")
                assertSame(rowB, b.rows.retainedItem(itemsB.rows, itemId))
                server.assertOneAcceptedRename(itemId)
            }
        }
    }
}
```

Pre-provision a resource through an explicit typed fixture/API when testing rename;
also include separate journeys that create/share it through real UI actions. Keep
fixture preparation out of the behavior being claimed. The receiver must confirm
subscription/resource baseline readiness before relying on transient live notices.
Then test the opposite ordering too: a client attaching after the write gets the
current authoritative snapshot without requiring another write.

After A's action, assert A's visible state, B's visible state and server receipt/
revision. One-sided assertions can miss duplicate effects or lost remote publication.
Verify row identity and navigation results where meaningful. Do not assert every
transport event count when correct resync can produce extra snapshots; assert
accepted effects, versions and final coherent content instead.

### Required multi-client failure cases

- Both clients edit the same expected revision: one accepted outcome, one typed
  conflict, no silent overwrite and recovery through authoritative content.
- Response and live update arrive in either order, including duplicate/out-of-order
  delivery; neither regresses state or duplicates a command effect.
- B disconnects, A changes data, B resumes and reconciles without polling per change.
- B attaches late or replaces its query; initial state is complete and collector-valid.
- Authority is revoked while B is subscribed: service denial and resource invalidation
  prevent further private content, including stale in-flight work.
- A logs out while disk/network work is gated, then another account logs in: releasing
  the gate cannot populate the new graph or revive protected navigation.
- One client disposes while another observes: the survivor continues and the disposed
  client releases its connections/leases/jobs.
- A command is accepted but its response is lost: use the same operation identity
  for outcome lookup; one durable effect, not a newly issued command.
- Server succeeds but client persistence fails: reconcile accepted outcome rather
  than claim rollback or queue another write.
- Independent clients keep independent drafts/selection while domain content converges.

## Durable server, database and process tests

Run the same principal journeys with a real PostgreSQL test database and real native/
browser client stores in their target lanes. Allocate a per-test database/schema or
an explicitly isolated reset protocol. Never use a production connection. Apply
actual migrations; retain the database across the restart interval being tested.

A durable embedded-server restart test proves reconstructed services/stores, but not
the executable's packaging/environment behavior or loss of process-local state.
Include a process lane that launches `:server:run` as built, passes fixture-owned
configuration, awaits readiness, drives generated clients, terminates/restarts it
and verifies persisted state with new graphs/transports. Crash recovery tests use
an abrupt termination rather than only graceful shutdown.

The fixture supervisor owns subprocesses and any additional real service required
by a principal workflow. Capture bounded output concurrently to avoid pipe deadlock.
Fail early if a child exits during readiness. On timeout/failed assertion, retain
redacted diagnostics, terminate gracefully, force termination after a deadline,
join output drains and clean isolated files/database resources. Do not leave a
daemon or disable teardown to keep nested watchers alive.

Verify:

- ServerItem/receipt/outbox protobuf records and indexes survive process restart.
- State + receipt/outbox atomicity under failed transaction and concurrent writers.
- Worker claims recover under their expiry/fencing policy; uncertain external effects
  are reconciled and not blindly replayed.
- Commit-to-publication interruption recovers via snapshot/reconciliation.
- Projection generations restart safely; stale patches request resync.
- Migration from old database/record/index fixtures succeeds; failure preserves old data.
- Client close/reopen hydrates cached content before refresh, with honest coverage.
- Logout erases the selected local data and clean login works without process restart.

Real external provider processes strengthen the workflows that require them, but
keep controlled-provider journeys as deterministic baseline coverage. Report which
boundaries are real in each lane. Passing fake-provider tests is not live-provider
qualification.

## Fault injection and diagnostics

Expose per-fixture controls at actual boundaries: passive store failure/delay,
provider call gate, before/after server commit, publication interruption, response
drop, socket close, duplicate/reordered read updates and expired resume baseline.
Do not bypass repository acceptance to manually push successful UI state.

Record phase transitions and typed operation/resource/version context. On failure,
include last VM state, latest navigation suffix, row IDs/size/load status, pending
gates, transport state, worker claims and relevant redacted server logs. Never print
tokens, private proofs or entire stored protobuf payloads.

Retry policy is the behavior under test, not a reason to rerun failing tests until
they pass. Expected transient faults should produce deterministic recovery assertions.
Test fixture construction and cleanup failures as first-class cases too.

## Startup, headless and platform qualification

The [composition/startup/lifecycle guide](composition-startup-and-lifecycle.md)
defines preparation, host acknowledgement, generation fencing and cleanup
contracts to exercise through this harness.

Use the same shared startup resolver in production and tests. Assert prepare →
resolve → install root → ready, with root installation held behind a deferred
acknowledgement. Signed-out startup constructs no authenticated screen first.
Offline cached reading is distinct from revoked/invalid session and storage failure.
Warm resume preserves valid navigation; cancellation/stale host callbacks cannot reveal
another generation. Headless startup creates no screen/navigator or interactive login.

Browser tests use actual generated JS bindings, cookie/origin protections, upgrade
behavior, IndexedDB and service-worker lifetimes. Test a worker while application
pages are closed, then reopen the page and reconcile from persistence. Native tests
use actual SQLite, provider adapters, actor/UI dispatch, back/result presentation,
Android recreation and Swift empty-container list startup.

Museums verify reusable design and DeltaList adapters with deterministic fixtures.
Product-host tests prove their integration with routes and lifecycle. Snapshot updates
are reviewed visual changes, not automatic proof of correctness.

## Execution lanes and completion

| Lane | Required execution |
| --- | --- |
| Fast shared | Contracts, IDs, repository/use-case/VM tests and DeltaList oracles |
| JVM server/network | Real service composition, listening engine, two-client journeys |
| Durable/process | PostgreSQL, actual server executable, restart/migration/claim recovery |
| Browser | Real bindings/IndexedDB/worker/history/session and socket behavior |
| Android | Emulator persistence, bindings/providers, recreation and navigation |
| Apple | Simulator persistence, generated Swift bindings, actor/lifecycle behavior |
| Design/museum | Independent target builds plus selected interaction/accessibility/render checks |

Application command names depend on its Gradle/workspace setup. Provide reproducible
wrappers for these lanes and explicit prerequisites. Do not present illustrative
target paths as commands that already exist. Pin versions and build generated outputs
before consumers. Keep fixtures deterministic and all mutable resources isolated.

A complete report names which layers and boundaries ran, their backend/process mode,
and passed/failed/skipped/blocked results. Compilation, fixture tests, network tests,
durable restart and real platform execution are separate evidence. Completion requires
principal multi-client journeys through real viewmodels, full listening-server startup
and verified teardown, alongside the layer-specific conformance checks.
