package com.latenighthack.basekit.navigation.test

import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.NavigatorArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlin.reflect.KClass

/**
 * Records a responding navigation and suspends until its [NavigationResponder] is invoked, returning the
 * response (or null on dismiss/close). The generated `TestClientNavigator`'s `suspend navigateTo…` overrides
 * call this; it is the single place the deferred-backed response channel and its cancellation contract live.
 *
 * The responder created here is the exact instance carried on the recorded [NavigationEvent.NavigatedTo], so
 * every way of responding resolves the same caller: the destination ViewModel (handed the responder via the
 * registry), a test's direct `event.responder?.respond(...)`, and `close()` (which responds null).
 *
 * If the caller coroutine is cancelled while suspended here, the pending deferred is cancelled too, so a
 * later `respond(...)` is a no-op rather than resolving a caller that no longer exists.
 */
public suspend fun <R : Any> NavigationRecorder.recordAndAwaitResponse(
    destination: KClass<*>,
    args: NavigatorArgs?,
    source: Any?,
    context: Any?,
): R? {
    val deferred = CompletableDeferred<R?>()
    lateinit var responder: NavigationResponder<R>
    responder = NavigationResponder<R> { response ->
        // Resolving removes it from the pending stack so a later close() dismisses the next one, not this.
        removePending(responder)
        deferred.complete(response)
    }
    addPending(responder)
    record(NavigationEvent.NavigatedTo(destination, args, source, context, responder))
    return try {
        deferred.await()
    } catch (cancellation: CancellationException) {
        removePending(responder)
        deferred.cancel()
        throw cancellation
    }
}
