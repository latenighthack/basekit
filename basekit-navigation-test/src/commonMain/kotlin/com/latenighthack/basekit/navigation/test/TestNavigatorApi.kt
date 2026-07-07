package com.latenighthack.basekit.navigation.test

import com.latenighthack.basekit.navigation.NavigationDestination
import com.latenighthack.basekit.navigation.NavigationResponder
import com.latenighthack.basekit.navigation.NavigatorArgs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlin.reflect.KClass

/**
 * The model-agnostic surface a test drives. The generated `TestClientNavigator` implements this (as do
 * hand-rolled interim shims): it exposes the recorded [navigationHistory], knows how to [createDestination]
 * for any destination in the graph, and can [launch] a root destination that nothing navigates *to*.
 *
 * Everything a test author actually calls is the [awaitViewModel] extension below.
 */
public interface TestNavigatorApi {
    /** Ordered, replayable stream of everything navigated to / closed on this navigator. */
    public val navigationHistory: Flow<List<NavigationEvent>>

    /**
     * Instantiates [destination]'s ViewModel with [args], wiring this navigator in as its navigator. For a
     * `RespondingDestination`, [responder] is the channel the ViewModel uses to hand a value back; it is
     * the responder carried on the matched [NavigationEvent.NavigatedTo], or null for plain destinations.
     */
    public fun <T : Any> createDestination(
        destination: KClass<T>,
        args: NavigatorArgs?,
        responder: NavigationResponder<*>? = null,
    ): T

    /**
     * Seeds a root destination (one with no incoming `@NavigateTo` edge, so no `navigateTo…` method
     * exists to record it otherwise). Records a [NavigationEvent.NavigatedTo] as if it had been navigated to.
     */
    public fun launch(args: NavigatorArgs, context: Any? = null)
}

/**
 * Suspends until the LATEST history entry is a [NavigationEvent.NavigatedTo] of [T], then instantiates
 * and returns that destination's ViewModel. Matching on the destination class (not the args class) makes
 * it robust when two destinations share an args type. Because [navigationHistory] is a `StateFlow`, a
 * navigation that already happened is replayed, so this returns immediately in that case.
 */
public suspend inline fun <reified T : Any> TestNavigatorApi.awaitViewModel(): T =
    navigationHistory.mapNotNull { events ->
        (events.lastOrNull() as? NavigationEvent.NavigatedTo)
            ?.takeIf { it.destination == T::class }
            ?.let { createDestination(T::class, it.args, it.responder) }
    }.first()

/**
 * Source-compatibility arity for consumers migrating from the old ViewModel lib, whose call sites read
 * `awaitViewModel<IHomeViewModel, IHomeViewModel.Args>()`. Delegates to the single-arity form.
 *
 * Kotlin cannot overload on type-parameter count alone, so this carries a defaulted [marker] purely to
 * give it a distinct value-parameter signature. Call sites never pass it.
 */
public suspend inline fun <reified T : NavigationDestination<A>, reified A : NavigatorArgs>
    TestNavigatorApi.awaitViewModel(
    @Suppress("UNUSED_PARAMETER") marker: Nothing? = null,
): T = awaitViewModel<T>()
