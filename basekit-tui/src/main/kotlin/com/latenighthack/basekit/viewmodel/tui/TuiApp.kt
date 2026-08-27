package com.latenighthack.basekit.viewmodel.tui

import kotlin.reflect.KClass

/**
 * Configures a TUI app. The only required call is [root], naming the ViewModel whose screen opens
 * first. The generated `TuiApp { … }` entry function applies this builder, then builds the
 * kotlin-inject component and hands the resolved root screen to a [TuiHost].
 */
public class TuiAppBuilder {
    @PublishedApi
    internal var rootViewModel: KClass<*>? = null

    @PublishedApi
    internal var rootArgs: Any? = null

    /** Names the starting screen, e.g. `root<FeedViewModel>()`. */
    public inline fun <reified T : Any> root() {
        rootViewModel = T::class
        rootArgs = null
    }

    /** Names a starting screen whose implementation requires assisted navigation [args]. */
    public inline fun <reified T : Any> root(args: Any) {
        rootViewModel = T::class
        rootArgs = args
    }

    /** The configured root ViewModel class, or throws if [root] was never called. */
    public fun requireRoot(): KClass<*> =
        rootViewModel ?: error("TuiApp requires a root screen: call root<YourViewModel>() inside TuiApp { }")

    /** Assisted arguments supplied by the root declaration, or null for an argument-free root. */
    public fun configuredRootArgs(): Any? = rootArgs
}
