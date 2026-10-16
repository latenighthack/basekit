package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits `BasekitDestinationModels.kt` for Android: the Compose counterpart of
 * [DestinationModelGenerator]'s Swift. `BasekitDestinationModel`, one class per destination with a
 * factory carrying its view model; `basekitViewModel(entry, host, factories)`, which builds it from a
 * navigation entry; `BasekitDestinationBinding`, the model bound for Compose, as the Swift cases carry
 * their `Observable` wrappers; and `BasekitDestinationView`, which builds the model once per
 * composition of the entry and observes it, through its binding, while shown.
 *
 * `basekitViewModel` is pure, as on Apple platforms: it creates the view model and nothing else, so
 * the host keeps the screen, the presentation and the model's lifetime. The navigator reports to the
 * host under `entry.ownerId`.
 */
class ComposeDestinationModelGenerator(
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
            if (navigationPackage.isNotEmpty()) {
                appendLine("package $navigationPackage")
                appendLine()
            }
            appendLine("import androidx.compose.runtime.Composable")
            appendLine("import androidx.compose.runtime.remember")
            appendLine("import kotlinx.coroutines.Job")
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
            appendLine(" * `entry.ownerId`, which is the owner the router closes the entry by.")
            appendLine(" *")
            appendLine(" * A responding destination's entry must carry a responder. `BasekitNavigationRouter` attaches one to")
            appendLine(" * every entry it presents; an entry built by hand without one fails here.")
            appendLine(" */")
            appendLine("public fun basekitViewModel(")
            appendLine("    entry: BasekitNavigationEntry,")
            appendLine("    host: AppleNavigationHost,")
            appendLine("    factories: $factories,")
            appendLine("): BasekitDestinationModel = when (val route = entry.route) {")
            sorted.forEach { (factory, route) -> appendLine(routeCase(factory, route)) }
            appendLine("}")
            appendLine()
            appendLine("/** A destination's view model bound for Compose: its state observed while composed, and its actions. */")
            appendLine("public sealed interface BasekitDestinationBinding {")
            sorted.forEach { (_, route) ->
                appendLine("    public class ${route.className}(public val binding: ${route.bindingType}) : BasekitDestinationBinding")
            }
            appendLine("}")
            appendLine()
            appendLine("/** Binds [model]: observes its state while composed, and runs its actions with [launchAction]. */")
            appendLine("@Composable")
            appendLine("public fun bindDestination(model: BasekitDestinationModel, launchAction: (suspend () -> Unit) -> Job): BasekitDestinationBinding = when (model) {")
            sorted.forEach { (_, route) ->
                appendLine("    is BasekitDestinationModel.${route.className} -> BasekitDestinationBinding.${route.className}(${route.bindFunction}(model.model, launchAction))")
            }
            appendLine("}")
            appendLine()
            appendLine("/**")
            appendLine(" * Builds [entry]'s view model once while this stays in the composition, observes it while shown, and")
            appendLine(" * shows [content] for it. It decides nothing about presentation: put it in a stack, sheet or dialog")
            appendLine(" * as the host likes. [launchAction] runs the screen's actions.")
            appendLine(" */")
            appendLine("@Composable")
            appendLine("public fun BasekitDestinationView(")
            appendLine("    entry: BasekitNavigationEntry,")
            appendLine("    host: AppleNavigationHost,")
            appendLine("    factories: $factories,")
            appendLine("    launchAction: (suspend () -> Unit) -> Job,")
            appendLine("    content: @Composable (BasekitDestinationBinding) -> Unit,")
            appendLine(") {")
            appendLine("    val model = remember(entry.id) { basekitViewModel(entry, host, factories) }")
            appendLine("    content(bindDestination(model, launchAction))")
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
                RouteParameter.NAVIGATOR -> "${route.navigatorType}(entry.ownerId, host)"
                RouteParameter.RESPONDER ->
                    "requireNotNull(route.responder) { \"basekitViewModel: the ${route.caseName} entry has no responder. " +
                        "BasekitNavigationRouter attaches one to every entry it presents; an entry built by hand must carry one.\" }"
            }
        }
        return "    is BasekitRoute.${route.className} -> BasekitDestinationModel.${route.className}(factories.${factory.name}($arguments))"
    }

    private val FactoryRoute.className: String get() = caseName.replaceFirstChar { it.uppercase() }
    private val FactoryRoute.specPackage: String get() = specQualifiedName.substringBeforeLast('.', "")
    private val FactoryRoute.bindingType: String get() = listOf(specPackage, "${specSimpleName}Binding").filter { it.isNotEmpty() }.joinToString(".")
    private val FactoryRoute.bindFunction: String get() = listOf(specPackage, "bind$specSimpleName").filter { it.isNotEmpty() }.joinToString(".")
}
