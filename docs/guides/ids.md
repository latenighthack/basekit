# IDs and semantic types

Every identifier in a Basekit application is a concrete nominal ID class. This
guide applies to public protobuf contracts, stored records, repositories, use
cases, viewmodels, navigation, server services, providers and tests. Raw strings
and byte arrays are serialization details, never alternative application IDs.

Read this with the [architecture](../multiplatform-architecture.md),
[storage guide](storage-and-repositories.md) and [DeltaList guide](delta-lists.md).
Examples describe application-owned contracts; they are not a compiled starter.

## One type for one identity

Define `AccountId`, `ItemId`, `OperationId`, `SubscriptionId` and other IDs as
separate classes. Each identity has exactly one canonical owner. Every layer uses
that same type; crossing a network, persistence or platform boundary does not
justify another class for the same identity.

An ID class is required even when the value is opaque, comes from a provider,
already looks encoded, or is used only as a collection key. String aliases,
TypeScript string brands, a generic untyped `Id`, `Any`, and convenience overloads
accepting raw values do not meet this rule.

| Value | Contract |
| --- | --- |
| Entity identity | Specific class such as `AccountId` or `ItemId` |
| Provider resource identity | Provider-specific ID, including its required scope |
| Command identity | `OperationId`, preserved during outcome lookup/retry |
| Subscription identity | `SubscriptionId`, distinct from resource identity |
| Row identity | Concrete `ItemRowId` or a closed typed row-ID hierarchy |
| Page/query identity | `ItemPageKey`, with every membership-affecting dimension |
| Pagination/replay | Separate `ItemPageCursor`, `ReplayCursor`, `LocalContinuation` |
| Versions/generations | `ItemRevision`, `ResourceVersion`, `AccountGeneration` |
| Session secrets | Specific redacted token/proof classes, kept out of UI state |

Strings remain appropriate for titles, messages and search input. A partially
entered identifier uses an explicit input type such as `ItemIdInput`; it is not
yet a valid `ItemId`. The parsing adapter returns a typed success/failure result.
Binary file contents remain bytes when they do not represent identity.

## Protobuf wrappers

Public ID messages live beside canonical public models in `:api`. Storage schemas
import them. Every containing field uses the message type:

```protobuf
syntax = "proto3";
package example.items.v1;

message AccountId { bytes raw_value = 1; }
message ItemId { bytes raw_value = 1; }
message OperationId { bytes raw_value = 1; }
message ItemRevision { int64 value = 1; }

message RenameItemRequest {
  AccountId account_id = 1;
  ItemId item_id = 2;
  OperationId operation_id = 3;
  ItemRevision expected_revision = 4;
  string title = 5;
}
```

The scalar inside an ID message is its encoding. It is not permission to carry
`bytes item_id` or `string item_id` elsewhere. Generate one canonical class from
the message and reuse it. If hand-owned canonical classes are selected instead,
generation must map to those classes; do not retain two competing identities.

Validate presence and encoding at ingress. Proto3 defaults, an empty wrapper,
or a nullable generated message field do not prove a valid identity. Reject
missing/invalid IDs before invoking workflows, indexing records or rendering
actionable children. Generate with the pinned toolchain and verify emitted
consumer code rather than assuming message syntax determines Kotlin nullability.

## Value equality, hashing and immutability

Two independently decoded IDs for the same identity compare equal and have equal
hashes. They must work as map/set keys after a protobuf or storage round trip.
Neither wrapper reference identity nor `ByteArray` reference equality is valid.

Byte-backed IDs defensively isolate their backing storage. Mutating constructor
input or an encoded result must not mutate a retained ID or change its hash.
Choose an immutable generated representation or enforce defensive copying in
the canonical implementation. A `data class` containing a mutable array is not
automatically an immutable value class.

Test these contracts for every ID family:

```kotlin
// Fixture and codec names are application-owned. Codec internals own raw bytes.
val original: ItemId = Fixtures.itemId()
val decoded: ItemId = ItemIdCodec.decode(ItemIdCodec.encode(original))
assertEquals(original, decoded)
assertEquals(original.hashCode(), decoded.hashCode())
assertEquals("retained", mapOf(original to "retained")[decoded])
assertEquals(1, setOf(original, decoded).size)
```

