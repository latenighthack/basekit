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

/**
 * How a `State` property is drawn in the state table. `AUTO` keeps the default text value; `TOGGLE`
 * renders a `Boolean` as `[x]`/`[ ]`; `BAR` renders a number as a filled gauge sized by [TuiField.max];
 * `HIDDEN` drops the row entirely. `TEXT` is the explicit form of the default.
 */
public enum class TuiRenderAs { AUTO, TEXT, TOGGLE, BAR, HIDDEN }

/** A text transform applied to a field's displayed value. `NONE` leaves it unchanged. */
public enum class TuiTransform { NONE, UPPERCASE, LOWERCASE, TITLE_CASE }

/**
 * Render hint for one `State` property (a row in the generated state table). All arguments are optional;
 * the defaults reproduce today's behaviour (the property name as the label, its `toString()` as the value).
 *
 * @param label overrides the row's field name; empty means derive it from the property name.
 * @param render selects the widget the value is drawn with (see [TuiRenderAs]).
 * @param transform applies a text transform to the displayed value (see [TuiTransform]).
 * @param max the upper bound used when [render] is [TuiRenderAs.BAR]; ignored otherwise.
 */
@Target(AnnotationTarget.PROPERTY)
public annotation class TuiField(
    val label: String = "",
    val render: TuiRenderAs = TuiRenderAs.AUTO,
    val transform: TuiTransform = TuiTransform.NONE,
    val max: Int = 0,
)

/**
 * Render hint for an action (a zero-arg suspend function) or a mutation (a single Bool/String-arg suspend
 * function). All arguments are optional.
 *
 * @param label overrides the label shown in the actions bar; empty means use the method name.
 * @param key pins the trigger key; a space means the processor auto-assigns one as it does today.
 * @param hidden omits the element from the actions bar and binds no key to it.
 */
@Target(AnnotationTarget.FUNCTION)
public annotation class TuiAction(
    val label: String = "",
    val key: Char = ' ',
    val hidden: Boolean = false,
)

/**
 * Merges a `Boolean` mutation with the `Boolean` `State` property it sets into a single interactive
 * toggle: the [field] row renders as `[x]`/`[ ]`, and the mutation's key flips it by calling the mutation
 * with the negated current value instead of opening a true/false prompt. Apply to the single-`Boolean`-arg
 * suspend function; [field] is the name of the `State` property it toggles.
 */
@Target(AnnotationTarget.FUNCTION)
public annotation class TuiToggle(val field: String)

/**
 * Render hint for the `@ViewModelList` property.
 *
 * @param label overrides the list title; empty means use the property name.
 */
@Target(AnnotationTarget.PROPERTY)
public annotation class TuiList(val label: String = "")

/**
 * Controls how a `@ChildViewModel` is embedded in its parent's generated terminal screen.
 *
 * When [visibleWhenField] and [visibleWhenValue] are both non-empty, the child is rendered only
 * while the named parent-state property's `toString()` equals [visibleWhenValue]. Empty values keep
 * the child permanently visible. This deliberately uses display values rather than a concrete enum
 * type so the annotation remains useful for enums, booleans, and strings without coupling the TUI
 * annotations slice to an application's state types.
 */
@Target(AnnotationTarget.PROPERTY)
public annotation class TuiChild(
    val label: String = "",
    val visibleWhenField: String = "",
    val visibleWhenValue: String = "",
)
