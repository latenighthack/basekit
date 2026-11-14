package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, on each Apple pass, `BasekitAppleViewModels.kt`: the Kotlin Swift builds view models through.
 * The factories, the `Observing…` decorators of view models and navigators, and `observing` are hidden
 * from Objective-C, so their bridges are not compiled; these functions build each view model with them.
 *
 * - One function per destination, `make{Case}` for its `BasekitRoute` case (`makeHome`): the destination's view model from
 *   its args and responder, with its navigator reporting to [com.latenighthack.basekit.navigation.NavigationObserver]
 *   and the host under `ownerId`, decorated with `observing(viewModelObserver)`.
 * - One per `@HostedComponent` built from its navigator alone, the same way (`makeShell`).
 *
 * The `make` prefix keeps names Objective-C treats specially, such as `new…` and `init…`, as they are.
 */
class AppleViewModelsGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val navigationPackage: String,
) {
    fun generate(factories: List<ViewModelFactoryInfo>, routes: List<Pair<ViewModelFactoryInfo, FactoryRoute>>) {
        val hosted = if (routes.isEmpty()) emptyList() else factories.filter { it.hostedNavigator != null && it.specQualifiedName != null }
        if (routes.isEmpty() && hosted.isEmpty()) return
        val factoriesPackage = commonPackage(factories.map { it.implQualifiedName.substringBeforeLast('.', "") })
        val factoriesType = listOf(factoriesPackage, ViewModelFactoriesGenerator.INTERFACE_NAME).filter { it.isNotEmpty() }.joinToString(".")
        val specs = routes.map { it.second.specQualifiedName } + hosted.mapNotNull { it.specQualifiedName }
        val body = buildString {
            if (navigationPackage.isNotEmpty()) {
                appendLine("package $navigationPackage")
                appendLine()
            }
            appendLine("import com.latenighthack.basekit.navigation.NavigationObserver")
            appendLine("import com.latenighthack.basekit.viewmodel.ViewModelObserver")
            // `observing` is an extension declared beside each spec.
            specs.map { it.substringBeforeLast('.', "") }.filter { it.isNotEmpty() }.distinct().sorted()
                .forEach { appendLine("import $it.observing") }
            appendLine()
            appendLine("/**")
            appendLine(" * Builds the view models Swift shows. Each navigator reports to `host` under `ownerId` and to")
            appendLine(" * `navigationObserver`; each view model, its children and rows to `viewModelObserver`.")
            appendLine(" */")
            appendLine("public object BasekitAppleViewModels {")
            routes.sortedBy { it.second.caseName }.forEach { (factory, route) -> append(routeFunction(factory, route, factoriesType)) }
            hosted.sortedBy { it.name }.forEach { append(hostedFunction(it, factoriesType)) }
            appendLine("}")
        }
        codeGenerator.createNewFile(dependencies, navigationPackage, "BasekitAppleViewModels", "kt").use {
            it.write(body.encodeToByteArray())
        }
    }

    private fun routeFunction(factory: ViewModelFactoryInfo, route: FactoryRoute, factoriesType: String): String = buildString {
        val navigates = RouteParameter.NAVIGATOR in route.parameters
        val parameters = buildList {
            add("factories: $factoriesType")
            route.parameters.forEachIndexed { index, parameter ->
                when (parameter) {
                    RouteParameter.ARGS -> add("args: ${factory.parameterTypes[index]}")
                    RouteParameter.RESPONDER -> add("responder: ${factory.parameterTypes[index]}")
                    RouteParameter.NAVIGATOR -> Unit
                }
            }
            if (navigates) {
                add("ownerId: String")
                add("host: BasekitNavigationHost")
                add("navigationObserver: NavigationObserver")
            }
            add("viewModelObserver: ViewModelObserver?")
        }
        val arguments = route.parameters.joinToString(", ") { parameter ->
            when (parameter) {
                RouteParameter.ARGS -> "args"
                RouteParameter.RESPONDER -> "responder"
                RouteParameter.NAVIGATOR -> "${route.observingNavigatorType}(${route.navigatorType}(ownerId, host), navigationObserver)"
            }
        }
        appendLine()
        appendLine("    /** The `${route.caseName}` destination's view model. */")
        appendLine("    public fun ${appleViewModelFunction(route.caseName)}(")
        parameters.forEach { appendLine("        $it,") }
        appendLine("    ): ${route.specQualifiedName} {")
        appendLine("        val model: ${route.specQualifiedName} = factories.${factory.name}($arguments)")
        appendLine("        return model.observing(viewModelObserver)")
        appendLine("    }")
    }

    private fun hostedFunction(factory: ViewModelFactoryInfo, factoriesType: String): String = buildString {
        val cap = factory.hostedNavigator!!
        val spec = factory.specQualifiedName!!
        appendLine()
        appendLine("    /** The hosted `$cap` component's view model, its navigator reporting to [host] under [ownerId]. */")
        appendLine("    public fun ${appleViewModelFunction(cap)}(")
        appendLine("        factories: $factoriesType,")
        appendLine("        ownerId: String,")
        appendLine("        host: BasekitNavigationHost,")
        appendLine("        navigationObserver: NavigationObserver,")
        appendLine("        viewModelObserver: ViewModelObserver?,")
        appendLine("    ): $spec {")
        appendLine("        val model: $spec = factories.${factory.name}(Observing${cap}Navigator(Host${cap}Navigator(ownerId, host), navigationObserver))")
        appendLine("        return model.observing(viewModelObserver)")
        appendLine("    }")
    }
}

/** The `BasekitAppleViewModels` function that builds [name]'s view model: `makeHome` for `home`. */
internal fun appleViewModelFunction(name: String): String = "make" + name.replaceFirstChar { it.uppercase() }