Also mutate the codec input/output arrays in adapter tests and verify the ID stays
unchanged. If a generator fails equality/immutability requirements, correct its
canonical output or type mapping. Do not compensate with an unwrapped string key
or add a second application ID class.

## Typed contracts and collection keys

Keep identifiers wrapped through every operation:

```kotlin
interface ItemStore {
    suspend fun get(itemId: ItemId): LocalItem?
    suspend fun remove(itemId: ItemId)
}

interface ItemRepository {
    fun observe(itemId: ItemId): Flow<ItemReadState>
    suspend fun rename(command: RenameItemCommand): RenameOutcome
}

data class RenameItemCommand(
    val itemId: ItemId,
    val operationId: OperationId,
    val expectedRevision: ItemRevision,
    val title: String,
)

data class ScopedItemId(val accountId: AccountId, val itemId: ItemId)
```

Use `Map<ItemId, ...>` and `Set<ItemId>`, not raw-value maps/sets. For scoped
resources, use a concrete composite class with typed fields; do not concatenate
unescaped values. Provider IDs are not globally unique unless the provider says
so. An ID identifies a resource; it does not grant authority to access it.

Presentation identity has its own types. An item in two sections can have two
`ItemRowId` values while retaining one domain `ItemId`. Headers and placeholders
have presentation IDs, never fabricated entity IDs. List positions, hashes,
timestamps and generated wrapper object addresses are not substitutes for IDs.

If an ordering operation requires `Comparable`, define a valid ordering contract
on the wrapper or sort domain snapshots by a separate typed comparator. Do not
unwrap IDs merely to satisfy an operator bound.

## Encoding boundaries

Only narrow adapters encode/decode identity: protobuf codecs, storage/index
codecs, URL/deep-link adapters and platform SDK bridges. Keep encoded values
internal and reconstruct the ID before calling application code.

Use typed ktstore mapped indexes so `index.eq(itemId)` accepts `ItemId`. The
codec maps that value to storage bytes inside the adapter. ID-message bytes and
the wrapper's scalar bytes are different representations; choose one explicitly,
preserve its index name/encoding and migrate when changing it.

Do not normalize IDs by trimming, case folding or base64 conversion unless their
canonical contract requires it. Preserve provider and protocol spelling. Do not
use protobuf serialization as a canonical cryptographic hash without specifying
its complete encoding rules, including unknown fields and ordering.

Basekit route arguments or platform framework keys may require primitive values.
Wrap that requirement in a tested adapter. Viewmodel arguments, callbacks, draft
registries and navigation results remain typed. Generated platform handles stay
inside their integration layer; they do not replace the application's row ID.
Bridge limitations never authorize a raw-ID application overload.

## Sensitive semantic values

Tokens, proofs and cryptographic identity material also need named types and
explicit encodings. Redact their diagnostic rendering. A default generated or
data-class `toString` can expose contents; do not log an entire request merely
because its fields are typed.

Session secrets use secure/platform-owned storage, not ordinary content stores
or viewmodel state. Public IDs are not inherently secret, but logs should include
only the bounded identity context needed for diagnostics. Validate server
ownership independently of client typing.

## Enforcement and acceptance

Run schema validation, static checks and negative compilation fixtures together:

- Reject primitive identifier fields in public/storage schemas outside ID messages.
  Use explicit schema metadata/declared types; suffix heuristics alone are insufficient.
- Reject raw-ID signatures and `.rawValue` access outside approved adapters.
- Prove `AccountId`, raw strings and byte arrays cannot be supplied as `ItemId`.
  Compile deliberately invalid consumers in an expected-failure check; ordinary
  unit tests cannot prove that invalid code is rejected by the compiler.
- Test nested record/message round trips, map/set equality and byte immutability.
- Compile Apple, Android and JS consumers and verify equivalent identity semantics
  through their actual bindings. A JS plain object is not automatically an ID class.
- Check fixtures, fakes, navigation, receipts and collection keys as strictly as
  production code. Test-only shortcuts must not establish raw-ID APIs.

The rule is satisfied when all application-facing identities remain wrapped from
ingress to persistence, observation and presentation, and encoding is confined to
the adapters that own it.
