package com.latenighthack.basekit.viewmodel

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.PagingObservation
import com.latenighthack.deltalist.SectionedDelta
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

// The observed forms of boundState and boundList. They collect the same flows once, recover the same
// way, and add only the reports. The unobserved forms in ViewModelFailures.kt are unchanged.

internal fun <S> ViewModel<S>.observedBoundState(viewModelName: String, observed: ObservedViewModel<S>): Flow<S> {
    val viewModel = this
    val observer = observed.basekitObserver
    return flow {
        val binding = bindingOperation(viewModelName, ViewModelFailure.STATE, ViewModelOperationKind.STATE_BINDING)
        val attached = TimeSource.Monotonic.markNow()
        observer.notify(ViewModelEvent.Attached(binding))
        var lastState = viewModel.initialState
        var sequence = 0L
        observer.notify(ViewModelEvent.StateChanged(binding, sequence, emptyList(), lastState))
        try {
            var next: Flow<S>? = viewModel.state
            var attempt = 0
            while (true) {
                val source = next ?: break
                next = null
                attempt += 1
                val operation = ViewModelObservation.nextOperation(binding.id, viewModelName, ViewModelFailure.STATE, ViewModelOperationKind.ATTEMPT)
                val start = TimeSource.Monotonic.markNow()
                observer.notify(ViewModelEvent.Started(operation, viewModel = null, attempt = attempt))
                var first = true
                var ended = false
                try {
                    source
                        .catch { error ->
                            if (error is CancellationException) throw error
                            ended = true
                            observer.notify(ViewModelEvent.Failed(operation, start.elapsedNow(), error))
                            next = viewModel.recoverState(ViewModelFailure(viewModelName, ViewModelFailure.STATE, error), lastState)
                        }
                        .collect { value ->
                            if (first) {
                                first = false
                                observer.notify(ViewModelEvent.FirstEmission(operation, start.elapsedNow()))
                            }
                            sequence += 1
                            observer.notify(ViewModelEvent.StateChanged(operation, sequence, observed.changedProperties(lastState, value), value))
                            lastState = value
                            emit(value)
                        }
                } catch (error: Throwable) {
                    if (!ended) observer.notifyEnd(operation, start, error)
                    throw error
                }
                if (!ended) observer.notify(ViewModelEvent.Completed(operation, start.elapsedNow()))
            }
        } finally {
            observer.notify(ViewModelEvent.Detached(binding, attached.elapsedNow()))
        }
    }
}

internal fun <T> ViewModel<*>.observedBoundList(
    viewModelName: String,
    listName: String,
    list: Flow<T>,
    observer: ViewModelObserver,
): Flow<T> {
    val viewModel = this
    return flow {
        val binding = bindingOperation(viewModelName, listName, ViewModelOperationKind.LIST_BINDING)
        val attached = TimeSource.Monotonic.markNow()
        observer.notify(ViewModelEvent.Attached(binding))
        try {
            // Lists have no recovery flow, so each attachment has one attempt.
            val operation = ViewModelObservation.nextOperation(binding.id, viewModelName, listName, ViewModelOperationKind.ATTEMPT)
            val start = TimeSource.Monotonic.markNow()
            observer.notify(ViewModelEvent.Started(operation, viewModel = null, attempt = 1))
            var first = true
            var ended = false
            var sequence = 0L
            // Paginated lists upstream report their demand and fetches under this binding. The element
            // has no dispatcher, so the upstream runs in this coroutine without a channel.
            val paging = PagingObservation { event -> observer.notify(ViewModelEvent.ListPaging(binding, event)) }
            try {
                list
                    .flowOn(paging)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        ended = true
                        observer.notify(ViewModelEvent.Failed(operation, start.elapsedNow(), error))
                        viewModel.recoverList(ViewModelFailure(viewModelName, listName, error))
                    }
                    .collect { value ->
                        if (first) {
                            first = false
                            observer.notify(ViewModelEvent.FirstEmission(operation, start.elapsedNow()))
                        }
                        sequence += 1
                        val applying = TimeSource.Monotonic.markNow()
                        try {
                            emit(value)
                        } catch (error: Throwable) {
                            if (error !is CancellationException) observer.notifyDelta(operation, sequence, value, applying, error)
                            throw error
                        }
                        observer.notifyDelta(operation, sequence, value, applying, null)
                    }
            } catch (error: Throwable) {
                if (!ended) observer.notifyEnd(operation, start, error)
                throw error
            }
            if (!ended) observer.notify(ViewModelEvent.Completed(operation, start.elapsedNow()))
        } finally {
            observer.notify(ViewModelEvent.Detached(binding, attached.elapsedNow()))
        }
    }
}

private suspend fun bindingOperation(owner: String, name: String, kind: ViewModelOperationKind): ViewModelOperation =
    ViewModelObservation.nextOperation(currentCoroutineContext()[ViewModelObservation]?.operation?.id, owner, name, kind)

/** The changed property names, or none when the generated comparison throws (from a user `equals`). */
private fun <S> ObservedViewModel<S>.changedProperties(previous: S, next: S): List<String> =
    try {
        basekitChangedProperties(previous, next)
    } catch (_: Throwable) {
        emptyList()
    }

private fun ViewModelObserver.notifyEnd(operation: ViewModelOperation, start: TimeSource.Monotonic.ValueTimeMark, error: Throwable) {
    notify(
        if (error is CancellationException) ViewModelEvent.Cancelled(operation, start.elapsedNow())
        else ViewModelEvent.Failed(operation, start.elapsedNow(), error),
    )
}

/** Reports a delta from its change and size alone; items are never read. Other values are not reported. */
private fun ViewModelObserver.notifyDelta(
    operation: ViewModelOperation,
    sequence: Long,
    value: Any?,
    applying: TimeSource.Monotonic.ValueTimeMark,
    failure: Throwable?,
) {
    when (value) {
        is Delta<*> -> notify(ViewModelEvent.ListDelta(operation, sequence, value.change, value.items.size, applying.elapsedNow(), failure))
        is SectionedDelta<*, *> -> notify(ViewModelEvent.SectionedListDelta(operation, sequence, value.change, value.sections.size, applying.elapsedNow(), failure))
    }
}
