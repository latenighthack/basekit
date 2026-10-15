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
 * factory carrying its `Observable{Spec}`; `basekitViewModel(for:host:factories:)`, which builds it
 * from a navigation entry; and `BasekitDestinationView`, which builds it once per view identity and
 * observes it while shown.
 *
 * `basekitViewModel` is pure: it creates the view model and nothing else, so the host keeps the view,
 * the presentation and the model's lifetime. The navigator always reports to the host under
 * `entry.ownerID`, the owner the router closes the entry by. It does not throw: every destination has
 * a factory (the processor fails the build otherwise, see [missingDestinationFactories]), and the
 * router gives every responding entry it presents a responder, deep links included. An entry built by
 * hand for a responding destination without one stops at a `preconditionFailure`.
 */
class DestinationModelGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val frameworkImports: List<String>,
) {
    fun generate(routes: List<Pair<ViewModelFactoryInfo, FactoryRoute>>) {
        if (routes.isEmpty()) return
        val sorted = routes.sortedBy { it.second.caseName }
        val body = buildString {
            for (framework in frameworkImports) appendLine("@_spi(BaseKitCodegen) import $framework")
            appendLine("import Foundation")
            appendLine("import SwiftUI")
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
            appendLine("/// Builds the view model for [entry]'s destination. Pure: it creates no view and keeps nothing,")
            appendLine("/// so the host owns presentation and the model's lifetime. The navigator reports to [host] under")
            appendLine("/// `entry.ownerID`, which is the owner the router closes the entry by.")
            appendLine("///")
            appendLine("/// A responding destination's entry must carry a responder. `BasekitNavigationRouter` attaches one to")
            appendLine("/// every entry it presents; an entry built by hand without one stops at a `preconditionFailure`.")
            appendLine("@available(iOS 18.0, macOS 15.0, *)")
            appendLine("@MainActor")
            appendLine("public func basekitViewModel(")
            appendLine("    for entry: BasekitNavigationEntry,")
            appendLine("    host: AppleNavigationHost,")
            appendLine("    factories: GeneratedViewModelFactories")
            appendLine(") -> BasekitDestinationModel {")
            appendLine("    switch entry.route {")
            sorted.forEach { (factory, route) -> append(routeCase(factory, route)) }
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("/// Builds [entry]'s view model once per view identity, observes it while shown, and shows [content]")
            appendLine("/// for it. A re-render or layout change that keeps the view's identity keeps the model, as")
            appendLine("/// `BasekitModelView` does. It decides nothing about presentation: wrap it in a stack, sheet or")
            appendLine("/// detents as the host likes.")
            appendLine("@available(iOS 18.0, macOS 15.0, *)")
            appendLine("@MainActor")
            appendLine("public struct BasekitDestinationView<Content: View>: View {")
            appendLine("    @StateObject private var holder: BasekitDestinationModelHolder")
            appendLine("    private let content: (BasekitDestinationModel) -> Content")
            appendLine()
            appendLine("    public init(")
            appendLine("        for entry: BasekitNavigationEntry,")
            appendLine("        host: AppleNavigationHost,")
            appendLine("        factories: GeneratedViewModelFactories,")
            appendLine("        @ViewBuilder content: @escaping (BasekitDestinationModel) -> Content")
            appendLine("    ) {")
            appendLine("        _holder = StateObject(wrappedValue: BasekitDestinationModelHolder(basekitViewModel(for: entry, host: host, factories: factories)))")
            appendLine("        self.content = content")
            appendLine("    }")
            appendLine()
            appendLine("    public var body: some View {")
            appendLine("        content(holder.model).task { await holder.model.model.observe() }")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("/// Keeps `BasekitDestinationView`'s model for the view's identity. Publishes nothing: screens observe")
            appendLine("/// the view model itself.")
            appendLine("@available(iOS 18.0, macOS 15.0, *)")
            appendLine("@MainActor")
            appendLine("final class BasekitDestinationModelHolder: ObservableObject {")
            appendLine("    let model: BasekitDestinationModel")
            appendLine("    init(_ model: BasekitDestinationModel) { self.model = model }")
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
            appendLine("        guard let responder else {")
            appendLine("            preconditionFailure(\"basekitViewModel: the ${route.caseName} entry has no responder. BasekitNavigationRouter attaches one to every entry it presents; an entry built by hand must carry one.\")")
            appendLine("        }")
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

/** A `@Destination` as a build error names it: its `BasekitRoute` case, its spec and where it is declared. */
data class DestinationSite(val caseName: String, val qualifiedName: String, val location: String?)

/**
 * One build error for each of [destinations] that `basekitViewModel(for:host:factories:)` could not
 * build: one without a factory among [factories], or whose factory takes a parameter a navigation entry
 * does not supply.
 */
fun missingDestinationFactories(destinations: Collection<DestinationSite>, factories: List<ViewModelFactoryInfo>): List<String> =
    destinations.sortedBy { it.caseName }.mapNotNull { destination ->
        val where = destination.location?.let { " (declared at $it)" }.orEmpty()
        val factory = factories.firstOrNull { it.name == "${destination.caseName}Factory" }
        when {
            factory == null ->
                "Destination ${destination.qualifiedName}$where has no public @ViewModelInject implementation, so " +
                    "basekitViewModel(for:host:factories:) cannot build it. Add a public @ViewModelInject class that implements it."
            factory.route == null ->
                "Destination ${destination.qualifiedName}$where: its implementation ${factory.implQualifiedName} takes an " +
                    "@Assisted parameter that is not the destination's args, a navigator or its responder " +
                    "(${factory.parameterTypes.joinToString()}), so basekitViewModel(for:host:factories:) cannot build it."
            else -> null
        }
    }
