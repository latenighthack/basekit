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

/** How a generated router shows an entry. */
public enum class NavigationPresentation { ROOT, PUSH, SHEET, FULL_SCREEN_COVER, CUSTOM }

/**
 * What a generated router actually shows, as opposed to what was asked for. A [NavigationEvent.NavigatedTo]
 * is the request; [Presented] follows when the router puts the entry on its stack, sheet, cover or a
 * custom presentation, or a host shows a root. [NavigationEvent.Closed] is a close request or a host
 * dismissal; [Dismissed] follows when the entry leaves. [NavigationEvent.Responded] is the responding
 * navigation's resolution. A navigation a handler claims and never presents has no [Presented].
 */
public sealed class NavigationPresentationEvent {
    public abstract val screen: NavigationScreenId

    /** The router's own id for the entry, the same in the matching [Dismissed]. */
    public abstract val entryId: String

    /** The entry is shown. [customId] is the platform presentation's id for [NavigationPresentation.CUSTOM]. */
    public data class Presented(
        override val screen: NavigationScreenId,
        override val entryId: String,
        val presentation: NavigationPresentation,
        val customId: String?,
    ) : NavigationPresentationEvent()

    /** The entry is no longer shown. Entries leaving together are reported top first. */
    public data class Dismissed(
        override val screen: NavigationScreenId,
        override val entryId: String,
        val presentation: NavigationPresentation,
    ) : NavigationPresentationEvent()
}

/**
 * Implement it on the [NavigationObserver] given to a generated router to also receive what the router
 * presents and dismisses. Routers with any other observer track nothing.
 */
public fun interface NavigationPresentationObserver {
    public fun onPresentation(event: NavigationPresentationEvent)
}
