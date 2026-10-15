package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** What one `@Assisted` factory parameter of a destination's view model receives from a navigation entry. */
enum class RouteParameter { ARGS, NAVIGATOR, RESPONDER }

/**
 * A destination whose view model has a factory in `GeneratedViewModelFactories`: [caseName] is its
 * `BasekitRoute` case (`agent`), [parameters] the factory's parameters in order.
 */
data class FactoryRoute(
    val caseName: String,
    val specSimpleName: String,
    val navigatorType: String,
    val hasArgs: Boolean,
    val responding: Boolean,
    val parameters: List<RouteParameter>,
)

/**
 * Emits `BasekitDestinationModels.swift`: `BasekitDestinationModel`, one case per destination with a
 * factory carrying its `Observable{Spec}`, and `basekitViewModel(for:host:factories:)`, which builds it
 * from a navigation entry.
 *
 * The function is pure: it creates the view model and nothing else, so the host keeps the view, the
 * presentation and the model's lifetime (`@StateObject`). The navigator always reports to [host] under
 * `entry.ownerID`, the owner the router closes the entry by. A responding destination whose entry has
 * no responder, and a destination without a factory, throw [BasekitDestinationModelError].
 */
class DestinationModelGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val frameworkImports: List<String>,
) {
    fun generate(routes: List<Pair<ViewModelFactoryInfo, FactoryRoute>>, allCaseNames: Set<String>) {
        if (routes.isEmpty()) return
        val sorted = routes.sortedBy { it.second.caseName }
        val body = buildString {
            for (framework in frameworkImports) appendLine("@_spi(BaseKitCodegen) import $framework")
            appendLine("import Foundation")
            appendLine()
            appendLine("/// A destination's view model, built by `basekitViewModel(for:host:factories:)`.")
            appendLine("@available(iOS 18.0, macOS 15.0, *)")
            appendLine("public enum BasekitDestinationModel {")
            sorted.forEach { (_, route) -> appendLine("    case ${route.caseName}(Observable${route.specSimpleName})") }
            appendLine()
            appendLine("    /// The view model, for code that only observes it.")
            appendLine("    @MainActor public var model: any BasekitObservableViewModel {")
            appendLine("        switch self {")
            sorted.forEach { (_, route) -> appendLine("        case .${route.caseName}(let model): return model") }
            appendLine("        }")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("/// Why `basekitViewModel(for:host:factories:)` could not build a destination's view model.")
            appendLine("public enum BasekitDestinationModelError: Error, Equatable {")
            appendLine("    /// The entry is for a responding destination but carries no responder, as with a deep link to it.")
            appendLine("    case missingResponder(destination: String)")
            appendLine("    /// No `@ViewModelInject` implementation builds this destination's view model.")
            appendLine("    case noFactory(destination: String)")
            appendLine("}")
            appendLine()
            appendLine("/// Builds the view model for [entry]'s destination. Pure: it creates no view and keeps nothing,")
            appendLine("/// so the host owns presentation and the model's lifetime. The navigator reports to [host] under")
            appendLine("/// `entry.ownerID`, which is the owner the router closes the entry by.")
            appendLine("@available(iOS 18.0, macOS 15.0, *)")
            appendLine("@MainActor")
            appendLine("public func basekitViewModel(")
            appendLine("    for entry: BasekitNavigationEntry,")
            appendLine("    host: AppleNavigationHost,")
            appendLine("    factories: GeneratedViewModelFactories")
            appendLine(") throws(BasekitDestinationModelError) -> BasekitDestinationModel {")
            appendLine("    switch entry.route {")
            sorted.forEach { (factory, route) -> append(routeCase(factory, route)) }
            if (allCaseNames.any { name -> sorted.none { it.second.caseName == name } }) {
                appendLine("    default:")
                appendLine("        throw .noFactory(destination: \"\\(entry.route)\")")
            }
            appendLine("    }")
            appendLine("}")
        }
        codeGenerator.createNewFile(dependencies, "", "BasekitDestinationModels", "swift").use {
            it.write(body.encodeToByteArray())
        }
    }

    private fun routeCase(factory: ViewModelFactoryInfo, route: FactoryRoute): String = buildString {
        val bindsArgs = RouteParameter.ARGS in route.parameters
        val bindsResponder = RouteParameter.RESPONDER in route.parameters
        val pattern = buildList {
            if (route.hasArgs) add(if (bindsArgs) "let args" else "_")
            add("_")
            if (route.responding) add(if (bindsResponder) "let responder" else "_")
        }
        val head = if (bindsArgs || bindsResponder) ".${route.caseName}(${pattern.joinToString(", ")})" else ".${route.caseName}"
        appendLine("    case $head:")
        if (bindsResponder) {
            appendLine("        guard let responder else { throw .missingResponder(destination: \"${route.caseName}\") }")
        }
        val arguments = route.parameters.joinToString(", ") { parameter ->
            when (parameter) {
                RouteParameter.ARGS -> "args"
                RouteParameter.NAVIGATOR -> "${route.navigatorType}(ownerId: entry.ownerID, host: host)"
                RouteParameter.RESPONDER -> "responder"
            }
        }
        appendLine("        return .${route.caseName}(Observable${route.specSimpleName}(factories.${factory.name}($arguments)))")
    }
}
