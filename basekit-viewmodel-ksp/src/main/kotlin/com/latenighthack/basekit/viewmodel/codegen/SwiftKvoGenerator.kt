package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, a Swift `Kvo{Vm}` wrapper (`Kvo{Vm}.swift`). It exposes each State field
 * as an `@objc dynamic` property (KVO-observable), seeds them from `initialState`, and keeps them
 * current by collecting the ViewModel's `state` sequence (SKIE bridges Kotlin `Flow` to Swift
 * `AsyncSequence`). Suspend actions become `async throws` methods; each `@ViewModelList` gets a
 * `bind{ListProp}(_:)` that drives a deltalist `DeltaCollectionDataSource`, vending `Kvo{ChildVm}`
 * wrappers to the cell provider.
 *
 * Delivered as source (not compiled by Gradle); a consuming Xcode/SwiftPM target compiles it with the
 * `KvoViewModel` support base and links the exported KMP frameworks.
 *
 * When the exported KMP types live in their own framework/module (rather than the same Swift target that
 * compiles the wrapper), [frameworkImports] names the modules to `import` at the top of each file.
 *
 * `initialState` and the `state` sequence's elements are read back as `Any?`/`Any`: Kotlin/Native erases
 * the `ViewModel<State>` interface's type argument when exporting the protocol to Objective-C, so the
 * wrapper casts them to the concrete `{Vm}State` before touching typed fields.
 *
 * Zero-arg actions become `async throws` methods; single-arg mutators become `async throws` methods
 * taking the argument. (The two-way binding a mutator can back is SwiftUI-only; UIKit call sites invoke
 * the method directly — see [SwiftUIObservableGenerator].)
 */
class SwiftKvoGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val frameworkImports: List<String> = emptyList(),
) {
    fun generate(viewModels: List<VmInfo>) {
        for (vm in viewModels) {
            val className = "Kvo${vm.simpleName}"

            val dynamicProps = vm.stateProperties.joinToString("\n") {
                val st = swiftType(it.typeQualifiedName)
                "    @objc public dynamic var ${it.name}: ${st.type} = ${st.default}"
            }

            val seedAssigns = vm.stateProperties.joinToString("\n") {
                "        self.${it.name} = initial.${it.name}"
            }

            val updateAssigns = vm.stateProperties.joinToString("\n") {
                "                        self.${it.name} = state.${it.name}"
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

            val listBinders = vm.lists.joinToString("\n\n") { list ->
                val cap = list.propertyName.toUpperCamelCase()
                val childKvo = "Kvo${list.elementSimpleName}"
                """
                |    #if canImport(UIKit)
                |    @available(iOS 14.0, *)
                |    @discardableResult
                |    @MainActor public func bind$cap(
                |        _ collectionView: UICollectionView,
                |        cellProvider: @escaping (UICollectionView, IndexPath, $childKvo) -> UICollectionViewCell
                |    ) -> DeltaCollectionDataSource<${list.elementSimpleName}> {
                |        let dataSource = DeltaCollectionDataSource<${list.elementSimpleName}>(
                |            collectionView: collectionView
                |        ) { cv, indexPath, item in
                |            cellProvider(cv, indexPath, $childKvo(item))
                |        }
                |        dataSource.bind(erased: viewModel.${list.propertyName})
                |        return dataSource
                |    }
                |    #endif
                """.trimMargin()
            }

            val body = buildString {
                for (framework in frameworkImports) {
                    appendLine("import $framework")
                }
                appendLine("import Foundation")
                appendLine("#if canImport(UIKit)")
                appendLine("import UIKit")
                appendLine("#endif")
                appendLine()
                appendLine("// Generated KVO wrapper for ${vm.qualifiedName}.")
                appendLine("// The exported ViewModel type (${vm.simpleName}), its State, DeltaListCore and the")
                appendLine("// KvoViewModel support base are linked/compiled by the consuming Swift target.")
                appendLine()
                appendLine("@objcMembers")
                appendLine("public final class $className: KvoViewModel {")
                appendLine()
                appendLine("    private let viewModel: ${vm.simpleName}")
                appendLine()
                if (dynamicProps.isNotEmpty()) {
                    appendLine(dynamicProps)
                    appendLine()
                }
                appendLine("    public init(_ viewModel: ${vm.simpleName}) {")
                appendLine("        self.viewModel = viewModel")
                appendLine("        super.init()")
                appendLine("        let initial = viewModel.initialState as! ${vm.stateSwiftName}")
                if (seedAssigns.isNotEmpty()) appendLine(seedAssigns)
                appendLine("        startObserving { [weak self] in")
                appendLine("            guard let self = self else { return }")
                appendLine("            do {")
                appendLine("                for try await anyState in self.viewModel.state {")
                appendLine("                    guard let state = anyState as? ${vm.stateSwiftName} else { continue }")
                appendLine("                    await MainActor.run {")
                if (updateAssigns.isNotEmpty()) appendLine(updateAssigns)
                appendLine("                    }")
                appendLine("                }")
                appendLine("            } catch {")
                appendLine("            }")
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
                if (listBinders.isNotEmpty()) {
                    appendLine()
                    appendLine(listBinders)
                }
                appendLine("}")
            }

            codeGenerator.createNewFile(dependencies, "", className, "swift").apply {
                write(body.encodeToByteArray())
            }.close()
        }
    }
}
