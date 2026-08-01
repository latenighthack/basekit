package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits, per `@ViewModelSpec`, an `Abstract{Vm}Activity` extending the runtime `BaseActivity`. The
 * developer subclasses it, supplies the view, and overrides the typed `onStateChanged(state)`; each
 * `@ViewModelList` gets a `bind{ListProp}(recyclerView, ...)` helper wired to the deltalist adapter.
 * Each single-arg mutator gets a fire-and-forget helper that launches it on the Activity's lifecycle.
 */
class AndroidBindingGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(viewModels: List<VmInfo>) {
        for (vm in viewModels) {
            val className = "Abstract${vm.simpleName}Activity"

            val mutatorMethods = vm.mutators.joinToString("\n\n") { mutator ->
                """
                |    /** Runs the `${mutator.name}` mutator on this Activity's lifecycle scope. */
                |    protected fun ${mutator.name}(${mutator.paramName}: ${mutator.paramTypeQualifiedName}) {
                |        lifecycleScope.launch { viewModel.${mutator.name}(${mutator.paramName}) }
                |    }
                """.trimMargin()
            }

            val listBinders = vm.lists.joinToString("\n\n") { list ->
                val cap = list.propertyName.toUpperCamelCase()
                val elementState = list.elementStateQualifiedName ?: "kotlin.Any"
                """
                |    protected fun bind$cap(
                |        recyclerView: androidx.recyclerview.widget.RecyclerView,
                |        viewFactory: (android.view.ViewGroup) -> android.view.View,
                |        binder: (android.view.View, ${list.elementQualifiedName}) -> Unit = { _, _ -> },
                |        stateBinder: (android.view.View, ${list.elementQualifiedName}, $elementState) -> Unit,
                |    ) {
                |        recyclerView.bindViewModels(
                |            this,
                |            lifecycleScope,
                |            viewModel.${list.propertyName},
                |            viewFactory,
                |            binder,
                |            stateBinder,
                |        )
                |    }
                """.trimMargin()
            }

            codeGenerator.createNewFile(dependencies, vm.packageName, className, "kt").apply {
                writeln("package ${vm.packageName}")
                writeln()
                writeln("import androidx.lifecycle.lifecycleScope")
                writeln("import com.latenighthack.basekit.viewmodel.bindViewModels")
                writeln("import kotlinx.coroutines.launch")
                writeln()
                writeln(
                    """
                    |/** Generated Android binding for [${vm.qualifiedName}]. */
                    |public abstract class $className :
                    |    com.latenighthack.basekit.viewmodel.BaseActivity<${vm.qualifiedName}, ${vm.stateQualifiedName}>() {
                    |
                    |    /** Convenience typed state hook; override instead of the two-arg form. */
                    |    protected open fun onStateChanged(state: ${vm.stateQualifiedName}) {}
                    |
                    |    override fun onStateChanged(viewModel: ${vm.qualifiedName}, state: ${vm.stateQualifiedName}) {
                    |        onStateChanged(state)
                    |    }
                    """.trimMargin()
                )
                if (mutatorMethods.isNotEmpty()) {
                    writeln()
                    writeln(mutatorMethods)
                }
                if (listBinders.isNotEmpty()) {
                    writeln()
                    writeln(listBinders)
                }
                writeln("}")
            }.close()
        }
    }
}
