# Use cases and mutation workflows

Use cases express reusable application behavior over repository contracts. Reuse
the **whole flow**, including validation, sequencing, branching, partial success
and recovery. Do not make every viewmodel rebuild the same flow from individual
repository operations or copy a sequence of small use-case calls.

A use case can contain multiple logical groupings together. It does not have to
be one class per verb, one `invoke` function, or one repository operation. Group
related observations, commands and complete workflows when they share a coherent
application responsibility. Split them when ownership, dependencies or policy
diverge; do not impose artificial fragmentation merely to satisfy a naming rule.

This guide expands the [architecture](../multiplatform-architecture.md),
[repository guide](storage-and-repositories.md), [composition/lifecycle guide](composition-startup-and-lifecycle.md)
and [testing guide](testing.md). Contracts below are illustrative application code,
not generated Basekit APIs or a compiled starter. Every identifier uses its
canonical concrete ID wrapper, including operation and workflow identities.

## Assign responsibility before choosing classes

| Layer | Owns |
| --- | --- |
| Viewmodel | Editable presentation draft, selection, feedback, user action eligibility and scoped navigation |
| Use case | Application decisions, reusable projections, complete workflows and cross-repository orchestration |
| Repository | Domain observations, API/provider interaction, synchronization, memory/cache, persistence acceptance and optimistic overlays |
| Passive store | Typed generated protobuf records, indexes and transactional persistence operations |
| Server service/domain | Authoritative authorization, validation, revision checks, durable mutation/effect receipts and business rules |

Use cases consume repositories, never raw stores, SDKs or generated RPC clients.
Actual screen navigation stays in viewmodels/coordinators; use cases return typed
outcomes or semantic next intents. A workflow must be usable from a native screen,
web screen, headless entry point or another use case without importing destinations.

Local eligibility improves interaction, but the server independently validates
authority and current state. Shared application validation is not an excuse to
weaken server verification.

## Reuse a complete workflow across callers

For example, applying a standing policy and submitting a decision is one reusable
application flow. A screen, contextual row and eligible background action should
call that flow instead of each implementing:

```text
validate intent
  → save policy
  → submit decision
  → interpret partial success
  → reconcile projections
  → report outcome and available recovery
```

The use case owns that sequence and its policy. Repositories own the individual
remote operations and their acceptance into local observations. A caller owns its
draft and presentation of the result. Different entry points can share behavior
while retaining different navigation and confirmation requirements.

If a flow already exists, extend/reuse its explicit contract rather than duplicate
its private steps in a new viewmodel. Share smaller subflows where genuinely
reusable, but keep a public complete-flow entry point for consumers. Calling a
workflow from another workflow must not accidentally execute its side effects
twice; pass an explicit operation context and identify completed steps.

A headless caller reuses only workflows for which it has sufficient authorized
intent and supported capabilities. Reuse does not permit background code to skip
user confirmation, launch interactive authentication or infer an action from a
notification tap.

## Group related behavior deliberately

A coherent use-case class can contain several logical groupings:

```kotlin
class ItemUseCases(
    private val items: ItemRepository,
    private val policies: PolicyRepository,
) {
    // Reading and discovery grouping.
    fun observe(itemId: ItemId): Flow<ItemReadState> = items.observe(itemId)
    fun observeItems(query: ItemQuery): Flow<Delta<ItemSummary>> = items.observeItems(query)

    // Editing grouping.
    suspend fun rename(command: RenameItemCommand): RenameOutcome =
        items.rename(command)

    // Complete decision workflow grouping.
    suspend fun applyPolicyAndDecide(
        command: ApplyPolicyAndDecideCommand,
    ): PolicyDecisionOutcome = executePolicyDecision(command)

    // Whole-flow recovery, under the same policy and operation identities.
    suspend fun reconcileDecision(
        workflowId: DecisionWorkflowId,
    ): PolicyDecisionOutcome = reconcilePolicyDecision(workflowId)

    // Application-owned private workflow implementations omitted.
}
```

This shape is valid when the operations form a cohesive feature capability; it
is not a requirement to put every feature into one large class. An observation
method may forward a repository flow unchanged: keeping the layer seam does not
require manufacturing extra state or another subscription owner.

Use separate workflow objects inside a grouped use case when a sequence is complex
or reused elsewhere. Expose explicit methods/interfaces, not string-based dispatch
or dynamic service lookup. Choose names that explain application behavior rather
than mirroring every endpoint mechanically.

