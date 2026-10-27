package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits `BasekitDestinationModels.kt` for Kotlin/JS: the web counterpart of
 * [ComposeDestinationModelGenerator], beside the navigation processor's `BasekitReactNavigation.kt`.
 *
 * - `BasekitDestinationModel` and `basekitViewModel(entry, host, factories)`, as on Android: pure, the
 *   navigator reporting to the host under `entry.ownerId` and, when the host is the generated router,
 *   to its observers.
 * - `BasekitWebDestinations` (exported), made in Kotlin with `basekitWebDestinations(factories)`: its
 *   `reference(entry)` builds the entry's view model once and gives a `BasekitDestinationRef`, `kind`
 *   (the `BasekitRoute` case) with `ref`, the destination's `{Spec}ReactRef`. The reference lives on the
 *   entry, so it goes when the entry leaves its router.
 * - `useBasekitNavigation(router)`, a router's snapshot read with `useSyncExternalStore`, and
 *   `BasekitNavigationStack`, which shows a router's root and path, every entry kept mounted with all
 *   but the top one hidden, and its sheet and cover, each a stack of its own for the router made for
 *   it, so what a sheet opens is presented over it.
 */
class ReactDestinationModelGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val navigationPackage: String,
    private val factoriesPackage: String,
) {
    fun generate(routes: List<Pair<ViewModelFactoryInfo, FactoryRoute>>) {
        if (routes.isEmpty()) return
        val sorted = routes.sortedBy { it.second.caseName }
        val factories = listOf(factoriesPackage, "GeneratedViewModelFactories").filter { it.isNotEmpty() }.joinToString(".")
        val body = buildString {
            appendLine("@file:Suppress(\"NON_EXPORTABLE_TYPE\")")
            appendLine("@file:OptIn(ExperimentalJsExport::class)")
            appendLine()
            if (navigationPackage.isNotEmpty()) {
                appendLine("package $navigationPackage")
                appendLine()
            }
            appendLine("import com.latenighthack.basekit.navigation.NavigationObserver")
            appendLine("import com.latenighthack.basekit.viewmodel.React")
            appendLine("import com.latenighthack.basekit.viewmodel.ViewModelActionObserver")
            appendLine("import com.latenighthack.basekit.viewmodel.ViewModelObservers")
            // `observing` and `reactReference` are extensions declared beside each spec.
            sorted.map { (_, route) -> route.specPackage }.filter { it.isNotEmpty() }.distinct().sorted().forEach {
                appendLine("import $it.observing")
                appendLine("import $it.reactReference")
            }
            appendLine("import kotlin.js.ExperimentalJsExport")
            appendLine("import kotlin.js.JsExport")
            appendLine("import kotlin.js.JsName")
            appendLine()
            appendLine("/** A destination's view model, built by `basekitViewModel(entry, host, factories)`. */")
            appendLine("public sealed interface BasekitDestinationModel {")
            appendLine("    /** The view model, for code that only observes it. */")
            appendLine("    public val model: com.latenighthack.basekit.viewmodel.ViewModel<*>")
            appendLine()
            sorted.forEach { (_, route) ->
                appendLine("    public class ${route.className}(override val model: ${route.specQualifiedName}) : BasekitDestinationModel")
            }
            appendLine("}")
            appendLine()
            appendLine("/**")
            appendLine(" * Builds the view model for [entry]'s destination. Pure: it creates no screen and keeps nothing, so")
            appendLine(" * the host owns presentation and the model's lifetime. The navigator reports to [host] under")
            appendLine(" * `entry.ownerId`, which is the owner the router closes the entry by. Pass `router.host(entry)`: a")
            appendLine(" * presented entry reports to the router made for it.")
            appendLine(" *")
            appendLine(" * When [host] is a `BasekitNavigationRouter`, the navigator reports to its `navigationObserver` and")
            appendLine(" * the view model, with its bindings, children and rows, to its `viewModelObserver` and its actions to")
            appendLine(" * its `actionObserver`.")
            appendLine(" *")
            appendLine(" * A responding destination's entry must carry a responder. `BasekitNavigationRouter` attaches one to")
            appendLine(" * every entry it presents; an entry built by hand without one fails here.")
            appendLine(" */")
            appendLine("public fun basekitViewModel(")
            appendLine("    entry: BasekitNavigationEntry,")
            appendLine("    host: NavigationHost,")
            appendLine("    factories: $factories,")
            appendLine("): BasekitDestinationModel {")
            appendLine("    // The observers of the router the entry reports to; another kind of host observes nothing.")
            appendLine("    val router = host as? BasekitNavigationRouter")
            appendLine("    val navigationObserver = router?.navigationObserver ?: NavigationObserver.NoOp")
            appendLine("    val actionObserver = router?.actionObserver ?: ViewModelActionObserver.NoOp")
            appendLine("    val viewModelObserver = ViewModelObservers.combined(router?.viewModelObserver, actionObserver)")
            appendLine("    return when (val route = entry.route) {")
            sorted.forEach { (factory, route) -> appendLine(routeCase(factory, route)) }
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("/** A destination's React reference: [kind] is its `BasekitRoute` case, such as `detail`, and [ref]")
            appendLine(" * its `{Spec}ReactRef`, for the destination's generated hook. */")
            appendLine("@JsExport")
            appendLine("public class BasekitDestinationRef internal constructor(public val kind: String, public val ref: Any)")
            appendLine()
            appendLine("/** Builds the destinations' view models from [factories], one per entry. */")
            appendLine("@JsExport")
            appendLine("public class BasekitWebDestinations internal constructor(private val factories: $factories) {")
            appendLine("    /** The reference of [entry]'s destination: built with its view model the first time, then the same")
            appendLine("     * one while the entry stays in its router. The navigator reports to `entry.host`. */")
            appendLine("    public fun reference(entry: BasekitWebEntry): BasekitDestinationRef {")
            appendLine("        (entry.model as? BasekitDestinationRef)?.let { return it }")
            appendLine("        val reference = when (val model = basekitViewModel(entry.entry, entry.host.router, factories)) {")
            sorted.forEach { (_, route) ->
                appendLine("            is BasekitDestinationModel.${route.className} -> BasekitDestinationRef(\"${route.caseName}\", model.model.reactReference())")
            }
            appendLine("        }")
            appendLine("        entry.model = reference")
            appendLine("        return reference")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("/** The web destinations for an app's [factories]; export it from the app's Kotlin/JS client. */")
            appendLine("public fun basekitWebDestinations(factories: $factories): BasekitWebDestinations = BasekitWebDestinations(factories)")
            appendLine()
            appendLine("/** [router]'s path, sheet and cover, rendering again when they change. */")
            appendLine("@JsExport")
            appendLine("@JsName(\"useBasekitNavigation\")")
            appendLine("public fun useBasekitNavigation(router: BasekitWebRouter): BasekitWebStack =")
            appendLine("    React.useSyncExternalStore(router.subscribe, router.getSnapshot).unsafeCast<BasekitWebStack>()")
            appendLine()
            appendLine("/**")
            appendLine(" * Shows `props.router`'s stack over `props.root`: `props.render(destination, entry)` draws each entry")
            appendLine(" * from its `BasekitDestinationRef`, made by `props.destinations`. Every entry stays mounted, those")
            appendLine(" * under the top one hidden, so a screen and its view model outlive the screens pushed over it. Each")
            appendLine(" * entry reports itself shown (`didShow`) as it mounts.")
            appendLine(" *")
            appendLine(" * `props.sheet` and `props.fullScreenCover`, `(entry, dismiss, content)`, present the router's sheet")
            appendLine(" * and cover; each defaults to a modal `<dialog>` that Escape dismisses. `content` is a stack of its")
            appendLine(" * own for the router made for the entry, with the entry at its root.")
            appendLine(" */")
            appendLine("@JsExport")
            appendLine("@JsName(\"BasekitNavigationStack\")")
            appendLine("public fun BasekitNavigationStack(props: dynamic): dynamic = basekitStack(props)")
            appendLine()
            appendLine("private val basekitStack: dynamic = { props: dynamic ->")
            appendLine("    val router = props.router.unsafeCast<BasekitWebRouter>()")
            appendLine("    val stack = useBasekitNavigation(router)")
            appendLine("    val entries = listOf(props.root.unsafeCast<BasekitWebEntry>()) + stack.path")
            appendLine("    val shown = entries.mapIndexed { index, entry ->")
            appendLine("        React.createElement(basekitEntryView, basekitProps(")
            appendLine("            \"key\" to entry.id, \"router\" to router, \"entry\" to entry, \"hidden\" to (index != entries.lastIndex),")
            appendLine("            \"destinations\" to props.destinations, \"render\" to props.render,")
            appendLine("        ))")
            appendLine("    }")
            appendLine("    val sheet = stack.sheet?.let { basekitPresented(props, router, it, props.sheet) }")
            appendLine("    val cover = stack.fullScreenCover?.let { basekitPresented(props, router, it, props.fullScreenCover) }")
            appendLine("    React.createElement(React.Fragment, null, *(shown + listOfNotNull(sheet, cover)).toTypedArray())")
            appendLine("}")
            appendLine()
            appendLine("private val basekitEntryView: dynamic = { props: dynamic ->")
            appendLine("    val router = props.router.unsafeCast<BasekitWebRouter>()")
            appendLine("    val entry = props.entry.unsafeCast<BasekitWebEntry>()")
            appendLine("    val destination = props.destinations.unsafeCast<BasekitWebDestinations>().reference(entry)")
            appendLine("    React.useEffect({ router.didShow(entry); undefined }, arrayOf(router, entry))")
            appendLine("    React.createElement(\"div\", basekitProps(\"hidden\" to props.hidden, \"data-basekit-entry\" to entry.id, \"data-basekit-screen\" to entry.screen), props.render(destination, entry))")
            appendLine("}")
            appendLine()
            appendLine("/** [entry], presented by [router] as a sheet or cover, through [present] or a modal `<dialog>`. */")
            appendLine("private fun basekitPresented(props: dynamic, router: BasekitWebRouter, entry: BasekitWebEntry, present: dynamic): dynamic {")
            appendLine("    val content = React.createElement(basekitStack, basekitProps(")
            appendLine("        \"router\" to entry.host, \"root\" to entry, \"destinations\" to props.destinations, \"render\" to props.render,")
            appendLine("        \"sheet\" to props.sheet, \"fullScreenCover\" to props.fullScreenCover,")
            appendLine("    ))")
            appendLine("    val dismiss: () -> Unit = { router.dismiss(entry) }")
            appendLine("    val presented = if (present == null) basekitDialog(entry, dismiss, content) else present(entry, dismiss, content)")
            appendLine("    return React.createElement(React.Fragment, basekitProps(\"key\" to entry.id), presented)")
            appendLine("}")
            appendLine()
            appendLine("private fun basekitDialog(entry: BasekitWebEntry, dismiss: () -> Unit, content: dynamic): dynamic {")
            appendLine("    val showModal: (dynamic) -> Unit = { element -> if (element != null && !element.open.unsafeCast<Boolean>()) element.showModal() }")
            appendLine("    val cancel: (dynamic) -> Unit = { event -> event.preventDefault(); dismiss() }")
            appendLine("    return React.createElement(\"dialog\", basekitProps(\"ref\" to showModal, \"onCancel\" to cancel, \"data-basekit-presentation\" to entry.presentation), content)")
            appendLine("}")
            appendLine()
            appendLine("private fun basekitProps(vararg values: Pair<String, Any?>): dynamic {")
            appendLine("    val props: dynamic = js(\"({})\")")
            appendLine("    values.forEach { (name, value) -> props[name] = value }")
            appendLine("    return props")
            appendLine("}")
        }
        codeGenerator.createNewFile(dependencies, navigationPackage, "BasekitDestinationModels", "kt").use {
            it.write(body.encodeToByteArray())
        }
    }

    private fun routeCase(factory: ViewModelFactoryInfo, route: FactoryRoute): String {
        val arguments = route.parameters.joinToString(", ") { parameter ->
            when (parameter) {
                RouteParameter.ARGS -> "route.args"
                RouteParameter.NAVIGATOR -> "${route.observingNavigatorType}(${route.navigatorType}(entry.ownerId, host), navigationObserver)"
                RouteParameter.RESPONDER ->
                    "requireNotNull(route.responder) { \"basekitViewModel: the ${route.caseName} entry has no responder. " +
                        "BasekitNavigationRouter attaches one to every entry it presents; an entry built by hand must carry one.\" }"
            }
        }
        return "        is BasekitRoute.${route.className} -> BasekitDestinationModel.${route.className}(factories.${factory.name}($arguments).observing(viewModelObserver))"
    }

    private val FactoryRoute.className: String get() = caseName.replaceFirstChar { it.uppercase() }
    private val FactoryRoute.specPackage: String get() = specQualifiedName.substringBeforeLast('.', "")
}
