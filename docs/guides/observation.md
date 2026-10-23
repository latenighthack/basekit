# Observing view models and bindings

A `ViewModelObserver` receives events from a view model and from the generated bindings that show
it: actions and mutators, state and list collection, list deltas, and paging. It is optional. Without
one, view models and bindings run the same code as before and create no events.

```kotlin
val observer = ViewModelObserver { event ->
    when (event) {
        is ViewModelEvent.Failed -> log.warn("${event.operation.owner}.${event.operation.name} failed", event.error)
        else -> {}
    }
}
```

`onEvent` runs synchronously, on the thread doing the work. Keep it short and do not block. A throw
from it is discarded. A cancellation is still reported as `Cancelled` and still propagates.

## Installing it

| Where | How |
|---|---|
| Destinations | `router.viewModelObserver = observer` (Kotlin and Swift). Routers made from it use the same observer unless they set their own. |
| Any view model | `viewModel.observing(observer)`, generated for each spec. It returns the view model itself when the observer is null or already installed. |
| Compose host | `{Spec}Host(…, observer = observer) { … }` |
| SwiftUI / UIKit | `Observable{Spec}(viewModel, observer: observer)`, `Kvo{Spec}(viewModel, observer: observer)` |
| React | `viewModel.reactReference(observer)` |

A `@HostedComponent`'s view model is built by the app, so pass the observer to one of these
explicitly.

The decorated view model passes the observer on:

- Each `@ChildViewModel` property returns its child decorated with the same observer. The decorated
  child is the same instance for as long as the property returns the same child.
- The generated bindings decorate each row of a `@ViewModelList` as they bind it: Compose with
  `remember`, React once per row object, Swift in the list binding's row classifier. Lists are not
  mapped or read for this.

`ViewModelActionObserver` and `observingActions` are unchanged. An action observer receives
zero-argument actions only, and does not decorate children or rows. When a router has both,
`basekitViewModel` reports to both.

## Operations

Every event carries a `ViewModelOperation`: `id` (unique in the process), `parentId`, `owner` (the
spec's qualified name), `name`, and `kind`.

| Kind | Name | Parent |
|---|---|---|
| `ACTION`, `MUTATOR` | the function | the operation running when it was called, if observed |
| `WORK` | the name passed to `observedWork` | the action or work running it |
| `STATE_BINDING` | `state` | the operation running when collection started, usually none |
| `LIST_BINDING` | the list property | as for `STATE_BINDING` |
| `ATTEMPT` | `state` or the list property | its binding |

`observedWork(name) { … }` reports a block as its own operation under the running one. Outside an
observed action it runs the block and reports nothing. Suspensions, resumptions and dispatcher
changes are not reported.

## Events

| Event | When |
|---|---|
| `Started(operation, viewModel, attempt)` | An action, mutator, work block or attempt starts. `viewModel` is the undecorated implementation for actions and mutators. `attempt` counts attempts under a binding from 1. |
| `Completed(operation, duration)` | It returned, or the attempt's flow completed. |
| `Failed(operation, duration, error)` | It threw something other than a cancellation. The throw continues as before. |
| `Cancelled(operation, duration)` | It was cancelled. |
| `Attached(binding)` / `Detached(binding, duration)` | A platform binding started or stopped collecting. |
| `FirstEmission(attempt, sinceStart)` | The attempt's first value. |
| `StateChanged(operation, sequence, changedProperties, state)` | Sequence 0 is `initialState`, reported with the binding as it attaches. Later states carry their attempt. `changedProperties` names the generated State properties that differ from the previous state, by `equals`. |
| `ListDelta(attempt, sequence, change, size, applyDuration, failure)` | A flat list delta went to the platform. `change` is the delta's own `Change`. |
| `SectionedListDelta(attempt, sequence, change, sectionCount, applyDuration, failure)` | The same for a sectioned list. |
| `ListPaging(binding, event)` | A `paginatedDeltaList` collected by this list asked for a page (`Requested`), or a fetch started, completed, failed or was cancelled. |

`applyDuration` is the time the platform's collector took to accept the delta. `failure` is what it
threw while applying it.

BaseKit never converts a state, item or argument to text. `state` and `viewModel` are references
for an observer that reads them itself.

### A binding's events

One platform collection is one upstream collection. For a binding whose state fails once and is
recovered by `ViewModelFailureHandler.onStateFailed`:

```
Attached STATE_BINDING
StateChanged 0 (initialState)
Started ATTEMPT #1
FirstEmission
StateChanged 1 [title]
Failed ATTEMPT #1
Started ATTEMPT #2        same binding, new id
StateChanged 2 [error]
Completed ATTEMPT #2
Detached STATE_BINDING
```

A row whose `state` is `emptyFlow()` reports `Started` and `Completed` with no `FirstEmission`.
Compose, SwiftUI, UIKit and React report the same sequence, because each collects through the same
Kotlin `boundState` and `boundList`.

## Navigation presentation

`NavigationObserver` events describe requests: `NavigatedTo` is a navigation asked for, `Closed` a
close asked for or a host dismissal, and `Responded` a responding navigation's result. To also see
what the generated router actually shows, implement `NavigationPresentationObserver` on the same
observer:

| Event | When |
|---|---|
| `Presented(screen, entryId, presentation, customId)` | The router put the entry on its path, sheet, cover or a custom presentation, or a host showed a root (`didShow`). |
| `Dismissed(screen, entryId, presentation)` | The entry left. Entries leaving together are reported top first. |

A navigation that a handler claims and never presents has a `NavigatedTo` and no `Presented`.

## Paging in DeltaList

`paginatedDeltaList` reports to a `PagingObserver` given as its `observer` argument, and to the
`PagingObservation` in the context of each collector while it collects. An observed `boundList`
installs one with `flowOn`, so paging under a view model's list arrives as `ListPaging` with that
list's binding.
