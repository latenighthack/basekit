package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

internal class ComposeGenerator(private val generator: CodeGenerator, private val dependencies: Dependencies) {
    fun generate(models: List<VmInfo>) {
        models.forEach { vm ->
            generator.createNewFile(dependencies, vm.packageName, "${vm.simpleName}Compose", "kt").use { file ->
                file.writeln("""
                    package ${vm.packageName}
                    import androidx.compose.runtime.*
                    import androidx.compose.ui.Modifier
                    import androidx.lifecycle.ViewModelStoreOwner
                    import androidx.lifecycle.compose.collectAsStateWithLifecycle
                    import com.latenighthack.basekit.viewmodel.compose.*
                    import kotlinx.coroutines.Job

                    public class ${vm.simpleName}Binding internal constructor(
                        private val model: ${vm.qualifiedName},
                        public val state: ${vm.stateQualifiedName},
                        private val launchAction: (suspend () -> Unit) -> Job,
                    ) {
                        public fun launch(action: suspend ${vm.simpleName}Binding.() -> Unit): Job = launchAction { action() }
                """.trimIndent())
                vm.actions.forEach { file.writeln("    public suspend fun ${it.name}() { model.${it.name}() }") }
                vm.mutators.forEach { file.writeln("    public suspend fun ${it.name}(${it.paramName}: ${it.type.kotlinName}) { model.${it.name}(${it.paramName}) }") }
                vm.children.forEach { child ->
                    file.writeln("    @Composable public fun ${child.propertyName.toUpperCamelCase()}(): ${child.typeQualifiedName.substringBeforeLast('.')}.${child.typeSimpleName}Binding = ${child.typeQualifiedName.substringBeforeLast('.')}.bind${child.typeSimpleName}(model.${child.propertyName}, launchAction)")
                }
                vm.lists.forEach { list ->
                    val rows = list.possibleTypes.joinToString(",\n") {
                        "        ${it.simpleName.toListCaseName()}: @Composable (${it.qualifiedName.substringBeforeLast('.')}.${it.simpleName}Binding) -> Unit"
                    }
                    file.writeln("    @Composable public fun ${list.propertyName.toUpperCamelCase()}(\n$rows,\n        modifier: Modifier = Modifier, placeholder: @Composable (Int) -> Unit = {},\n    ) {")
                    file.writeln("        DeltaRows(source = model.${list.propertyName}, modifier = modifier, placeholder = placeholder, identity = { raw -> when (raw) {")
                    list.possibleTypes.forEach {
                        val key = when {
                            it.identityProperty != null -> "\"${it.qualifiedName}:\" + raw.${it.identityProperty}"
                            it.reactId -> "\"${it.qualifiedName}:\" + raw.initialState.id"
                            else -> "raw"
                        }
                        file.writeln("            is ${it.qualifiedName} -> $key")
                    }
                    file.writeln("            else -> error(\"Undeclared ${vm.simpleName}.${list.propertyName} child\")\n        } }) { raw -> when (raw) {")
                    list.possibleTypes.forEach {
                        file.writeln("            is ${it.qualifiedName} -> ${it.simpleName.toListCaseName()}(${it.qualifiedName.substringBeforeLast('.')}.bind${it.simpleName}(raw, launchAction))")
                    }
                    file.writeln("            else -> error(\"Undeclared ${vm.simpleName}.${list.propertyName} child\")\n        } }\n    }")
                }
                file.writeln("}")
                file.writeln("""
                    @Composable
                    public fun bind${vm.simpleName}(model: ${vm.qualifiedName}, launchAction: (suspend () -> Unit) -> Job): ${vm.simpleName}Binding {
                        val state by model.state.collectAsStateWithLifecycle(model.initialState)
                        return ${vm.simpleName}Binding(model, state, launchAction)
                    }

                    @Composable
                    public fun ${vm.simpleName}Host(
                        ownerKey: String,
                        storeOwner: ViewModelStoreOwner,
                        prepare: suspend () -> PreparedViewModel<${vm.qualifiedName}>,
                        onActionError: (Throwable) -> Unit,
                        loading: @Composable () -> Unit,
                        failure: @Composable (Throwable, () -> Unit) -> Unit,
                        content: @Composable (${vm.simpleName}Binding) -> Unit,
                    ) {
                        val errorHandler by rememberUpdatedState(onActionError)
                        PreparedViewModelHost("${vm.qualifiedName}:" + ownerKey, storeOwner, prepare, loading, failure) { model, owner ->
                            content(bind${vm.simpleName}(model) { action -> owner.launch({ errorHandler(it) }, action) })
                        }
                    }
                """.trimIndent())
            }
        }
    }
}
