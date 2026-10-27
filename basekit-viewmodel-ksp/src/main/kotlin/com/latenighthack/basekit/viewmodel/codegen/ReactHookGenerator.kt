package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, a `@JsExport use{Vm}(viewModel)` React hook (Kotlin/JS) and the
 * `ReactDescriptor` it reads with `useReactViewModel`. The descriptor holds only what differs per
 * view model, as compiled lambdas: State reads, actions, mutators, lists and children. The runtime
 * keeps one binding per view model instance, shared by every component bound to it:
 *
 * - `state` is a `ReactFlowStore` over `boundState` (which passes a throw from `state` to the view
 *   model's failure handler and keeps the last state), read with `useSyncExternalStore`. When
 *   `state` emits on subscription the first render shows that state.
 * - Each `@ViewModelList` is a deltalist-react list store, read the same way; its snapshot is the
 *   list's revision.
 * - Each zero-arg action and single-arg mutator is one `Promise`-returning function, created once.
 * - The returned plain JS object keeps its identity while the state, every list revision and every
 *   child's object are unchanged. Converted lists and adapter values are reused while their Kotlin
 *   value is.
 *
 * List children are closed generated handles: `kind` selects the exact row, `key` supplies
 * identity, and `use()` invokes the generated child hook without exposing the raw ViewModel.
 */
class ReactHookGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(viewModels: List<VmInfo>) {
        for (vm in viewModels) {
            codeGenerator.createNewFile(dependencies, vm.packageName, "${vm.simpleName}ReactHook", "kt").use {
                it.write(render(vm).encodeToByteArray())
            }
        }
    }

    internal fun render(vm: VmInfo): String = buildString {
        val hookName = "use${vm.simpleName}"
        // The descriptor lives in an object: a file with top-level properties makes Kotlin/JS check
        // their initialization at the start of every function in it, lambdas included.
        val holder = "${vm.simpleName}React"

        // Lists of the same row types share one ReactRows.
        val rowsByTypes = LinkedHashMap<List<VmListElementType>, String>()
        val rowsDeclarations = ArrayList<String>()
        val rowsOf = vm.lists.associate { list ->
            list.propertyName to rowsByTypes.getOrPut(list.possibleTypes) {
                rowsDeclarations.add(reactRows(list))
                reactRowsName(list)
            }
        }

        appendLine("@file:Suppress(\"NON_EXPORTABLE_TYPE\")")
        appendLine()
        appendLine("package ${vm.packageName}")
        appendLine()
        if (vm.lists.isNotEmpty()) appendLine("import com.latenighthack.basekit.viewmodel.ReactRows")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelObserver")
        // Each row type's generated `observing` extension, imported when declared in another package.
        vm.lists.flatMap { it.possibleTypes }.map { it.qualifiedName.substringBeforeLast('.', "") }
            .filter { it.isNotEmpty() && it != vm.packageName }.distinct().sorted()
            .forEach { appendLine("import $it.observing") }
        appendLine("import com.latenighthack.basekit.viewmodel.reactDescriptor")
        if (vm.lists.any { list -> list.header?.let { !it.isEnum } == true }) appendLine("import com.latenighthack.basekit.viewmodel.reactHeaderKind")
        if (vm.lists.isNotEmpty()) appendLine("import com.latenighthack.basekit.viewmodel.reactRow")
        appendLine("import com.latenighthack.basekit.viewmodel.useReactViewModel")
        appendLine("import kotlin.js.ExperimentalJsExport")
        appendLine("import kotlin.js.JsExport")
        appendLine("import kotlin.js.JsName")
        appendLine()
        appendLine(
            """
            |/** Export this reference from the app's Kotlin/JS client factory. */
            |@OptIn(ExperimentalJsExport::class)
            |@JsExport
            |public class ${vm.simpleName}ReactRef internal constructor(private val value: ${vm.qualifiedName}) {
            |    public val ${vm.simpleName.replaceFirstChar { it.lowercase() }}Reference: Boolean get() = true
            |    public fun use(): dynamic = $hookName(value)
            |}
            |public fun ${vm.qualifiedName}.reactReference(): ${vm.simpleName}ReactRef = ${vm.simpleName}ReactRef(this)
            |
            |/** A reference whose view model, children and rows report to [observer]; see `observing`. */
            |public fun ${vm.qualifiedName}.reactReference(observer: ViewModelObserver?): ${vm.simpleName}ReactRef = ${vm.simpleName}ReactRef(observing(observer))
            |
            |private object $holder {
            """.trimMargin()
        )
        rowsDeclarations.forEach {
            appendLine(it)
            appendLine()
        }
        appendLine("    /** State, lists and actions of one [${vm.qualifiedName}], shared by every component bound to it. */")
        appendLine("    val descriptor = reactDescriptor<${vm.qualifiedName}, ${vm.stateQualifiedName}>(${kotlinStringLiteral(vm.qualifiedName)}, ${kotlinStringLiteral(vm.simpleName)}) {")
        vm.stateProperties.forEach { property ->
            val name = kotlinStringLiteral(property.name)
            if (reactConvertsToNewObject(property.type, property.reactAdapter)) {
                appendLine("        converted($name, { it.${property.name.kotlinRef()} }) { value -> ${reactToJs(property.type, "value", property.reactAdapter)} }")
            } else {
                appendLine("        field($name) { ${reactToJs(property.type, "it.${property.name.kotlinRef()}", property.reactAdapter)} }")
            }
        }
        vm.actions.forEach { action ->
            appendLine("        action(${kotlinStringLiteral(action.name)}, ${vm.qualifiedName}::${action.name.kotlinRef()})")
        }
        vm.mutators.forEach { mutator ->
            // A value passed to Kotlin as it is needs no conversion.
            val conversion = reactFromJs(mutator.type, "value", mutator.reactAdapter)
                .takeIf { it != "value.unsafeCast<${mutator.type.kotlinName}>()" }
                ?.let { ") { value -> $it }" } ?: ")"
            appendLine("        mutator<${mutator.type.kotlinName}>(${kotlinStringLiteral(mutator.name)}, ${vm.qualifiedName}::${mutator.name.kotlinRef()}$conversion")
        }
        vm.lists.forEach { list ->
            val name = kotlinStringLiteral(list.propertyName)
            val source = "{ it.${list.propertyName.kotlinRef()} }"
            val rows = rowsOf.getValue(list.propertyName)
            val header = reactHeaderLambda(vm, list)
            if (header == null) appendLine("        list($name, $source, $rows)")
            else appendLine("        sectionedList($name, $source, $header, $rows)")
        }
        vm.children.forEach { child ->
            val childPackage = child.typeQualifiedName.substringBeforeLast('.')
            appendLine("        child(${kotlinStringLiteral(child.propertyName)}) { $childPackage.use${child.typeSimpleName}(it.${child.propertyName.kotlinRef()}) }")
        }
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine(
            """
            |/** Generated React hook for [${vm.qualifiedName}]. */
            |@OptIn(ExperimentalJsExport::class)
            |@JsExport
            |@JsName("$hookName")
            |public fun $hookName(viewModelArg: dynamic): dynamic =
            |    useReactViewModel($holder.descriptor, viewModelArg.unsafeCast<${vm.qualifiedName}>())
            """.trimMargin()
        )
    }
}
