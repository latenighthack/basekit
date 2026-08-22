package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, a Swift `Kvo{Vm}` wrapper (`Kvo{Vm}.swift`). It exposes each State field
 * as an `@objc dynamic` property (KVO-observable), seeds them from `initialState`, and keeps them
 * current by collecting the ViewModel's `state` sequence (SKIE bridges Kotlin `Flow` to Swift
 * `AsyncSequence`). Suspend actions become `async throws` methods; each `@ViewModelList` gets a
 * `bind{ListProp}(_:)` that drives a deltalist collection-view data source, vending `Kvo{ChildVm}`
 * wrappers to the cell/item provider.
 *
 * This is the imperative binding for every Apple platform, not just iOS: `@objc dynamic` properties
 * are what both UIKit KVO and AppKit's Cocoa Bindings consume. One universal file is emitted per
 * ViewModel, identical from every Apple KSP pass, with the platform difference confined to
 * `#if canImport(UIKit)` / `#elseif canImport(AppKit)` — the list binder takes a `UICollectionView`
 * on UIKit and an `NSCollectionView` on AppKit (see [appleListBinder]). Emitting the same bytes from
 * every pass is what keeps `collectBasekitViewModelSwift`'s flatten deterministic.
 *
 * Like iOS, no view-controller host is generated. Android's `Abstract{Vm}Activity` exists because
 * Android mandates `Activity` as the entry point with a lifecycle that must be hooked; AppKit
 * mandates nothing (a macOS screen may be `NSViewController`-, `NSWindowController`-, document- or
 * SwiftUI-hosted), so a generated host would be wrong for most apps.
 *
 * Delivered as source; a consuming Xcode/SwiftPM target compiles it and links the exported KMP
 * frameworks, which already carry the `KvoViewModel` support base (SKIE compiles the bundled Swift
 * under `src/commonMain/swift` into each framework).
 *
 * When the exported KMP types live in their own framework/module (rather than the same Swift target that
 * compiles the wrapper), [frameworkImports] names the modules to `import` at the top of each file.
 *
 * `initialState` and the `state` sequence's elements are read back as `Any?`/`Any`: Kotlin/Native erases
 * the `ViewModel<State>` interface's type argument when exporting the protocol to Objective-C, so the
 * wrapper casts them to the concrete `{Vm}State` before touching typed fields.
 *
 * Zero-arg actions become `async throws` methods; single-arg mutators become `async throws` methods
 * taking the argument. (The two-way binding a mutator can back is SwiftUI-only — see
 * [SwiftUIObservableGenerator].) Each zero-arg action additionally gets an `@objc` target-action
 * thunk, `{action}Action(_:)`, because the `async throws` method is not reachable from a selector —
 * see [targetActionThunks].
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
                val st = swiftType(it.typeQualifiedName, it.nullable)
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
                val st = swiftType(mutator.paramTypeQualifiedName, mutator.paramTypeNullable)
                """
                |    public func ${mutator.name}(_ ${mutator.paramName}: ${st.type}) async throws {
                |        try await viewModel.${mutator.name}(${mutator.paramName}: ${mutator.paramName})
                |    }
                """.trimMargin()
            }

            val listBinders = vm.lists.joinToString("\n\n") { list -> appleListBinder(list) }

            val actionThunks = targetActionThunks(vm)

            val body = buildString {
                for (framework in frameworkImports) {
                    appendLine("import $framework")
                }
                appendLine("import Foundation")
                // UIKit first: Mac Catalyst can import both, and there the UIKit binder is correct.
                appendLine("#if canImport(UIKit)")
                appendLine("import UIKit")
                appendLine("#elseif canImport(AppKit)")
                appendLine("import AppKit")
                appendLine("#endif")
                appendLine()
                appendLine("// Generated KVO wrapper for ${vm.qualifiedName}. Universal across Apple platforms:")
                appendLine("// UIKit and AppKit differ only inside the #if blocks below.")
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
                if (actionThunks.isNotEmpty()) {
                    appendLine()
                    appendLine(actionThunks)
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
