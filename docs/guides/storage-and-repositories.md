# Protobuf storage and repositories

Repositories own reactive content and synchronization; stores are passive typed
persistence. Both client and server persist generated protobuf records through
ktstore using explicit indexes and codecs. This guide expands the
[architecture](../multiplatform-architecture.md) and uses the mandatory
[ID-wrapper rules](ids.md).

The Kotlin snippets illustrate application-owned contracts and the ktstore class
pattern. Generate their protobuf types and compile them against pinned libraries
before using them as implementation code.

See [use cases and mutation workflows](use-cases-and-mutation-workflows.md) for
whole-flow reuse, cross-repository orchestration and uncertain remote outcomes.

## Ownership and feature structure

```text
Generated API result / resource update
  → ItemRepository validates and accepts content
  → ItemStore persists generated LocalItem/page/checkpoint records
  → repository awaits commit and updates memory
  → use cases observe scalar metadata and DeltaList content
  → viewmodels project typed children
```

Keep the feature's persistence together:

```text
project/repository/
  src/main/proto/storage/v1/client_storage.proto
  src/commonMain/.../repository/items/ItemRepository.kt
  src/commonMain/.../repository/items/RealItemRepository.kt
  src/commonMain/.../store/items/ItemStore.kt
  src/commonMain/.../store/items/ItemStoreImpl.kt
  src/commonMain/.../store/database/ClientDatabase.kt
  build/generated/                       # Generated Local* records and codecs
project/server/
  src/main/proto/storage/server_storage.proto
  src/main/.../services/items/v1/ItemStore.kt
  src/main/.../services/items/v1/ItemStoreImpl.kt
```

A repository can own several stores: entities, query/page membership, checkpoints
and operation reconciliation. A stateless repository need not fabricate a store.
Server services use typed stores for authoritative records and may extract internal
repositories for policy; they do not need client-style reactive caches.

Stores perform reads, writes, indexed queries and transactions. They do not emit
Flow/deltas, watch the network, notify repositories or own background refresh.
Repositories explicitly publish after accepted persistence succeeds. Writing a raw
store from elsewhere does not automatically update an existing repository cache.

## Protobuf record design

Separate public messages, persisted wrappers and viewmodel state. Reuse a public
message when its semantics fit, then add local/server metadata in storage schemas.
Do not hand-maintain equivalent JSON or serializable record classes alongside
generated protobuf records.

```protobuf
syntax = "proto3";
package example.client.storage.v1;
import "items/v1/model.proto";
import "common/v1/model.proto";

message LocalItem {
  example.common.v1.AccountId account_id = 1;
  example.items.v1.ItemId item_id = 2;
  example.items.v1.Item item = 3;
  example.items.v1.ItemRevision revision = 4;
  int64 cached_at_millis = 5;
}

message LocalItemPage {
  example.common.v1.AccountId account_id = 1;
  example.items.v1.ItemPageKey page_key = 2;
  repeated example.items.v1.ItemId ordered_item_ids = 3;
  example.items.v1.ItemPageCursor next_cursor = 4;
  bool complete = 5;
  example.items.v1.ResourceVersion baseline = 6;
}
```

The enclosing record's `accountId`, `itemId`, cursor, page key and version are
named values. Validate required fields after decoding. Ordinary repeated fields
are passive snapshots, not observable lists. A page's ordered IDs establish
membership; the existence of an item record does not prove a query is complete.
Specify whether cursor absence, completion and unavailable metadata are represented
by message presence or an explicit union. Scalar defaults must not fabricate coverage.

Page keys include every dimension affecting membership: account, query/filter,
ordering, page input cursor, limit and locale if server presentation depends on it.
Use a versioned canonical composition, not ambiguous delimiter concatenation.
Account namespaces are typed, deterministic and filesystem-safe; tokens and email
addresses are not database names.

Server storage defines `ServerItem`, operation receipts and outbox records in the
server module. Keep server-only material out of client exports. A stored wrapper
does not grant permission to expose private content through RPC responses.

## Typed Store implementation

