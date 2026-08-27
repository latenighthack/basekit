package com.latenighthack.basekit.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Navigator interface calls cross into platform UI (UIKit on iOS, AppKit on macOS), which must run on
 * the main thread — modifying a view or pushing a controller off the main thread traps. ViewModel
 * actions, however, run on whatever coroutine dispatcher launched them (typically a background one), so
 * a `navigateTo…`/`close` invoked straight from an action would touch UI off the main thread.
 *
 * The generated `Apple<X>Navigator` routes every host call through [runOnMainThread] so navigation is
 * always delivered to the platform on the main thread, regardless of the dispatcher the action ran on.
 */
private val mainThreadScope = CoroutineScope(Dispatchers.Main.immediate)

/**
 * Runs [block] on the main thread: inline when the caller is already on it (via
 * `Dispatchers.Main.immediate`), otherwise posted to the main thread. Fire-and-forget — navigation
 * host calls return no value and the caller does not wait for delivery.
 */
public fun runOnMainThread(block: () -> Unit) {
    mainThreadScope.launch { block() }
}
