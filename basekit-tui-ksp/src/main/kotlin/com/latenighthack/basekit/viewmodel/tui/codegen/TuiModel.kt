package com.latenighthack.basekit.viewmodel.tui.codegen

/** One property of a ViewModel's `State` type (used to build the state table / list rows). */
data class StateProp(val name: String, val typeSimpleName: String)

/** A zero-arg suspend action, with the key that triggers it in the TUI. */
data class Action(val name: String, val key: Char)

/**
 * A `@ViewModelList` property: a `Flow<Delta<ElementVm>>` of child ViewModels rendered as a list.
 * [selectionAction] is the zero-arg suspend method invoked on the selected element when Enter is
 * pressed (null when the element has no such action). The element decides what that does — navigation,
 * if any, happens by the element calling its injected navigator, never here.
 */
data class ListInfo(
    val propertyName: String,
    val elementQualifiedName: String,
    val elementStateProps: List<StateProp>,
    val selectionAction: String?,
)

/**
 * A `navigateTo…` method the generated `…Navigator` implementation must provide (one per distinct
 * outbound destination). The implementation delegates to the screen's [TuiNavigation] to push.
 */
data class NavMethod(
    val methodName: String,
    val targetDestQualifiedName: String,
    val argsType: String?,
    val sourceType: String?,
)

/** Everything the generators need about one `@ViewModel` bound via `@TuiScreen`. */
data class ScreenInfo(
    val vmSimpleName: String,
    val vmQualifiedName: String,
    val implQualifiedName: String,
    val stateQualifiedName: String,
    val stateProps: List<StateProp>,
    val actions: List<Action>,
    val list: ListInfo?,
    val destQualifiedName: String,
    val navigatorInterface: String?,
    val navMethods: List<NavMethod>,
) {
    val screenClassName: String get() = "${vmSimpleName}Screen"

    /** The generated `…Navigator` implementation class injected into the ViewModel (null when the screen has no outbound edges). */
    val navigatorClassName: String? get() = navigatorInterface?.let { "${vmSimpleName}TuiNavigator" }
}