```kotlin
interface ItemStore {
    suspend fun read(itemId: ItemId): LocalItem?
    suspend fun write(record: LocalItem)
    suspend fun remove(itemId: ItemId)
}

class ItemStoreImpl(delegate: StoreDelegate) : ItemStore, Store<LocalItem>(
    delegate,
    "local_items",
    LocalItem::toByteArray,
    LocalItem.Companion::fromByteArray,
) {
    private val itemIdIndex = mappedIndex(
        IndexName("item_id"), LocalItem::itemId,
        object : StorageCodec<ItemId?, ByteArray> {
            override fun encode(value: ItemId?) = requireNotNull(value).toByteArray()
            override fun key(name: IndexName) = StoreKey.SerializedKey(name.value)
        },
    ).also { primaryKey(it) }

    override suspend fun read(itemId: ItemId) = get(itemIdIndex.eq(itemId))
    override suspend fun write(record: LocalItem) = save(record)
    override suspend fun remove(itemId: ItemId) = delete(itemIdIndex.eq(itemId))
}
```

Imports are omitted: generated records/IDs and their ktbuf codecs, plus ktstore
`Store`, `StoreDelegate`, `IndexName`, `StorageCodec` and `StoreKey`. The example
assumes an account-scoped storage namespace. If multiple accounts share storage,
make account identity part of the primary key and require it in store operations.

The store method and mapped index accept an ID class. The codec's bytes remain
inside the adapter. Choose stable explicit index names, primary keys and encodings.
For nullable secondary values use the nullable index contract; sparse null values
are not equality matches and never valid primary keys. A required generated
message field is checked before storage; do not encode null as an empty ID.

Indexes hold queryable fields beside the protobuf payload. Updating an indexed
value must update both payload and keys coherently. Do not assume SQL can inspect
arbitrary fields inside the protobuf blob. Composite keys have typed components
and explicitly ordered encoding. Do not build application queries from raw key bytes.

## Initialization and backend ownership

One client storage owner constructs/prepares all stores before opening or creating
their backing structures. Client repositories receive those stores through production
factories. Select SQLite on native platforms and IndexedDB in browsers. Use separate
real-backend test execution; a memory delegate proves neither native durability nor
browser transaction behavior.

Ktstore supports delegate-based stores and configured database handles. The legacy
shape prepares stores and invokes `createStores()`. Configured handles declare
stores/indexes/schema migrations, then `open()`; typed stores constructed with that
database participate in declared transactions. Choose one coherent setup path and
verify its supported target versions. Do not mix stores from different handles into
one transaction.

The JVM server executable injects a durable PostgreSQL delegate into its core and
extensions. In-memory stores are an explicit fast-test/dev choice. PostgreSQL server
transaction and migration guarantees must be tested separately from client SQLite/
IndexedDB conformance. Library client conformance is not proof of server durability.

## Read-through and observation

Repositories own live [DeltaList](delta-lists.md) collections and publish accepted
edits after persistence succeeds. Keep that contract through use cases and viewmodels;
do not create whole-list state caches that are repeatedly copied and rediffed for
presentation. Scalar load/freshness metadata uses typed state. Share work by
account/resource/query identity, not by screen instance.

On a memory miss, read through the store, validate account and version, hydrate memory
and expose the content. An online refresh can proceed while cached data remains visible.
An initial empty cache means unresolved, not successfully empty. Distinguish:

| Condition | Observation |
| --- | --- |
| No usable cache, unresolved read | Initial loading |
| Resolved complete query, zero items | Known empty |
| Usable cache, freshness unknown/expired | Content with stale metadata |
| Refresh in progress | Retained content with refreshing metadata |
| Read/refresh failed | Typed failure, retaining usable content |
| Offline with missing/partial coverage | Unavailable/incomplete data |

Fence disk and network completions by account/query generation and resource revision.
A read started before a newer accepted update cannot overwrite it when it finishes.
Deduplicate identical in-flight reads and subscriptions; cancel only when ownership
allows it. Disposing a row must not tear down a shared repository observation.

## Commit and publication

Every synchronization batch follows the same order:

1. Obtain and validate the remote result outside persistence transactions.
2. Serialize acceptance against other writers; check account/query generation and
   exact patch baseline/revision.
3. Atomically persist affected records, membership, ordering and checkpoint.
4. Await successful outer commit, then update memory and publish one coherent result.
5. Acknowledge remote acceptance when the protocol requires durable acceptance.

Hold no network waits, provider calls, child coroutines or arbitrary dispatcher hops
inside configured database transactions. Declare participating stores before entry.
Operation results inside a transaction are provisional until outer commit.
Nested work joins the outer transaction under the backend's contract; it does not
create an independently committed savepoint. A caught nested failure may still
leave the outer transaction rollback-only.

Content and baseline commit together. Saving a replay position without the content
can permanently skip data after restart. Persisting content without membership can
produce incorrect query results. Persisting membership without entities requires an
explicit partial-coverage representation, not a silently missing row.

