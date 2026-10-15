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
                val st = it.swiftObservableBindingType()
                "    @Published public private(set) var ${it.name.swiftDeclName()}: ${st.type}"
            }

            val seedAssigns = vm.stateProperties.joinToString("\n") {
                "        self.${it.name.swiftDeclName()} = initial.${it.swiftName.swiftSourceRef()}"
            }

            val updateAssigns = vm.stateProperties.joinToString("\n") {
                "            self.${it.name.swiftDeclName()} = state.${it.swiftName.swiftSourceRef()}"
            }

            val actionMethods = vm.actions.joinToString("\n\n") { action ->
                """
                |    public func ${action.name.swiftDeclName()}() async throws {
                |        try await viewModel.${action.name.swiftSourceRef()}()
                |    }
                """.trimMargin()
            }

            val mutatorMethods = vm.mutators.joinToString("\n\n") { mutator ->
                val st = swiftObservableType(mutator.type)
                """
                |    public func ${mutator.name.swiftDeclName()}(_ ${mutator.paramName.swiftDeclName()}: ${st.type}) async throws {
                |        try await viewModel.${mutator.name.swiftSourceRef()}(${mutator.paramName.swiftSourceRef()}: ${mutator.paramName.swiftDeclName()})
                |    }
                """.trimMargin()
            }

            // A mutator backs a two-way Binding when its noun matches a State property of the same type
            // AND the same nullability (else Binding<T?> and a T-taking mutator would not type-check):
            // read side = published state, write side = mutator (with an optimistic local echo).
            val bindings = vm.mutators.mapNotNull { mutator ->
                val noun = mutatorNoun(mutator.name)
                val prop = vm.stateProperties.firstOrNull {
                    it.name == noun &&
                        it.typeQualifiedName == mutator.paramTypeQualifiedName &&
                        it.nullable == mutator.paramTypeNullable &&
                        // A list property is driven by an action, not a bound scalar control; skip it so
                        // its `[String]` read side can't pair with a mutator whose param is still erased.
                        it.listElementQualifiedName == null
                } ?: return@mapNotNull null
                val st = prop.swiftObservableBindingType()
                val declProp = prop.name.swiftDeclName()
                val declMutator = mutator.name.swiftDeclName()
                """
                |    /// Two-way binding for `${prop.name}`: reads the latest state, writes call `${mutator.name}`.
                |    public var ${noun.swiftDeclName()}Binding: Binding<${st.type}> {
                |        Binding(
                |            get: { self.$declProp },
                |            set: { [weak self] newValue in
                |                guard let self = self else { return }
                |                // Optimistic echo keeps a bound control responsive; the real state event
                |                // from `${mutator.name}` reconciles it a moment later.
                |                self.$declProp = newValue
                |                Task { try? await self.$declMutator(newValue) }
                |            }
                |        )
                |    }
                """.trimMargin()
            }.joinToString("\n\n")

            // Sectioned lists bind through UIKit only (the Kvo wrapper); SwiftUI has no sectioned view.
            val flatLists = vm.lists.filterNot { it.sectioned }
            val listObservers = flatLists.joinToString("\n\n") { list ->
                val cap = list.propertyName.toUpperCamelCase()
                """
                |    /// Collects `${list.propertyName}` into a SwiftUI `DeltaList`. Drive with
                |    /// `.task { await model.observe$cap(into: list) }`; each row wraps its child in `Observable${list.elementSimpleName}`.
                |    ${DeltaListAvailability.SWIFTUI}
                |    @available(*, deprecated, message: "Use DeltaListView(model.${list.propertyName}); in Form/List use DeltaForEach(model.${list.propertyName}, observing: list) with container-owned collection")
                |    public func observe$cap(into list: DeltaList<${list.elementSimpleName}>) async {
                |        await list.collect(viewModel.${list.swiftName.swiftSourceRef()})
                |    }
                """.trimMargin()
            }
            val listElements = flatLists.map { appleListElementDeclaration(vm, it, AppleWrapperStyle.OBSERVABLE) }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
            val listDescriptors = flatLists.joinToString("\n\n") {
                appleListDescriptorProperty(vm, it, AppleWrapperStyle.OBSERVABLE)
            }

            val body = buildString {
                for (framework in frameworkImports) {
                    appendLine("@_spi(BaseKitCodegen) import $framework")
                }
                appendLine("import Foundation")
                appendLine("import Combine")
                appendLine("import SwiftUI")
                appendLine()
                appendLine("// Generated SwiftUI wrapper for ${vm.qualifiedName}.")
                appendLine("// The exported ViewModel type (${vm.simpleName}), its State, DeltaList and the")
                appendLine("// exported KMP frameworks are linked/compiled by the consuming Swift target.")
                appendLine()
                if (listElements.isNotEmpty()) {
                    appendLine(listElements)
                    appendLine()
                }
                appendLine("@MainActor")
                appendLine("public final class $className: ObservableObject, BasekitObservableViewModel {")
                appendLine()
                appendLine("    private let viewModel: ${vm.simpleName}")
                vm.identitySwiftName?.let { identity ->
                    appendLine("    public var basekitIdentity: String { viewModel.${identity.swiftSourceRef()} }")
                }
                for (child in vm.children) {
                    appendLine("    public lazy var ${child.propertyName.swiftDeclName()} = Observable${child.typeSimpleName}(viewModel.${child.swiftName.swiftSourceRef()})")
                }
                appendLine()
                if (publishedProps.isNotEmpty()) {
                    appendLine(publishedProps)
                    appendLine()
                }
                appendLine("    /// Never called: SKIE bridges `state` to a non-throwing AsyncSequence.")
                appendLine("    @available(*, deprecated, message: \"The state sequence does not throw; this handler is never called\")")
                appendLine("    public var onError: ((Error) -> Void)?")
                appendLine()
                appendLine("    public init(_ viewModel: ${vm.simpleName}) {")
                appendLine("        self.viewModel = viewModel")
                // A State without fields has nothing to seed or assign; omit the bindings Swift would
                // flag as unused, but still collect `state` (collection may start upstream work).
                if (seedAssigns.isNotEmpty()) {
                    appendLine("        let initial = viewModel.initialState as! ${vm.stateSwiftName}")
                    appendLine(seedAssigns)
                }
                appendLine("    }")
                appendLine()
                appendLine("    /// Collects `state` until the surrounding task is cancelled. Drive from")
                appendLine("    /// `.task { await model.observe() }` so the subscription follows the view's lifetime.")
                appendLine("    public func observe() async {")
                if (updateAssigns.isNotEmpty()) {
                    appendLine("        for await anyState in viewModel.state {")
                    appendLine("            guard let state = anyState as? ${vm.stateSwiftName} else { continue }")
                    appendLine(updateAssigns)
                    appendLine("        }")
                } else {
                    appendLine("        for await _ in viewModel.state {}")
                }
                appendLine("    }")
                if (actionMethods.isNotEmpty()) {
                    appendLine()
                    appendLine(actionMethods)
                }
                swiftActionIds(vm).takeIf { it.isNotEmpty() }?.let {
                    appendLine()
                    appendLine(it)
                }
                if (mutatorMethods.isNotEmpty()) {
                    appendLine()
                    appendLine(mutatorMethods)
                }
                if (bindings.isNotEmpty()) {
                    appendLine()
                    appendLine(bindings)
                }
                if (listDescriptors.isNotEmpty()) {
                    appendLine()
                    appendLine(listDescriptors)
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
