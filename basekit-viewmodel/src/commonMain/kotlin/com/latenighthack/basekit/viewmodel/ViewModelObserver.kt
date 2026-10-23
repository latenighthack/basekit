@file:OptIn(ExperimentalAtomicApi::class)

package com.latenighthack.basekit.viewmodel

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.PagingEvent
import com.latenighthack.deltalist.SectionedChange
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** What a [ViewModelOperation] is. */
public enum class ViewModelOperationKind {
    /** A zero-argument action. */
    ACTION,

    /** A single-argument mutator. */
    MUTATOR,

    /** Work named with [observedWork]. */
    WORK,

    /** A binding's collection of [ViewModel.state], from attach to detach. */
    STATE_BINDING,

    /** A binding's collection of one `@ViewModelList`, from attach to detach. */
    LIST_BINDING,

    /** One collection of the upstream flow under a binding. A recovery starts another attempt. */
    ATTEMPT,
}

/**
 * One unit of reported work. [id] is unique in the process. [parentId] is the operation that was
 * current when this one started: the binding of an attempt, or the action or work that called
 * [observedWork]. [owner] is the spec's qualified name; [name] is the action, mutator, list property,
 * `state`, or the name given to [observedWork].
 */
public data class ViewModelOperation(
    val id: Long,
    val parentId: Long?,
    val owner: String,
    val name: String,
    val kind: ViewModelOperationKind,
)

/**
 * An event reported to a [ViewModelObserver]. Events carry names, indices, counts and durations.
 * BaseKit never converts a state, item or argument to text: [StateChanged.state] and
 * [Started.viewModel] are references for an observer that reads them itself.
 */
public sealed class ViewModelEvent {
    public abstract val operation: ViewModelOperation

    /** An action, mutator, [observedWork] block or collection attempt started. */
    public data class Started(
        override val operation: ViewModelOperation,
        /** The undecorated implementation, for an action or mutator; otherwise null. */
        val viewModel: Any?,
        /** 1 for the first attempt under a binding, 2 after the first recovery, and so on; 0 otherwise. */
        val attempt: Int = 0,
    ) : ViewModelEvent()

    /** The operation returned normally, or the attempt's flow completed. */
    public data class Completed(override val operation: ViewModelOperation, val duration: Duration) : ViewModelEvent()

    /** The operation threw [error], which is not a cancellation. */
    public data class Failed(override val operation: ViewModelOperation, val duration: Duration, val error: Throwable) : ViewModelEvent()

    /** The operation was cancelled. The cancellation still propagates to the caller. */
    public data class Cancelled(override val operation: ViewModelOperation, val duration: Duration) : ViewModelEvent()

    /** A platform binding started collecting state or a list. [operation] is the binding. */
    public data class Attached(override val operation: ViewModelOperation) : ViewModelEvent()

    /** The platform binding stopped collecting, for any reason. [operation] is the binding. */
    public data class Detached(override val operation: ViewModelOperation, val duration: Duration) : ViewModelEvent()

    /** The attempt's first value arrived [sinceStart] after the attempt started. */
    public data class FirstEmission(override val operation: ViewModelOperation, val sinceStart: Duration) : ViewModelEvent()

    /**
     * A state the binding renders. [sequence] 0 is [ViewModel.initialState], reported as the binding
     * attaches with [operation] set to the binding; later states carry their attempt and count up
     * across attempts. [changedProperties] names the generated State properties that differ from the
     * previous state, compared with `equals`, starting from [ViewModel.initialState].
     */
    public data class StateChanged(
        override val operation: ViewModelOperation,
        val sequence: Long,
        val changedProperties: List<String>,
        val state: Any?,
    ) : ViewModelEvent()

