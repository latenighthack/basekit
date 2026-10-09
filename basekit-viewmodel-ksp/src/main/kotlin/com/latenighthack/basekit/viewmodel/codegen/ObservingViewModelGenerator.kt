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
        appendLine()
        appendLine("public class Observing${vm.simpleName}(")
        appendLine("    private val delegate: ${vm.qualifiedName},")
        appendLine("    internal val actionObserver: ViewModelActionObserver,")
        appendLine(") : ${vm.qualifiedName} by delegate {")
        vm.actions.forEach { action ->
            appendLine("    override suspend fun `${action.name}`() {")
            appendLine("        actionObserver.onAction(ViewModelActionEvent(${literal(vm.qualifiedName)}, ${literal(action.name)}, delegate))")
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

    private fun literal(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
}
