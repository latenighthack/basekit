package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, a `@JsExport use{Vm}(viewModel)` React hook (Kotlin/JS). It maps the
 * ViewModel's `state` onto a `useState` box via a `useEffect`-scoped Flow collection, exposes each
 * zero-arg action and single-arg mutator as a `Promise`-returning callback, and each `@ViewModelList`
 * as a stable delegated iterable from deltalist-react. Returns a plain JS object for the component to
 * consume. List children are closed generated handles: `kind` selects the exact row, `key` supplies
 * identity, and `use()` invokes the generated child hook without exposing the raw ViewModel.
 */
class ReactHookGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(viewModels: List<VmInfo>) {
        for (vm in viewModels) {
            val hookName = "use${vm.simpleName}"

            val listHooks = vm.lists.joinToString("\n") { list ->
                "    val ${list.propertyName}Value = useMappedDeltaList(viewModel.${list.propertyName}, ::${reactListFactoryName(vm, list)})"
            }

            val listFactories = vm.lists.joinToString("\n\n") { list ->
                reactListElementFactory(vm, list)
            }

            val stateAssigns = vm.stateProperties.joinToString("\n") {
                "    result.${it.name} = ${reactToJs(it.type, "state.${it.name}", it.reactAdapter)}"
            }

            val actionAssigns = vm.actions.joinToString("\n") { action ->
                "    result.${action.name} = { actions.run { viewModel.${action.name}() } }"
            }
            val mutatorAssigns = vm.mutators.joinToString("\n") { mutator ->
                "    result.${mutator.name} = { value: dynamic -> actions.run { viewModel.${mutator.name}(${reactFromJs(mutator.type, "value", mutator.reactAdapter)}) } }"
            }

            val listAssigns = vm.lists.joinToString("\n") {
                "    result.${it.propertyName} = ${it.propertyName}Value"
            }

            codeGenerator.createNewFile(dependencies, vm.packageName, "${vm.simpleName}ReactHook", "kt").apply {
                writeln("@file:Suppress(\"NON_EXPORTABLE_TYPE\")")
                writeln()
                writeln("package ${vm.packageName}")
                writeln()
                writeln("import com.latenighthack.basekit.viewmodel.React")
                writeln("import com.latenighthack.basekit.viewmodel.bindFlow")
                writeln("import com.latenighthack.basekit.viewmodel.ReactActionScope")
                writeln("import com.latenighthack.deltalist.react.useMappedDeltaList")
                writeln("import kotlinx.coroutines.CoroutineScope")
                writeln("import kotlinx.coroutines.SupervisorJob")
                writeln("import kotlinx.coroutines.launch")
                writeln("import kotlinx.coroutines.cancel")
                writeln("import kotlin.js.ExperimentalJsExport")
                writeln("import kotlin.js.JsExport")
                writeln("import kotlin.js.JsName")
                writeln("import kotlin.js.Promise")
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
                """.trimMargin())
                writeln(
                    """
                    |/** Generated React hook for [${vm.qualifiedName}]. */
                    |@OptIn(ExperimentalJsExport::class)
                    |@JsExport
                    |@JsName("$hookName")
                    |public fun $hookName(viewModelArg: dynamic): dynamic {
                    |    val viewModel = viewModelArg.unsafeCast<${vm.qualifiedName}>()
                    |
                    |    val actions = React.useMemo({ ReactActionScope() }, arrayOf(viewModelArg)).unsafeCast<ReactActionScope>()
                    |    React.useEffect({ actions.start(); { actions.stop() } }, arrayOf(actions))
                    |    val stateHolder = React.useState(viewModel.initialState)
                    |    val state = stateHolder[0].unsafeCast<${vm.stateQualifiedName}>()
                    |    val setState = stateHolder[1]
                    |
                    |    React.useEffect({
                    |        setState(viewModel.initialState)
                    |        bindFlow(viewModel.state) { s -> setState(s) }
                    |    }, arrayOf(viewModelArg))
                    """.trimMargin()
                )
                if (listHooks.isNotEmpty()) {
                    writeln()
                    writeln(listHooks)
                }
                writeln()
                writeln("    val result: dynamic = js(\"({})\")")
                if (stateAssigns.isNotEmpty()) writeln(stateAssigns)
                if (actionAssigns.isNotEmpty()) writeln(actionAssigns)
                if (mutatorAssigns.isNotEmpty()) writeln(mutatorAssigns)
                if (listAssigns.isNotEmpty()) writeln(listAssigns)
                for (child in vm.children) writeln("    result.${child.propertyName} = ${child.typeQualifiedName.substringBeforeLast('.')}.use${child.typeSimpleName}(viewModel.${child.propertyName})")
                writeln("    return result")
                writeln("}")
            }.close()
        }
    }
}
