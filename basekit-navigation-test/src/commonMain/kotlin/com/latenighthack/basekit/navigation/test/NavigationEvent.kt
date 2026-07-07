package com.latenighthack.basekit.navigation.test

import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.NavigatorArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    public val history: StateFlow<List<NavigationEvent>> = events

    public fun record(event: NavigationEvent) {
        events.value = events.value + event
    }
}
