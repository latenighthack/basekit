package com.latenighthack.basekit.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Presents a responding destination and suspends until it responds or is dismissed.
 *
 * The result channel is completed at most once. Cancelling the caller cancels the pending channel,
 * making a later response a no-op. Platform hosts dismiss by invoking the supplied responder with
 * `null`; they never need to own coroutine or continuation state themselves.
 */
public suspend fun <R : Any> awaitNavigationResult(
    present: (NavigationResponder<R>) -> Unit,
): R? {
    val deferred = CompletableDeferred<R?>()
    val responder = NavigationResponder<R> { response -> deferred.complete(response) }

    return try {
        present(responder)
        deferred.await()
    } catch (cancellation: CancellationException) {
        deferred.cancel()
        throw cancellation
    } catch (failure: Throwable) {
        deferred.cancel()
        throw failure
    }
}
