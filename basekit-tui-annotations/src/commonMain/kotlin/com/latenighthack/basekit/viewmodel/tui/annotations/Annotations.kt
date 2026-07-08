package com.latenighthack.basekit.viewmodel.tui.annotations

import kotlin.reflect.KClass

/**
 * Binds a `@ViewModelSpec` to a navigation `@Destination`, making it a TUI screen. The `tui` processor
 * generates a TamboUI screen for the ViewModel (state -> table, `@ViewModelList` -> list, zero-arg
 * actions -> key-bound buttons) plus a `…Navigator` implementation for the destination's generated
 * navigator interface, which it injects into the ViewModel so the ViewModel can navigate by calling
 * `navigateTo…` explicitly. Navigation is never inferred from the graph — the graph only defines the
 * navigator interfaces and tracks call sites.
 *
 * @param destination the `@Destination` interface this ViewModel renders as.
 * @param implementation optionally the concrete ViewModel implementation to construct; when left as
 *   the default the processor discovers the single class in the scanned package that implements the
 *   ViewModel interface.
 */
@Target(AnnotationTarget.CLASS)
public annotation class TuiScreen(
    val destination: KClass<*>,
    val implementation: KClass<*> = Unit::class,
)
