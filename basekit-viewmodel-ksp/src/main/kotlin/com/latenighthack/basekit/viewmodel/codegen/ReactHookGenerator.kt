package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, a `@JsExport use{Vm}(viewModel)` React hook (Kotlin/JS). It maps the
 * ViewModel's `state` onto a `useState` box via a `useEffect`-scoped Flow collection, exposes each
 * suspend action as a `Promise`-returning callback, and each `@ViewModelList` as an items array kept
 * current from the deltalist stream. Returns a plain JS object for the component to consume.
 */
class ReactHookGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(viewModels: List<VmInfo>) {
        for (vm in viewModels) {
            val hookName = "use${vm.simpleName}"

            val listHooks = vm.lists.joinToString("\n") { list ->
                val cap = list.propertyName.toUpperCamelCase()
                """
                |    val ${list.propertyName}Holder = React.useState(js("[]"))
                |    val ${list.propertyName}Value = ${list.propertyName}Holder[0]
                |    val set$cap = ${list.propertyName}Holder[1]
                |    React.useEffect({
                |        bindFlow(viewModel.${list.propertyName}) { delta ->
                |            set$cap(delta.items.softLoadedItems().toTypedArray())
                |        }
                |    }, arrayOf(viewModelArg))
                """.trimMargin()
            }

            val stateAssigns = vm.stateProperties.joinToString("\n") {
                "    result.${it.name} = state.${it.name}"
            }

            val actionAssigns = vm.actions.joinToString("\n") { action ->
                """
                |    result.${action.name} = {
                |        Promise<Unit> { resolve, reject ->
                |            CoroutineScope(SupervisorJob()).launch {
                |                try {
                |                    viewModel.${action.name}()
                |                    resolve(Unit)
                |                } catch (t: Throwable) {
                |                    reject(t)
                |                }
                |            }
                |        }
                |    }
                """.trimMargin()
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
                writeln("import com.latenighthack.deltalist.softLoadedItems")
                writeln("import kotlinx.coroutines.CoroutineScope")
                writeln("import kotlinx.coroutines.SupervisorJob")
                writeln("import kotlinx.coroutines.launch")
                writeln("import kotlin.js.ExperimentalJsExport")
                writeln("import kotlin.js.JsExport")
                writeln("import kotlin.js.JsName")
                writeln("import kotlin.js.Promise")
                writeln()
                writeln(
                    """
                    |/** Generated React hook for [${vm.qualifiedName}]. */
                    |@OptIn(ExperimentalJsExport::class)
                    |@JsExport
                    |@JsName("$hookName")
                    |public fun $hookName(viewModelArg: dynamic): dynamic {
                    |    val viewModel = viewModelArg.unsafeCast<${vm.qualifiedName}>()
                    |
                    |    val stateHolder = React.useState(viewModel.initialState)
                    |    val state = stateHolder[0].unsafeCast<${vm.stateQualifiedName}>()
                    |    val setState = stateHolder[1]
                    |
                    |    React.useEffect({
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
                if (listAssigns.isNotEmpty()) writeln(listAssigns)
                writeln("    return result")
                writeln("}")
            }.close()
        }
    }
}