A dependency bundle such as `ItemUseCaseDependencies` can collect typed use-case
instances to reduce constructor noise. It is distinct from a use case that owns
behavior: a bag of dependencies does not replace the reusable flow. Keep bundles
bounded and explicit; do not turn them into a global access point for unrelated
features or repositories.

## Specify typed commands and outcomes

A command captures one logical intent, with immutable target, parameters, expected
revision and stable operation identity:

```kotlin
data class RenameItemCommand(
    val itemId: ItemId,
    val operationId: OperationId,
    val expectedRevision: ItemRevision,
    val title: String,
)

sealed interface RenameOutcome {
    data class Accepted(val itemId: ItemId, val revision: ItemRevision) : RenameOutcome
    data class Invalid(val issues: RenameValidation) : RenameOutcome
    data class Conflict(val currentRevision: ItemRevision) : RenameOutcome
    data object Forbidden : RenameOutcome
    data object UnavailableOffline : RenameOutcome
    data class Rejected(val failure: MutationFailure) : RenameOutcome
    data class Uncertain(val operationId: OperationId) : RenameOutcome
}
```

Distinguish rejected, accepted and unknown outcomes. A timeout after submission
does not prove failure. A conflict requires re-reading/rebasing or another explicit
decision; a generic retry must not overwrite somebody else's accepted update.
Transport errors are translated into domain outcomes at the appropriate repository
boundary, with safe diagnostics and no secret-bearing payloads in UI state.

Give multi-step flows explicit partial outcomes. For example,
`PolicySavedDecisionRejected` retains the accepted policy receipt and decision
failure; `PolicySavedDecisionUncertain` retains the receipt and pending decision
operation ID. Do not return generic failure that implies the policy was rolled
back. Cancellation propagates as coroutine cancellation, while durable receipts
and observations preserve what actually happened.

## Define the mutation protocol

1. Capture the user intent and current expected revision. Freeze command content;
   allocate one typed operation identity for that logical submission.
2. Validate shared application constraints and eligibility without claiming server
   authority. Serialize conflicting work under the declared resource/workflow scope.
3. Invoke the repository command. If enabled, install its explicit pending overlay
   independently of committed content.
4. Execute authenticated server work. The server verifies access, revision and
   idempotency and persists the authoritative outcome.
5. The repository validates response/account generation, commits accepted content,
   membership/checkpoints and required metadata, then publishes observations.
6. The use case interprets the complete workflow outcome. The viewmodel updates
   draft/feedback and navigates only when the outcome permits it.

This is state-driven coroutine coordination: no application locks, mutexes or
semaphores. `StatefulViewModel.update` accepts a short, pure, non-suspending reducer
and delegates to StateFlow's atomic update. Its reducer may be evaluated again;
never put I/O, navigation, operation-ID allocation or any effect inside it. Capture
the command, perform the effect in the caller's coroutine, then merge its immutable
result only if the owning intent and generation still match. Do not keep a database
transaction open across network/provider work either. The
[viewmodel guide](viewmodels-state-and-flows.md) specifies both update-driven state
and direct `Flow<State>` implementations, with complete examples and tests.

Repository publication follows successful outer commit. An accepted server response
followed by local persistence failure needs reconciliation; it is not a rejected
server mutation. Response delivery and realtime delivery must converge without
duplicating entities, advancing checkpoints incorrectly or regressing revisions.

## Scope concurrency and operation identity

Choose concurrency according to the conflicting resource: a typed item, policy
scope or workflow identity. A busy Boolean on one button cannot prevent another
screen or client from racing. Client coordination prevents avoidable local races;
server revision checks remain authoritative across clients.

Use an explicit coroutine-owned workflow state for conflicting local work. Confine
decision handling to that owner, share an in-flight result where appropriate, and
use structured cancellation or latest-intent Flow composition for replaceable
observations. Do not introduce local resource locks, mutexes or semaphores. Avoid
dependency cycles; retire account-bound work with its account scope. Server
transactions, revisions and durable operation receipts establish cross-client
correctness; a client coroutine alone cannot provide that guarantee.

Keep operation identity stable when retrying/reconciling the same frozen command.
The server binds idempotency to actor, operation and canonical request content;
reuse with different content fails. A new user intent gets a new identity. An
edited title or changed expected revision is not the original submission merely
because it came from the same screen.

