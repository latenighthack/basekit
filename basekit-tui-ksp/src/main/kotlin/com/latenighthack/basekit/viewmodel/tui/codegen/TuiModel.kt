package com.latenighthack.basekit.viewmodel.tui.codegen

/** One property of a ViewModel's `State` type (used to build the state table / list rows). */
data class StateProp(val name: String, val typeSimpleName: String)

/** A zero-arg suspend action, with the key that triggers it in the TUI. */
data class Action(val name: String, val key: Char)

/** A `@ViewModelList` property: a `Flow<Delta<ElementVm>>` of child ViewModels rendered as a list. */
data class ListInfo(
    val propertyName: String,
    val elementQualifiedName: String,
    val elementStateProps: List<StateProp>,
)

/** A `navigateTo…` method the screen must override (one per distinct outbound destination). */
data class NavMethod(
    val methodName: String,
    val targetDestQualifiedName: String,
    val argsType: String?,
    val sourceType: String?,
)

/** Concrete navigation performed when Enter activates a list row. */
data class RowNav(
    val methodName: String,
    val argsType: String?,
    val sourceConstant: String?,
    /** (routeArgName, "item.initialState.<prop>") assignments; empty when the Args has no route args. */
    val argAssignments: List<Pair<String, String>>,
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
    val rowNav: RowNav?,
) {
    val screenClassName: String get() = "${vmSimpleName}Screen"
}
