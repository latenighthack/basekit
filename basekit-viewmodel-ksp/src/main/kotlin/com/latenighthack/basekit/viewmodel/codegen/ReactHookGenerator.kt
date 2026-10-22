package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, a `@JsExport use{Vm}(viewModel)` React hook (Kotlin/JS) and the
 * `{Vm}ReactBinding` it reads. One binding exists per view model instance and every component bound
 * to that instance shares it:
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
            val hookName = "use${vm.simpleName}"
            val bindingName = "${vm.simpleName}ReactBinding"
            val name = kotlinStringLiteral(vm.qualifiedName)

            val listStores = vm.lists.joinToString("\n") { list ->
                val source = "viewModel.boundList($name, ${kotlinStringLiteral(list.propertyName)}, viewModel.${list.propertyName.kotlinRef()})"
                if (list.sectioned) {
                    "    val ${list.propertyName.kotlinRef()}: ReactSectionedDeltaListStore = ReactSectionedDeltaListStore($source, ::${reactHeaderFactoryName(vm, list)}, ::${reactListFactoryName(vm, list)})"
                } else {
                    "    val ${list.propertyName.kotlinRef()}: ReactDeltaListStore = ReactDeltaListStore($source, ::${reactListFactoryName(vm, list)})"
                }
            }

            val listFactories = vm.lists.flatMap { list ->
                listOf(reactListElementFactory(vm, list), reactHeaderFactory(vm, list))
            }.filter { it.isNotEmpty() }.joinToString("\n\n")

            val conversions = vm.stateProperties.filter { reactConvertsToNewObject(it.type, it.reactAdapter) }.joinToString("\n") {
                "    private val ${it.name}Value = ReactConversion<${it.type.kotlinName}> { value -> ${reactToJs(it.type, "value", it.reactAdapter)} }"
            }
            val stateAssigns = vm.stateProperties.joinToString("\n") {
                val value = if (reactConvertsToNewObject(it.type, it.reactAdapter)) {
                    "${it.name}Value(state.${it.name.kotlinRef()})"
                } else {
                    reactToJs(it.type, "state.${it.name.kotlinRef()}", it.reactAdapter)
                }
                "        result.${it.name.kotlinRef()} = $value"
            }

            val actionFunctions = vm.actions.joinToString("\n") { action ->
                "    private val ${action.name}Action: dynamic = { core.run { viewModel.${action.name.kotlinRef()}() } }"
            }
            val mutatorFunctions = vm.mutators.joinToString("\n") { mutator ->
                "    private val ${mutator.name}Action: dynamic = { value: dynamic -> core.run { viewModel.${mutator.name.kotlinRef()}(${reactFromJs(mutator.type, "value", mutator.reactAdapter)}) } }"
            }
            val callableAssigns = (vm.actions.map { it.name } + vm.mutators.map { it.name }).joinToString("\n") {
                "        result.${it.kotlinRef()} = ${it}Action"
            }
            val listAssigns = vm.lists.joinToString("\n") {
                if (it.sectioned) "        result.${it.propertyName.kotlinRef()} = ${it.propertyName.kotlinRef()}.handle"
                else "        result.${it.propertyName.kotlinRef()} = ${it.propertyName.kotlinRef()}.proxy"
            }
            val childAssigns = vm.children.joinToString("\n") {
                "        result.${it.propertyName.kotlinRef()} = ${it.propertyName}Binding"
            }

            val resultParams = listOf("state: ${vm.stateQualifiedName}") +
                vm.lists.map { "${it.propertyName}Revision: dynamic" } +
                vm.children.map { "${it.propertyName}Binding: dynamic" }
            val resultInputs = listOf("state") + vm.lists.map { "${it.propertyName}Revision" } + vm.children.map { "${it.propertyName}Binding" }

            val hookReads = buildList {
                add("    val state = React.useSyncExternalStore(binding.core.state.subscribe, binding.core.state.getSnapshot).unsafeCast<${vm.stateQualifiedName}>()")
                vm.lists.forEach {
                    add("    val ${it.propertyName}Revision = React.useSyncExternalStore(binding.${it.propertyName.kotlinRef()}.subscribe, binding.${it.propertyName.kotlinRef()}.getSnapshot)")
                }
                vm.children.forEach {
                    add("    val ${it.propertyName}Binding = ${it.typeQualifiedName.substringBeforeLast('.')}.use${it.typeSimpleName}(viewModel.${it.propertyName.kotlinRef()})")
                }
            }.joinToString("\n")

            codeGenerator.createNewFile(dependencies, vm.packageName, "${vm.simpleName}ReactHook", "kt").apply {
                writeln("@file:Suppress(\"NON_EXPORTABLE_TYPE\")")
                writeln()
                writeln("package ${vm.packageName}")
                writeln()
                writeln("import com.latenighthack.basekit.viewmodel.React")
                writeln("import com.latenighthack.basekit.viewmodel.ReactBindingCache")
                writeln("import com.latenighthack.basekit.viewmodel.ReactConversion")
                writeln("import com.latenighthack.basekit.viewmodel.ReactViewModelBinding")
                if (vm.lists.isNotEmpty()) writeln("import com.latenighthack.basekit.viewmodel.boundList")
                if (vm.lists.any { !it.sectioned }) writeln("import com.latenighthack.deltalist.react.ReactDeltaListStore")
                if (vm.lists.any { it.sectioned }) writeln("import com.latenighthack.deltalist.react.ReactSectionedDeltaListStore")
                writeln("import kotlin.js.ExperimentalJsExport")
                writeln("import kotlin.js.JsExport")
                writeln("import kotlin.js.JsName")
                writeln()
                if (listFactories.isNotEmpty()) {
                    writeln(listFactories)
                    writeln()
                }
                writeln("""
                    |/** Export this reference from the app's Kotlin/JS client factory. */
                    |@OptIn(ExperimentalJsExport::class)
                    |@JsExport
                    |public class ${vm.simpleName}ReactRef internal constructor(private val value: ${vm.qualifiedName}) {
                    |    public val ${vm.simpleName.replaceFirstChar { it.lowercase() }}Reference: Boolean get() = true
                    |    public fun use(): dynamic = $hookName(value)
                    |}
                    |public fun ${vm.qualifiedName}.reactReference(): ${vm.simpleName}ReactRef = ${vm.simpleName}ReactRef(this)
                    |
                    |/** State, lists and actions of one [${vm.qualifiedName}], shared by every component bound to it. */
                    |internal class $bindingName private constructor(private val viewModel: ${vm.qualifiedName}) {
                    |    val core: ReactViewModelBinding<${vm.stateQualifiedName}> = ReactViewModelBinding(viewModel, $name)
                """.trimMargin())
                if (listStores.isNotEmpty()) writeln(listStores)
                if (conversions.isNotEmpty()) writeln(conversions)
                if (actionFunctions.isNotEmpty()) writeln(actionFunctions)
                if (mutatorFunctions.isNotEmpty()) writeln(mutatorFunctions)
                writeln()
                writeln("    fun result(${resultParams.joinToString(", ")}): dynamic = core.result(arrayOf(${resultInputs.joinToString(", ")})) {")
                writeln("        val result: dynamic = js(\"({})\")")
                for (section in listOf(stateAssigns, callableAssigns, listAssigns, childAssigns)) {
                    if (section.isNotEmpty()) writeln(section)
                }
                writeln("        result")
                writeln("    }")
                writeln()
                writeln("    companion object {")
                writeln("        private val bindings = ReactBindingCache<${vm.qualifiedName}, $bindingName> { $bindingName(it) }")
                writeln("        fun of(viewModel: ${vm.qualifiedName}): $bindingName = bindings.get(viewModel)")
                writeln("    }")
                writeln("}")
                writeln()
                writeln(
                    """
                    |/** Generated React hook for [${vm.qualifiedName}]. */
                    |@OptIn(ExperimentalJsExport::class)
                    |@JsExport
                    |@JsName("$hookName")
                    |public fun $hookName(viewModelArg: dynamic): dynamic {
                    |    val viewModel = viewModelArg.unsafeCast<${vm.qualifiedName}>()
                    |    val binding = $bindingName.of(viewModel)
                    """.trimMargin()
                )
                writeln(hookReads)
                writeln("    return binding.result(${resultInputs.joinToString(", ")})")
                writeln("}")
            }.close()
        }
    }
}
