package com.latenighthack.basekit.navigation

import kotlin.reflect.KClass

/**
 * Marker implemented by the generated app-wide `NavigationScreen` enum. It lets this library carry the
 * screen on a [NavigationEvent] without depending on the per-app enum: an observer narrows once
 * (`event.screen as NavigationScreen`) and then gets a compiler-exhaustive `when` over its screens.
 */
public interface NavigationScreenId

/**
 * A navigation event emitted by an observing navigator (see the generated `Observing<X>Navigator`)
 * before it delegates to the real navigator. The sealed hierarchy plus the generated [NavigationScreen]
 * enum on each event let a [NavigationObserver] log every screen transition exhaustively.
 */
public sealed class NavigationEvent {
    /** A `navigateTo…` call: navigation to [screen] (the [destination] interface) with [args]. */
    public data class NavigatedTo(
        val screen: NavigationScreenId,
        val destination: KClass<*>,
        val args: NavigatorArgs?,
        // The generated `<X>Source` enum entry when the target has more than one call site, else null.
        val source: Any?,
        val context: Any?,
    ) : NavigationEvent()

    /**
     * A responding navigation (a [RespondingDestination]) resolved: [response] is the value handed
     * back, or null on dismiss/close. Emitted after the suspended `navigateTo…` call returns.
     */
    public data class Responded(
        val screen: NavigationScreenId,
        val response: Any?,
    ) : NavigationEvent()

    /** A `close(context)` on the screen that owns the navigator. */
    public data class Closed(
        val screen: NavigationScreenId,
        val context: Any?,
    ) : NavigationEvent()
}

/**
 * Receives every navigation event flowing through an observing navigator. Supply one (wrap the
 * concrete navigator in the generated `Observing<X>Navigator` before injecting it into the ViewModel)
 * to get a single, platform-agnostic seam for screen-level logging.
 */
public fun interface NavigationObserver {
    public fun onNavigation(event: NavigationEvent)

    public companion object {
        /** An observer that discards every event; the default delegate for un-observed navigation. */
        public val NoOp: NavigationObserver = NavigationObserver { }
    }
}