    /**
     * A flat list's delta went to the platform. [change] is the delta's own change: a reload, or
     * insert, remove, move and update mutations with their indices and counts. [size] is the new
     * list size. Items are not read. [applyDuration] is how long the platform's collector took to
     * accept the delta; [failure] is what it threw, other than cancellation.
     */
    public data class ListDelta(
        override val operation: ViewModelOperation,
        val sequence: Long,
        val change: Change,
        val size: Int,
        val applyDuration: Duration,
        val failure: Throwable?,
    ) : ViewModelEvent()

    /**
     * A `paginatedDeltaList` collected by this list binding asked for a page, or a fetch started or
     * ended. [operation] is the list binding, which names the view model and the list.
     */
    public data class ListPaging(override val operation: ViewModelOperation, val event: PagingEvent) : ViewModelEvent()

    /** [ListDelta] for a sectioned list. [sectionCount] is the new number of sections. */
    public data class SectionedListDelta(
        override val operation: ViewModelOperation,
        val sequence: Long,
        val change: SectionedChange,
        val sectionCount: Int,
        val applyDuration: Duration,
        val failure: Throwable?,
    ) : ViewModelEvent()
}

/**
 * Receives the events of observed view models and their generated bindings. It is called
 * synchronously, on the thread doing the work, so keep it short and do not block. A throw from
 * [onEvent] is discarded and does not affect the view model or the binding.
 *
 * Install it on a view model with the generated `observing(observer)` extension (or the router's
 * `viewModelObserver`, which does so for destinations). The decorated view model passes the same
 * observer to its `@ChildViewModel`s, and the generated bindings pass it to the rows of its lists.
 * Without an observer, view models and bindings run exactly as they do without this API.
 */
public fun interface ViewModelObserver {
    public fun onEvent(event: ViewModelEvent)
}

/**
 * Implemented by the generated `Observing{Spec}` decorators. Generated bindings read it to find the
 * observer; application code does not implement it.
 */
public interface ObservedViewModel<State> {
    /** The observer this view model reports to. */
    public val basekitObserver: ViewModelObserver

    /** The generated State properties whose values differ between [previous] and [next]. */
    public fun basekitChangedProperties(previous: State, next: State): List<String>
}

/**
 * The observer installed on this view model by its generated decorator, which bindings report to and
 * pass to children and rows; null without one. A decorator made by `observingActions` reports actions
 * only, so this is null for it.
 */
public fun ViewModel<*>.viewModelObserver(): ViewModelObserver? =
    (this as? ObservedViewModel<*>)?.basekitObserver?.takeIf { it.observesBindings }

/**
 * The decorated form of a child property, for the generated decorators: [decorate] runs once per child
 * instance, so the child keeps one identity while the property returns the same instance.
 */
public class ObservedChildCache<T : Any>(private val decorate: (T) -> T) {
    @kotlin.concurrent.Volatile
    private var entry: Pair<T, T>? = null

    public fun get(raw: T): T {
        entry?.let { if (it.first === raw) return it.second }
        return decorate(raw).also { entry = raw to it }
    }
}

/** Observer helpers for generated code, as members so Swift reaches them as `ViewModelObservers.shared`. */
public object ViewModelObservers {
    /**
     * One observer reporting to both [observer] and [actions]: [actions] receives each zero-argument
     * action before it runs, as `observingActions` delivers it. Null when neither observes anything.
     */
    public fun combined(observer: ViewModelObserver?, actions: ViewModelActionObserver): ViewModelObserver? {
        val adapted = if (actions === ViewModelActionObserver.NoOp) null else ActionObserverAdapter(actions)
        return when {
            observer == null -> adapted
            adapted == null -> observer
            else -> ViewModelObserver { event ->
                observer.notify(event)
                adapted.notify(event)
            }
        }
    }
}

/**
 * Runs [block] as an operation named [name], reported to the observer of the action or binding
 * that is running it, under that operation. Outside an observed operation it runs [block] and
 * reports nothing. Only the start and the outcome are reported, not suspensions, resumptions or
 * dispatcher changes.
 */
