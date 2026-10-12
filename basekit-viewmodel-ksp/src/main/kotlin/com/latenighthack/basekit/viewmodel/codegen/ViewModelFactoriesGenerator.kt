package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * One `@ViewModelInject` implementation as a kotlin-inject factory: [name] (`homeFactory`) returns
 * [implQualifiedName] from its `@Assisted` constructor parameters, whose types are [parameterTypes].
 */
data class ViewModelFactoryInfo(
    val name: String,
    val implQualifiedName: String,
    val parameterTypes: List<String>,
)

/**
 * Emits `GeneratedViewModelFactories`: one abstract factory per `@ViewModelInject` implementation,
 *
 *  `val homeFactory: (HomeNavigator) -> HomeViewModel`
 *
 * taking the implementation's `@Assisted` parameters in order. An app's kotlin-inject component
 * implements them by extending the interface:
 *
 *  `@Component abstract class ViewModelComponent(@Component val core: Core) : GeneratedViewModelFactories`
 */
class ViewModelFactoriesGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
) {
    fun generate(factories: List<ViewModelFactoryInfo>, packageName: String) {
        codeGenerator.createNewFile(dependencies, packageName, INTERFACE_NAME, "kt").use { out ->
            out.writeln(render(factories, packageName))
        }
    }

    private fun render(factories: List<ViewModelFactoryInfo>, packageName: String): String = buildString {
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName")
            appendLine()
        }
        appendLine("public interface $INTERFACE_NAME {")
        factories.forEachIndexed { index, factory ->
            if (index > 0) appendLine()
            val parameters = factory.parameterTypes.joinToString(", ", "(", ")")
            appendLine("    public val ${factory.name}: $parameters -> ${factory.implQualifiedName}")
        }
        appendLine("}")
    }

    companion object {
        const val INTERFACE_NAME = "GeneratedViewModelFactories"
    }
}
