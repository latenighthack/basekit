package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

internal class ComposeGenerator(private val generator: CodeGenerator, private val dependencies: Dependencies) {
    fun generate(models: List<VmInfo>) {
        models.forEach { vm ->
            // A mutator backs a two-way binding when its noun matches a non-list State property of the
            // same type and nullability, as in the Swift bindings.
            val bound = vm.mutators.mapNotNull { mutator ->
                val noun = mutatorNoun(mutator.name)
                vm.stateProperties.firstOrNull {
                    it.name == noun &&
                        it.typeQualifiedName == mutator.paramTypeQualifiedName &&
                        it.nullable == mutator.paramTypeNullable &&
                        it.listElementQualifiedName == null
                }?.let { mutator to it }
            }
            generator.createNewFile(dependencies, vm.packageName, "${vm.simpleName}Compose", "kt").use { file ->
                file.writeln("""
                    package ${vm.packageName}
                    import androidx.compose.runtime.*
                    import androidx.compose.ui.Modifier
                    import androidx.lifecycle.ViewModelStoreOwner
                    import androidx.lifecycle.compose.collectAsStateWithLifecycle
                    import com.latenighthack.basekit.viewmodel.boundList
                    import com.latenighthack.basekit.viewmodel.boundState
                    import com.latenighthack.basekit.viewmodel.compose.*
                    import kotlinx.coroutines.Job

                    public class ${vm.simpleName}Binding internal constructor(
                        private val model: ${vm.qualifiedName},
                        public val state: ${vm.stateQualifiedName},
                        private val launchAction: (suspend () -> Unit) -> Job,
                """.trimIndent())
                bound.forEach { (mutator, property) ->
                    // Two-way binding for `${property.name}`: reads the latest state, writes call the mutator.
                    file.writeln("    public val ${mutatorNoun(mutator.name)}Binding: MutatorBinding<${mutator.type.kotlinName}>,")
                }
                file.writeln("""
                    ) {
                        public fun launch(action: suspend ${vm.simpleName}Binding.() -> Unit): Job = launchAction { action() }
                """.trimIndent())
                vm.identityProperty?.let { identity ->
                    // The row's @ViewModelIdentity, as Swift's `basekitIdentity`: a list slot can tell
                    // which domain item a row is, e.g. to map a pager page back to its ID.
                    file.writeln("    /** This view model's `@ViewModelIdentity`; it never changes for the instance. */")
                    file.writeln("    public val basekitIdentity: String get() = model.${identity.kotlinRef()}")
                }
                vm.actions.forEach { file.writeln("    public suspend fun ${it.name}() { model.${it.name}() }") }
                val identified = vm.actionIdentifierMembers()
                if (identified.isNotEmpty()) {
                    // Stable identifiers, the same on every platform; hosts pass them to Modifier.testTag.
                    file.writeln("    public object ActionIds {")
                    identified.forEach { file.writeln("        public const val ${it.kotlinRef()}: String = \"${actionIdentifier(vm, it)}\"") }
                    file.writeln("    }")
                }
                vm.mutators.forEach { file.writeln("    public suspend fun ${it.name}(${it.paramName}: ${it.type.kotlinName}) { model.${it.name}(${it.paramName}) }") }
                vm.children.forEach { child ->
                    file.writeln("    @Composable public fun ${child.propertyName.toUpperCamelCase()}(): ${child.typeQualifiedName.substringBeforeLast('.')}.${child.typeSimpleName}Binding = ${child.typeQualifiedName.substringBeforeLast('.')}.bind${child.typeSimpleName}(model.${child.propertyName}, launchAction)")
                }
                vm.lists.forEach { list ->
                    val rows = list.possibleTypes.joinToString(",\n") {
                        "        ${it.simpleName.toListCaseName()}: @Composable (${it.qualifiedName.substringBeforeLast('.')}.${it.simpleName}Binding) -> Unit"
                    }
                    // A sectioned list adds a typed header slot; rows bind exactly as in a flat list.
                    val headerSlot = list.header?.let { "        header: @Composable (header: ${it.qualifiedName}, sectionIndex: Int) -> Unit,\n" }.orEmpty()
                    fun key(type: VmListElementType) = when {
                        type.identityProperty != null -> "\"${type.qualifiedName}:\" + raw.${type.identityProperty}"
                        type.reactId -> "\"${type.qualifiedName}:\" + raw.initialState.id"
                        else -> "raw"
                    }
                    fun render(type: VmListElementType) =
                        "${type.simpleName.toListCaseName()}(${type.qualifiedName.substringBeforeLast('.')}.bind${type.simpleName}(raw, launchAction))"
                    val single = list.possibleTypes.singleOrNull()?.takeIf { it.qualifiedName == list.elementQualifiedName }
                    val name = list.propertyName.toUpperCamelCase()
                    // Remembered per model: the row composables restart collection on a new flow instance.
                    val source = "remember(model) { model.boundList(${kotlinStringLiteral(vm.qualifiedName)}, ${kotlinStringLiteral(list.propertyName)}, model.${list.propertyName.kotlinRef()}) }"
                    // Each list binds twice: `Items` scrolls by itself (a LazyColumn), and `ItemsForEach`
                    // renders its rows in place among other content, as SwiftUI's `DeltaForEach` does.
                    for (inline in listOf(false, true)) {
                        val rowsCall = when {
                            list.sectioned && inline -> "DeltaSectionsForEach(source = $source, header = header, "
                            list.sectioned -> "DeltaSections(source = $source, header = header, "
                            inline -> "DeltaForEach(source = $source, "
                            else -> "DeltaRows(source = $source, "
                        }
                        val modifierArgument = if (inline) "" else "modifier = modifier, "
                        val modifierParameter = if (inline) "" else "modifier: Modifier = Modifier, "
                        val method = if (inline) "${name}ForEach" else name
                        file.writeln("    @Composable public fun $method(\n$headerSlot$rows,\n        ${modifierParameter}placeholder: @Composable (Int) -> Unit = {},\n    ) {")
                        if (single != null) {
                            // `raw` already has the row's type; a `when` would be an always-true check plus a
                            // redundant `else`.
                            file.writeln("        ${rowsCall}${modifierArgument}placeholder = placeholder, identity = { raw -> ${key(single)} }) { raw -> ${render(single)} }\n    }")
                            continue
                        }
                        file.writeln("        ${rowsCall}${modifierArgument}placeholder = placeholder, identity = { raw -> when (raw) {")
                        list.possibleTypes.forEach { file.writeln("            is ${it.qualifiedName} -> ${key(it)}") }
                        file.writeln("            else -> error(\"Undeclared ${vm.simpleName}.${list.propertyName} child\")\n        } }) { raw -> when (raw) {")
                        list.possibleTypes.forEach { file.writeln("            is ${it.qualifiedName} -> ${render(it)}") }
                        file.writeln("            else -> error(\"Undeclared ${vm.simpleName}.${list.propertyName} child\")\n        } }\n    }")
                    }
                }
                file.writeln("}")
                file.writeln("""
                    @Composable
                    public fun bind${vm.simpleName}(model: ${vm.qualifiedName}, launchAction: (suspend () -> Unit) -> Job): ${vm.simpleName}Binding {
                        // A throw from `state` goes to the view model's failure handler; the last state stays.
                        val state by remember(model) { model.boundState(${kotlinStringLiteral(vm.qualifiedName)}) }.collectAsStateWithLifecycle(model.initialState)
                        return ${vm.simpleName}Binding(model, state, launchAction${bound.joinToString("") { (mutator, property) -> ",\n                            rememberMutatorBinding(state.${property.name}, launchAction) { model.${mutator.name}(it) }" }})
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