For a multi-step workflow, use a concrete workflow ID and stable typed identities
for each remote step. Record or deterministically derive the step mapping under
an explicit contract. Re-entering a whole flow must reconcile completed steps,
not allocate fresh child operation IDs and repeat accepted effects.

Idempotency has scope, retention and expiry rules. Receipt lookup must be
authenticated and bound to the original actor/command. When the server cannot
establish an old operation's outcome, report that uncertainty; do not promise safe
automatic replay after receipt expiry.

## Keep optimistic state separate from committed truth

The default remains online writes and committed repository observations. Add
optimistic behavior only where the product has a clear reconciliation contract.

The repository owns a pending overlay keyed by typed operation/resource identity;
it does not save unconfirmed optimistic values as authoritative protobuf content.
An overlay records its base revision, proposed change and pending status. Use-case
projections and viewmodels can expose that status without creating another cache.

On acceptance, reconcile the overlay against confirmed server content and retire
it once represented. On definite rejection, remove only that operation's overlay;
do not restore an old whole snapshot over later live updates or another accepted
mutation. On uncertainty, retain an explicit pending/uncertain presentation and
query receipts/current authoritative state under the declared recovery policy.

Define composition of concurrent overlays or reject unsupported overlapping edits.
Test response-first and realtime-first ordering, out-of-order outcomes, conflicts
and remote deletion. A temporary optimistic row has its own concrete presentation
ID; never fabricate a server entity ID or use a raw string placeholder.

## Treat multi-effect flows honestly

A local repository-level unit of work can coordinate declared stores and coherent
publication. Use cases receive that repository contract, not raw store handles.
A local transaction does not make separate server requests atomic.

If the product requires atomic policy-and-decision behavior, provide one server
operation with a verified authoritative transaction. If separate requests are
intentional, the use case models their partial outcomes and recovery explicitly.
Compensation is another authorized mutation with its own revision/operation
contract, not a pretend rollback of committed effects.

External provider effects require durable server claims/receipts, provider
idempotency where supported and an explicit uncertain state. Do not put external
calls inside a database transaction or promise exactly-once execution from local
locking. Workers and interactive callers must share the same effect ownership
and reconciliation rules.

For bulk work, retain an outcome per typed target/operation. Bound concurrency,
report partial acceptance and reconcile uncertain targets individually. Re-running
the entire batch with fresh identities is not a safe retry strategy.

## Separate reusable flow lifetime from presentation lifetime

A use case is not inherently a coroutine scope. A cold observation inherits the
lifetime of its collecting viewmodel binding; a suspend command inherits its caller.
Prefer those structured lifetimes. For work intentionally outliving a binding,
inject execution ownership or accept a workflow-owned context. Stateless methods
can run in the caller's scope; do not create a hidden perpetual scope for each
use-case object.

Destination cancellation stops that caller's observation and pending interaction.
It cannot undo accepted remote work. If completion must survive destination loss,
place operation tracking in the declared account/repository or durable workflow
owner, then let later callers reconcile it. Only still-valid viewmodels navigate
or update drafts from the result. Account retirement fences all old completions.

Progress, if needed, is a typed projection of workflow state with one owner.
Do not make UI consumers infer successful completion from a conflated progress
Boolean; use the command outcome/receipt. Shared observations retain the DeltaList
contract for collections and do not introduce competing `Flow<List<...>>` caches.

## Acceptance tests

- Drive the same complete workflow from different real viewmodels and a supported
  headless caller; assert identical application policy and distinct presentation.
- Test grouped use-case observations/commands without duplicating transport or
  cache ownership; use repository fakes, not raw stores or RPC mocks, at this layer.
- Gate each remote step and cover every partial-success, definite-rejection and
  uncertain-outcome branch. Cancellation must not imply rollback.
- Retry frozen commands with the same operation identities; reject ID reuse with
  changed content and preserve completed-step identities across workflow recovery.
- Verify per-resource concurrency, stale generations, conflicts and unrelated
  work proceeding independently. Test actual competing clients against a server.
- Test optimistic overlays separately from committed content, including late
  realtime updates, reverse response order and operation-specific removal.
- Run full-server viewmodel journeys with response loss, local commit failure,
  caller disposal and server restart; reconcile authoritative receipts/state.
- Verify forbidden/invalid/offline outcomes produce no successful navigation,
  while partial acceptance remains visible and recoverable.

See [testing](testing.md) for production factories, listening-server startup,
multi-client journeys and durable failure injection.
