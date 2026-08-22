package com.latenighthack.basekit.navigation.test

import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.NavigatorArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.reflect.KClass

/**
 * One entry in a [NavigationRecorder]'s ordered history. The generated `TestClientNavigator` records
 * a [NavigatedTo] for every `navigateTo…`/`launch` call and a [Closed] for every `close`, so a test can
 * assert on exactly what a ViewModel navigated to, in order.
 */
public sealed class NavigationEvent {
    /**
     * A navigation to [destination] (the `@Destination` interface). [args] is the destination's
     * navigation arguments (null for arg-less destinations); [source] is the generated `<X>Source`
     * enum entry when the destination has more than one call site, else null; [context] is the opaque
     * value threaded through the generated navigator methods. [responder] carries the resolution
     * channel for a responding destination (a `RespondingDestination`), or null for fire-and-forget.
     */
    public data class NavigatedTo(
        val destination: KClass<*>,
        val args: NavigatorArgs?,
        val source: Any?,
        val context: Any?,
        val responder: NavigationResponder<*>? = null,
    ) : NavigationEvent()

    /** A `close(context)` call. */
    public data class Closed(val context: Any?) : NavigationEvent()
}

/**
 * The per-navigator, globally-isolated store of [NavigationEvent]s. Each `TestClientNavigator` owns one;
 * there is no shared/companion state, so N navigators in one test never cross-talk. [history] is a
 * [StateFlow], so a collector that subscribes after a navigation still replays the latest value — this
 * is what lets `awaitViewModel` match a navigation that already happened.
 */
public class NavigationRecorder {
    private val events = MutableStateFlow<List<NavigationEvent>>(emptyList())

    // Unresolved responders from responding navigations, most-recent last. `close()` dismisses the
    // top one; a responder removes itself on resolve. This is what fixes the leak a naive
    // "respond on the last history entry" close had: navigate to a picker, navigate elsewhere, then
    // close — the picker is no longer the last event, but it is still the top pending responder.
    private val pendingResponders = ArrayDeque<NavigationResponder<*>>()

    public val history: StateFlow<List<NavigationEvent>> = events

    public fun record(event: NavigationEvent) {
        events.update { it + event }
    }

    /** Tracks a responder awaiting resolution. Internal: driven by [recordAndAwaitResponse]. */
    internal fun addPending(responder: NavigationResponder<*>) {
        pendingResponders.addLast(responder)
    }

    /** Drops a responder that resolved or whose caller was cancelled. Idempotent. */
    internal fun removePending(responder: NavigationResponder<*>) {
        pendingResponders.remove(responder)
    }

    /**
     * Dismisses the most recent still-pending responding navigation by resolving its caller with
     * null. A no-op when nothing is pending. The generated `close()` calls this so a back/close over
     * a responding destination always resumes its suspended caller, even when later navigations have
     * happened since.
     */
    public fun dismissTopPending() {
        pendingResponders.removeLastOrNull()?.respond(null)
    }
}
