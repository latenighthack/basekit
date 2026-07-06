package com.latenighthack.basekit.viewmodel.tui.annotations

import kotlin.reflect.KClass

/**
 * Binds a `@ViewModel` to a navigation `@Destination`, making it a TUI screen. The `tui` processor
 * generates a TamboUI screen for the ViewModel (state -> table, `@ViewModelList` -> list, zero-arg
 * actions -> key-bound buttons) and implements the destination's generated `…Navigator` interface so
 * the screen can move between screens via the navigation slice.
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

/**
 * Binds a clickable element to a specific navigation edge of the owning screen's destination. Placed
 * on a `@ViewModelList` property (Enter on a selected row navigates) or a zero-arg action. When
 * omitted and the destination has exactly one outbound `@NavigateTo` edge, the binding is inferred.
 *
 * @param destination the target `@Destination` to navigate to.
 * @param source optional name of the generated `…Source` enum constant to pass when the destination
 *   has more than one inbound call site; empty selects the first.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
public annotation class TuiNavigatesTo(
    val destination: KClass<*>,
    val source: String = "",
)
