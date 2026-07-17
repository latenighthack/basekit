package com.latenighthack.basekit.viewmodel.tui.codegen

/** One property of a ViewModel's `State` type (used to build the state table / list rows). */
data class StateProp(val name: String, val typeSimpleName: String)

/** A zero-arg suspend action, with the key that triggers it in the TUI. */
data class Action(val name: String, val key: Char)

/** The single argument a [Mutation] method takes, deciding how the TUI collects its value. */
enum class MutationParamKind { BOOL, STRING }

/**
 * A single-argument suspend method exposed as a mutation. Pressing [key] opens a prompt that collects
 * the argument — `true`/`false` for [MutationParamKind.BOOL], typed text for [MutationParamKind.STRING] —
 * and then invokes the method with the collected value.
 */
data class Mutation(val name: String, val key: Char, val paramKind: MutationParamKind)

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
    // Non-null when the target is a RespondingDestination: the method is `suspend` and returns `R?`.
    val responseType: String?,
)

/**
 * The single kotlin-inject `@Assisted` constructor parameter a screen's impl takes, supplied by the
 * component at build time. Each screen has at most one: the per-screen [NAVIGATOR], the navigation
 * [ARGS], or the [RESPONDER] for a responding destination. [NONE] means the impl is built straight from
 * the graph (widened by GeneratedViewModelModule).
 */
enum class AssistedKind { NONE, NAVIGATOR, ARGS, RESPONDER }

/**
 * One kotlin-inject `@Assisted` constructor parameter of a screen impl, in declaration order. A screen
 * may take several (e.g. both navigation [ARGS] and its per-screen [NAVIGATOR]); the component supplies
 * each from the matching source when it builds the screen. [type] is the factory parameter type.
 */
data class AssistedParam(val kind: AssistedKind, val type: String)

/** Everything the generators need about one `@ViewModelSpec` bound via `@TuiScreen`. */
data class ScreenInfo(
    val vmSimpleName: String,
    val vmQualifiedName: String,
    val implQualifiedName: String,
    // True when the concrete impl is `@ViewModelInject`: the component builds it through the kotlin-inject
    // graph rather than calling its constructor directly.
    val injected: Boolean,
    val stateQualifiedName: String,
    val stateProps: List<StateProp>,
    val actions: List<Action>,
    // Single-argument suspend methods, each opened by [Mutation.key] into a prompt that collects the
    // argument (true/false, or typed text) before the method is called.
    val mutations: List<Mutation>,
    val list: ListInfo?,
    val destQualifiedName: String,
    // The impl's `@Assisted` parameters in constructor order, each supplied by the component per screen
    // build (navigator / navigation args / responder). Empty when the impl is built straight from the graph.
    val assisted: List<AssistedParam>,
    val navigatorInterface: String?,
    val navMethods: List<NavMethod>,
) {
    val screenClassName: String get() = "${vmSimpleName}Screen"

    /** The generated `…Navigator` implementation class injected into the ViewModel (null when the screen has no outbound edges). */
    val navigatorClassName: String? get() = navigatorInterface?.let { "${vmSimpleName}TuiNavigator" }

    /** The kotlin-inject `(Assisted...) -> Impl` factory accessor name on the component (null when none). */
    val factoryName: String? get() = if (assisted.isEmpty()) null else "${vmSimpleName.replaceFirstChar { it.lowercase() }}Factory"
}