public suspend fun <R> observedWork(name: String, block: suspend () -> R): R {
    val current = currentCoroutineContext()[ViewModelObservation] ?: return block()
    return current.observer.observeOperation(
        ViewModelObservation.nextOperation(current.operation.id, current.operation.owner, name, ViewModelOperationKind.WORK),
        viewModel = null,
        block = block,
    )
}

/**
 * The observer and current operation in a coroutine context, set while an observed action, mutator
 * or [observedWork] block runs. Generated decorators and [observedWork] use it to parent operations.
 */
public class ViewModelObservation internal constructor(
    public val observer: ViewModelObserver,
    public val operation: ViewModelOperation,
) : AbstractCoroutineContextElement(ViewModelObservation) {
    public companion object Key : CoroutineContext.Key<ViewModelObservation> {
        private val ids = AtomicLong(0)

        internal fun nextOperation(parentId: Long?, owner: String, name: String, kind: ViewModelOperationKind) =
            ViewModelOperation(ids.addAndFetch(1L), parentId, owner, name, kind)
    }
}

/**
 * Runs an action or mutator of an observed view model, for the generated decorators: reports it
 * started, then its outcome. A throw is rethrown unchanged; a cancellation stays a cancellation.
 */
public suspend fun <R> ViewModelObserver.observeCall(
    owner: String,
    name: String,
    kind: ViewModelOperationKind,
    viewModel: Any,
    block: suspend () -> R,
): R {
    val parent = currentCoroutineContext()[ViewModelObservation]?.operation?.id
    return observeOperation(ViewModelObservation.nextOperation(parent, owner, name, kind), viewModel, block)
}

private suspend fun <R> ViewModelObserver.observeOperation(
    operation: ViewModelOperation,
    viewModel: Any?,
    block: suspend () -> R,
): R {
    val start = TimeSource.Monotonic.markNow()
    notify(ViewModelEvent.Started(operation, viewModel))
    // Only full observers see nested operations, so an action-only observer adds no coroutine.
    val run: suspend () -> R = if (this is ActionObserverAdapter) block else {
        { withContext(ViewModelObservation(this, operation)) { block() } }
    }
    val result = try {
        run()
    } catch (error: CancellationException) {
        notify(ViewModelEvent.Cancelled(operation, start.elapsedNow()))
        throw error
    } catch (error: Throwable) {
        notify(ViewModelEvent.Failed(operation, start.elapsedNow(), error))
        throw error
    }
    notify(ViewModelEvent.Completed(operation, start.elapsedNow()))
    return result
}

/** Delivers [event], discarding anything [ViewModelObserver.onEvent] throws. */
internal fun ViewModelObserver.notify(event: ViewModelEvent) {
    try {
        onEvent(event)
    } catch (_: Throwable) {
    }
}

/** True for an observer that receives bindings' events; an adapted [ViewModelActionObserver] does not. */
internal val ViewModelObserver.observesBindings: Boolean get() = this !is ActionObserverAdapter

/** A [ViewModelActionObserver] as a [ViewModelObserver]: actions only, before they run. */
internal class ActionObserverAdapter(val actions: ViewModelActionObserver) : ViewModelObserver {
    override fun onEvent(event: ViewModelEvent) {
        if (event is ViewModelEvent.Started && event.operation.kind == ViewModelOperationKind.ACTION) {
            actions.onAction(ViewModelActionEvent(event.operation.owner, event.operation.name, event.viewModel ?: return))
        }
    }
}

/** [actions] as a [ViewModelObserver] for a generated decorator, for `observingActions`. */
public fun actionObserverAsViewModelObserver(actions: ViewModelActionObserver): ViewModelObserver = ActionObserverAdapter(actions)

/** The [ViewModelActionObserver] [observer] adapts, or null; lets `observingActions` stay idempotent. */
public fun adaptedActionObserver(observer: ViewModelObserver): ViewModelActionObserver? = (observer as? ActionObserverAdapter)?.actions
