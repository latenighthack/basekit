package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** Common decorators sit below every UI binding, so shared Kotlin and native calls use the same seam. */
class ObservingViewModelGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(models: List<VmInfo>) {
        models.forEach { vm ->
            codeGenerator.createNewFile(dependencies, vm.packageName, "Observing${vm.simpleName}", "kt").use {
                it.writeln(render(vm))
            }
        }
    }

    internal fun render(vm: VmInfo): String = buildString {
        if (vm.packageName.isNotEmpty()) appendLine("package ${vm.packageName}")
        appendLine()
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelActionEvent")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelActionObserver")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelFailure")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelFailureHandler")
        appendLine("import kotlinx.coroutines.flow.Flow")
        appendLine()
        appendLine("public class Observing${vm.simpleName}(")
        appendLine("    private val delegate: ${vm.qualifiedName},")
        appendLine("    internal val actionObserver: ViewModelActionObserver,")
        appendLine(") : ${vm.qualifiedName} by delegate, ViewModelFailureHandler<${vm.stateQualifiedName}> {")
        // Bindings look for the handler on the instance they get, which is this decorator.
        appendLine("    @Suppress(\"UNCHECKED_CAST\")")
        appendLine("    override suspend fun onStateFailed(failure: ViewModelFailure, lastState: ${vm.stateQualifiedName}): Flow<${vm.stateQualifiedName}> =")
        appendLine("        (delegate as? ViewModelFailureHandler<${vm.stateQualifiedName}>)?.onStateFailed(failure, lastState)")
        appendLine("            ?: super.onStateFailed(failure, lastState)")
        appendLine()
        appendLine("    override suspend fun onListFailed(failure: ViewModelFailure) {")
        appendLine("        (delegate as? ViewModelFailureHandler<*>)?.onListFailed(failure) ?: super.onListFailed(failure)")
        appendLine("    }")
        vm.actions.forEach { action ->
            appendLine("    override suspend fun `${action.name}`() {")
            appendLine("        actionObserver.onAction(ViewModelActionEvent(${kotlinStringLiteral(vm.qualifiedName)}, ${kotlinStringLiteral(action.name)}, delegate))")
            appendLine("        delegate.`${action.name}`()")
            appendLine("    }")
        }
        appendLine("}")
        appendLine()
        appendLine("/** Install in the factory returning the public specification, before handing it to any binding. */")
        appendLine("public fun ${vm.qualifiedName}.observingActions(observer: ViewModelActionObserver): ${vm.qualifiedName} =")
        appendLine("    if (observer === ViewModelActionObserver.NoOp || (this is Observing${vm.simpleName} && actionObserver === observer)) this")
        appendLine("    else Observing${vm.simpleName}(this, observer)")
    }
}
