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
        val name = kotlinStringLiteral(vm.qualifiedName)
        val state = vm.stateQualifiedName
        if (vm.packageName.isNotEmpty()) appendLine("package ${vm.packageName}")
        appendLine()
        appendLine("import com.latenighthack.basekit.viewmodel.ObservedChildCache")
        appendLine("import com.latenighthack.basekit.viewmodel.ObservedViewModel")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelActionObserver")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelFailure")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelFailureHandler")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelObserver")
        appendLine("import com.latenighthack.basekit.viewmodel.ViewModelOperationKind")
        appendLine("import com.latenighthack.basekit.viewmodel.actionObserverAsViewModelObserver")
        appendLine("import com.latenighthack.basekit.viewmodel.adaptedActionObserver")
        appendLine("import com.latenighthack.basekit.viewmodel.observeCall")
        appendLine("import com.latenighthack.basekit.viewmodel.viewModelObserver")
        appendLine("import kotlinx.coroutines.flow.Flow")
        // Each child's generated `observing` extension, imported when it is declared in another package.
        vm.children.map { it.typeQualifiedName.substringBeforeLast('.', "") }
            .filter { it.isNotEmpty() && it != vm.packageName }.distinct().sorted()
            .forEach { appendLine("import $it.observing") }
        appendLine()
        appendLine("public class Observing${vm.simpleName}(")
        appendLine("    private val delegate: ${vm.qualifiedName},")
        appendLine("    observer: ViewModelObserver,")
        appendLine(") : ${vm.qualifiedName} by delegate, ViewModelFailureHandler<$state>, ObservedViewModel<$state> {")
        appendLine("    /** Reports zero-argument actions to [actionObserver] before they run, as before [ViewModelObserver] existed. */")
        appendLine("    public constructor(delegate: ${vm.qualifiedName}, actionObserver: ViewModelActionObserver) : this(delegate, actionObserverAsViewModelObserver(actionObserver))")
        appendLine()
        appendLine("    override val basekitObserver: ViewModelObserver = observer")
        appendLine("    internal val actionObserver: ViewModelActionObserver? = adaptedActionObserver(observer)")
        appendLine()
        appendLine("    override fun basekitChangedProperties(previous: $state, next: $state): List<String> {")
        if (vm.stateProperties.isEmpty()) {
            appendLine("        return emptyList()")
        } else {
            appendLine("        val changed = ArrayList<String>(0)")
            vm.stateProperties.forEach { property ->
                val ref = property.name.kotlinRef()
                appendLine("        if (previous.$ref != next.$ref) changed.add(${kotlinStringLiteral(property.name)})")
            }
            appendLine("        return changed")
        }
        appendLine("    }")
        appendLine()
        // Bindings look for the handler on the instance they get, which is this decorator.
        appendLine("    @Suppress(\"UNCHECKED_CAST\")")
        appendLine("    override suspend fun onStateFailed(failure: ViewModelFailure, lastState: $state): Flow<$state> =")
        appendLine("        (delegate as? ViewModelFailureHandler<$state>)?.onStateFailed(failure, lastState)")
        appendLine("            ?: super.onStateFailed(failure, lastState)")
        appendLine()
        appendLine("    override suspend fun onListFailed(failure: ViewModelFailure) {")
        appendLine("        (delegate as? ViewModelFailureHandler<*>)?.onListFailed(failure) ?: super.onListFailed(failure)")
        appendLine("    }")
        vm.children.forEach { child ->
            // A child reports to the same observer. One decorator per child instance keeps its identity.
            val ref = child.propertyName.kotlinRef()
            appendLine()
            appendLine("    private val ${child.propertyName}Observed = ObservedChildCache<${child.typeQualifiedName}> { it.observing(viewModelObserver()) }")
            appendLine("    override val $ref: ${child.typeQualifiedName} get() = ${child.propertyName}Observed.get(delegate.$ref)")
        }
        vm.actions.forEach { action ->
            appendLine()
            appendLine("    override suspend fun `${action.name}`() {")
            appendLine("        basekitObserver.observeCall($name, ${kotlinStringLiteral(action.name)}, ViewModelOperationKind.ACTION, delegate) { delegate.`${action.name}`() }")
            appendLine("    }")
        }
        vm.mutators.forEach { mutator ->
            appendLine()
            appendLine("    override suspend fun `${mutator.name}`(${mutator.paramName.kotlinRef()}: ${mutator.type.kotlinName}) {")
            appendLine("        basekitObserver.observeCall($name, ${kotlinStringLiteral(mutator.name)}, ViewModelOperationKind.MUTATOR, delegate) { delegate.`${mutator.name}`(${mutator.paramName.kotlinRef()}) }")
            appendLine("    }")
        }
        appendLine("}")
        appendLine()
        appendLine("/**")
        appendLine(" * This view model reporting to [observer], with its children and the rows its bindings show; itself")
        appendLine(" * when [observer] is null or already installed. Install it before handing the view model to a binding.")
        appendLine(" */")
        appendLine("public fun ${vm.qualifiedName}.observing(observer: ViewModelObserver?): ${vm.qualifiedName} =")
        appendLine("    if (observer == null || viewModelObserver() === observer) this else Observing${vm.simpleName}(this, observer)")
        appendLine()
        appendLine("/** Install in the factory returning the public specification, before handing it to any binding. */")
        appendLine("public fun ${vm.qualifiedName}.observingActions(observer: ViewModelActionObserver): ${vm.qualifiedName} =")
        appendLine("    if (observer === ViewModelActionObserver.NoOp || (this is Observing${vm.simpleName} && actionObserver === observer)) this")
        appendLine("    else Observing${vm.simpleName}(this, observer)")
    }
}
