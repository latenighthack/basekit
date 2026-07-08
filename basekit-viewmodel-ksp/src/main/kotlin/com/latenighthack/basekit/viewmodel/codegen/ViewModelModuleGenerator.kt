package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** One `@ViewModelInject` implementation (without an `@Assisted` navigator): the `@ViewModelSpec`
 * interface it satisfies and its concrete class. */
data class InjectVmInfo(
    val interfaceQualifiedName: String,
    val implQualifiedName: String,
)

/**
 * Emits `GeneratedViewModelModule`, a kotlin-inject provider interface the platform `@Component`
 * includes. Each navigator-free `@ViewModelInject` implementation contributes one widening binding:
 *
 *  `@Provides fun bindX(impl: RealX): X = impl`
 *
 * kotlin-inject builds `RealX` from its `@Inject` constructor; this only widens it to its interface so
 * the rest of the graph depends on the interface. (Assisted VMs are built by the component directly
 * from kotlin-inject's native `(Navigator) -> RealX` factory, so they need no binding here.)
 */
class ViewModelModuleGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(vms: List<InjectVmInfo>, packageName: String) {
        codeGenerator.createNewFile(dependencies, packageName, MODULE_NAME, "kt").use { out ->
            out.writeln(render(vms, packageName))
        }
    }

    private fun render(vms: List<InjectVmInfo>, packageName: String): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("import me.tatarka.inject.annotations.Provides")
        appendLine()
        appendLine("public interface $MODULE_NAME {")
        for (vm in vms) {
            val bindName = "bind" + vm.interfaceQualifiedName.substringAfterLast('.')
            appendLine()
            appendLine("    @Provides")
            appendLine("    public fun $bindName(impl: ${vm.implQualifiedName}): ${vm.interfaceQualifiedName} = impl")
        }
        appendLine("}")
    }

    companion object {
        const val MODULE_NAME = "GeneratedViewModelModule"
    }
}
