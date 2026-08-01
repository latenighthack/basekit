package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, a Swift `Observable{Vm}` wrapper (`Observable{Vm}.swift`) — the SwiftUI
 * peer of [SwiftKvoGenerator]'s `Kvo{Vm}`. It is a plain `ObservableObject` that exposes each State
 * field as an `@Published private(set)` property, seeds them from `initialState`, and keeps them
 * current by collecting the ViewModel's `state` sequence from an `observe()` method the View drives
 * with `.task { await model.observe() }`. Zero-arg actions and single-arg mutators become `async throws`
 * methods; each `@ViewModelList` gets an `observe{List}(into:)` that feeds a deltalist `DeltaList`.
 *
 * Two-way binding: for every mutator whose noun (see `mutatorNoun`) matches a State property of the same
 * type, a `{noun}Binding: Binding<T>` is generated — its read side returns the published state, its
 * write side calls the mutator. A `TextField(text: model.nameBinding)` then reads state and writes
 * through `setName`.
 *
 * Co-existence with KVO: both wrappers take the *same* Kotlin ViewModel and each collect their own
 * subscription to the cold `state` flow, so a screen can hold `Kvo{Vm}` (UIKit) and `Observable{Vm}`
 * (SwiftUI) over one instance at once. Neither wrapper owns state on the ViewModel, so they never
 * interfere; actions/mutators from either propagate to both through the shared `MutableStateFlow`.
 *
 * Delivered as source (not compiled by Gradle), like `Kvo{Vm}`; a consuming Swift target compiles it
 * with `DeltaList` (from deltalist) and links the exported KMP frameworks. [frameworkImports] names the
 * modules to `import` when the exported KMP types live in their own framework.
 */
class SwiftUIObservableGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val frameworkImports: List<String> = emptyList(),
) {
    fun generate(viewModels: List<VmInfo>) {
        for (vm in viewModels) {
            val className = "Observable${vm.simpleName}"

            val publishedProps = vm.stateProperties.joinToString("\n") {
                val st = swiftType(it.typeQualifiedName)
                "    @Published public private(set) var ${it.name}: ${st.type} = ${st.default}"
            }

            val seedAssigns = vm.stateProperties.joinToString("\n") {
                "        self.${it.name} = initial.${it.name}"
            }

            val updateAssigns = vm.stateProperties.joinToString("\n") {
                "                self.${it.name} = state.${it.name}"
            }

            val actionMethods = vm.actions.joinToString("\n\n") { action ->
                """
                |    public func ${action.name}() async throws {
                |        try await viewModel.${action.name}()
                |    }
                """.trimMargin()
            }

            val mutatorMethods = vm.mutators.joinToString("\n\n") { mutator ->
                val st = swiftType(mutator.paramTypeQualifiedName)
                """
                |    public func ${mutator.name}(_ ${mutator.paramName}: ${st.type}) async throws {
                |        try await viewModel.${mutator.name}(${mutator.paramName}: ${mutator.paramName})
                |    }
                """.trimMargin()
            }

            // A mutator backs a two-way Binding when its noun matches a State property of the same type:
            // read side = published state, write side = mutator (with an optimistic local echo).
            val bindings = vm.mutators.mapNotNull { mutator ->
                val noun = mutatorNoun(mutator.name)
                val prop = vm.stateProperties.firstOrNull {
                    it.name == noun && it.typeQualifiedName == mutator.paramTypeQualifiedName
                } ?: return@mapNotNull null
                val st = swiftType(prop.typeQualifiedName)
                """
                |    /// Two-way binding for `${prop.name}`: reads the latest state, writes call `${mutator.name}`.
                |    public var ${noun}Binding: Binding<${st.type}> {
                |        Binding(
                |            get: { [weak self] in self?.${prop.name} ?? ${st.default} },
                |            set: { [weak self] newValue in
                |                guard let self = self else { return }
                |                // Optimistic echo keeps a bound control responsive; the real state event
                |                // from `${mutator.name}` reconciles it a moment later.
                |                self.${prop.name} = newValue
                |                Task { try? await self.${mutator.name}(newValue) }
                |            }
                |        )
                |    }
                """.trimMargin()
            }.joinToString("\n\n")

            val listObservers = vm.lists.joinToString("\n\n") { list ->
                val cap = list.propertyName.toUpperCamelCase()
                """
                |    /// Collects `${list.propertyName}` into a SwiftUI `DeltaList`. Drive with
                |    /// `.task { await model.observe$cap(into: list) }`; each row wraps its child in `Observable${list.elementSimpleName}`.
                |    @available(iOS 15.0, *)
                |    public func observe$cap(into list: DeltaList<${list.elementSimpleName}>) async {
                |        await list.collect(viewModel.${list.propertyName})
                |    }
                """.trimMargin()
            }

            val body = buildString {
                for (framework in frameworkImports) {
                    appendLine("import $framework")
                }
                appendLine("import Foundation")
                appendLine("import Combine")
                appendLine("import SwiftUI")
                appendLine()
                appendLine("// Generated SwiftUI wrapper for ${vm.qualifiedName}.")
                appendLine("// The exported ViewModel type (${vm.simpleName}), its State, DeltaList and the")
                appendLine("// exported KMP frameworks are linked/compiled by the consuming Swift target.")
                appendLine()
                appendLine("@MainActor")
                appendLine("public final class $className: ObservableObject {")
                appendLine()
                appendLine("    private let viewModel: ${vm.simpleName}")
                appendLine()
                if (publishedProps.isNotEmpty()) {
                    appendLine(publishedProps)
                    appendLine()
                }
                appendLine("    /// Surfaced if the state stream terminates with an error. Defaults to nil (silent).")
                appendLine("    public var onError: ((Error) -> Void)?")
                appendLine()
                appendLine("    public init(_ viewModel: ${vm.simpleName}) {")
                appendLine("        self.viewModel = viewModel")
                appendLine("        let initial = viewModel.initialState as! ${vm.stateSwiftName}")
                if (seedAssigns.isNotEmpty()) appendLine(seedAssigns)
                appendLine("    }")
                appendLine()
                appendLine("    /// Collects `state` until the surrounding task is cancelled. Drive from")
                appendLine("    /// `.task { await model.observe() }` so the subscription follows the view's lifetime.")
                appendLine("    public func observe() async {")
                appendLine("        do {")
                appendLine("            for try await anyState in viewModel.state {")
                appendLine("                guard let state = anyState as? ${vm.stateSwiftName} else { continue }")
                if (updateAssigns.isNotEmpty()) appendLine(updateAssigns)
                appendLine("            }")
                appendLine("        } catch {")
                appendLine("            onError?(error)")
                appendLine("        }")
                appendLine("    }")
                if (actionMethods.isNotEmpty()) {
                    appendLine()
                    appendLine(actionMethods)
                }
                if (mutatorMethods.isNotEmpty()) {
                    appendLine()
                    appendLine(mutatorMethods)
                }
                if (bindings.isNotEmpty()) {
                    appendLine()
                    appendLine(bindings)
                }
                if (listObservers.isNotEmpty()) {
                    appendLine()
                    appendLine(listObservers)
                }
                appendLine("}")
            }

            codeGenerator.createNewFile(dependencies, "", className, "swift").apply {
                write(body.encodeToByteArray())
            }.close()
        }
    }
}
