package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModel`, a Swift `Kvo{Vm}` wrapper (`Kvo{Vm}.swift`). It exposes each State field
 * as an `@objc dynamic` property (KVO-observable), seeds them from `initialState`, and keeps them
 * current by collecting the ViewModel's `state` sequence (SKIE bridges Kotlin `Flow` to Swift
 * `AsyncSequence`). Suspend actions become `async throws` methods; each `@ViewModelList` gets a
 * `bind{ListProp}(_:)` that drives a deltalist `DeltaCollectionDataSource`, vending `Kvo{ChildVm}`
 * wrappers to the cell provider.
 *
 * Delivered as source (not compiled by Gradle); a consuming Xcode/SwiftPM target compiles it with the
 * `KvoViewModel` support base and links the exported KMP frameworks.
 */
class SwiftKvoGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    private data class SwiftType(val type: String, val default: String)

    private fun swiftType(qualifiedName: String): SwiftType = when (qualifiedName) {
        "kotlin.Int" -> SwiftType("Int32", "0")
        "kotlin.Long" -> SwiftType("Int64", "0")
        "kotlin.Short" -> SwiftType("Int16", "0")
        "kotlin.Byte" -> SwiftType("Int8", "0")
        "kotlin.Boolean" -> SwiftType("Bool", "false")
        "kotlin.Float" -> SwiftType("Float", "0")
        "kotlin.Double" -> SwiftType("Double", "0")
        "kotlin.String" -> SwiftType("String", "\"\"")
        else -> SwiftType("AnyObject?", "nil")
    }

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

            val listBinders = vm.lists.joinToString("\n\n") { list ->
                val cap = list.propertyName.toUpperCamelCase()
                val childKvo = "Kvo${list.elementSimpleName}"
                """
                |    #if canImport(UIKit)
                |    @available(iOS 14.0, *)
                |    @discardableResult
                |    public func bind$cap(
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
                appendLine("        let initial = viewModel.initialState")
                if (seedAssigns.isNotEmpty()) appendLine(seedAssigns)
                appendLine("        startObserving { [weak self] in")
                appendLine("            guard let self = self else { return }")
                appendLine("            do {")
                appendLine("                for try await state in self.viewModel.state {")
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