On rollback, keep committed memory/content unchanged and expose a typed failure.
If commit succeeds but publication is interrupted, rehydrate/reconcile from persistence.
Cancellation alone is not proof of rollback. Use a typed operation identity/receipt to
resolve ambiguous completion. Optimism, when supported, is an explicit pending overlay
with reconciliation; it is not committed content.

## Cross-repository work and server effects

Use cases coordinate repositories through typed operations. For local atomic work
spanning repositories, expose a repository-level unit of work: declare participants,
apply the transaction, then publish coordinated cache changes. Do not give use cases
raw store handles or allow store callbacks to discover affected repositories.

Separate remote commands are separate effects. A local transaction cannot make them
atomic. Surface partial success and recovery explicitly. If a server mutation succeeds
but local storage fails, reconcile its receipt/snapshot using the same `OperationId`;
do not report a server rollback or issue a fresh command automatically.

Server effects requiring atomicity save authoritative state, operation receipt and
outbox in one verified server transaction. External calls run outside it, with durable
claims/idempotency or uncertain outcomes. A convenience delegate fallback or a loop of
`save` calls is not proof of atomicity.

## Pages, projections and eviction

Persist entity versions, ordered membership, continuation, coverage and baseline.
Deduplicate overlapping pages by `ItemId` while preserving authoritative ordering.
Document whether paging replaces or appends content. Failed next-page requests retain
their exact cursor; changing a query starts a new generation. Server cursors and
local indexed-query continuations are distinct types and contracts.

Local indexed queries need an explicit total ordering and stable primary-key tie-break.
Use supported sortable encodings; raw strings or legacy numeric encodings are not
automatically portable ordered indexes. A local continuation is tied to database,
schema, store, index and query dimensions, not an immutable server snapshot.

Retained materialized projections belong to repositories. Track source versions,
invalidation and rebuild rules. Cheap use-case combinations can remain transformations.
Neither kind creates an upward repository dependency on use cases or viewmodels.

Set byte/count/age budgets for memory and persistence. Protect actively observed
entries where practical. Evict coherent units or invalidate dependent membership/
checkpoints and require refresh. Never turn an evicted query into a successful empty
result. Offline reads expose only known cached coverage. The default architecture
does not queue failed writes for reconnect execution.

## Migrations, corruption and lifecycle

Treat protobuf record evolution, database schema version and remote version as
independent. Preserve protobuf field numbers and reserve removed fields. Add an
explicit record-format version/union when decoding semantics require one.

Index names/encodings are persisted contracts. Renaming a Kotlin field does not
authorize an index rename. Changes to ID encoding, primary keys, null conventions
or sort encodings require a data/index migration, even if the protobuf still decodes.
Keep old bytes and old indexes readable during the declared migration path; test
failure without destroying the previous database. Size migrations for real workloads.

Malformed protobuf is corruption, not absence. Distinguish constraint, quota, busy,
blocked, closed, timeout, unavailable and migration failures. Keep payloads and secrets
out of diagnostics. Recovery must not silently erase authoritative data.

Logout fences generations, stops work, closes handles and erases account data under
the selected lifecycle policy. Coordinate page/worker/tab handles explicitly; a
process-local mutex does not coordinate them. Browser blocked deletion/upgrade is a
typed unresolved lifecycle outcome until reconciled. Create new handles after deletion;
closed old handles do not become valid again. Native paths are app-owned and use the
chosen backup/exclusion policy. Secure storage and content persistence do not share
an automatic transaction.

## Acceptance and tests

Use the detailed [testing guide](testing.md). At minimum prove:

- Protobuf/ID round trips and typed index queries; no raw-ID overloads.
- Save/close/reopen persistence, uniqueness, ordering and bounded queries on each backend.
- Atomic multi-store content/membership/checkpoint changes and failed outer commit.
- Migration success, migration failure, old-record decoding and changed key encodings.
- Two observers share one load/subscription; writes publish only after commit.
- Delayed disk reads and late remote pages cannot replace newer or another account's data.
- Response and live update converge without duplicate effects or regressing versions.
- Eviction/cold restart retain honest coverage and recover invalid checkpoints.
- Real PostgreSQL server persistence survives restart and verifies its own transactions.
- Worker/tab/foreground reconciliation, cancellation near commit and logout in process.

Store tests do not invent notifications. Seed before repository construction or drive
updates through repository operations. Repository tests use delayed/failing passive
stores; backend tests use real databases. Both are needed to prove the whole pattern.
