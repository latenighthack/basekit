package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits the kotlin-inject `GeneratedTuiComponent` that provides each bound ViewModel and maps both
 * ViewModel classes and destination classes to their screens, plus the top-level `TuiApp { … }` entry
 * the app calls. `TuiApp` applies the builder, creates the component via kotlin-inject's `create()`,
 * and runs the resolved root screen in a [com.latenighthack.basekit.viewmodel.tui.TuiHost].
 */
class TuiComponentGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val rootPackage: String,
) {
    fun generate(screens: List<ScreenInfo>) {
        codeGenerator.createNewFile(dependencies, rootPackage, "GeneratedTuiComponent", "kt").use { out ->
            out.writeln(render(screens))
        }
    }

    private fun prop(screen: ScreenInfo): String = screen.vmSimpleName.replaceFirstChar { it.lowercase() }

    private fun render(screens: List<ScreenInfo>): String = buildString {
        appendLine("package $rootPackage")
        appendLine()
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiAppBuilder")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiHost")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiNavigation")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiScreen")
        appendLine("import me.tatarka.inject.annotations.Component")
        appendLine("import me.tatarka.inject.annotations.Provides")
        appendLine("import kotlin.reflect.KClass")
        appendLine()
        appendLine("@Component")
        appendLine("public abstract class GeneratedTuiComponent {")
        appendLine()
        for (screen in screens) {
            appendLine("    public abstract val ${prop(screen)}: ${screen.vmQualifiedName}")
        }
        appendLine()
        for (screen in screens) {
            appendLine("    @Provides")
            appendLine("    public fun provide${screen.vmSimpleName}(): ${screen.vmQualifiedName} = ${screen.implQualifiedName}()")
            appendLine()
        }
        for (screen in screens) {
            appendLine("    private val ${prop(screen)}Instance by lazy { ${prop(screen)} }")
        }
        appendLine()
        appendLine("    public fun screenForViewModel(viewModel: KClass<*>, nav: TuiNavigation): TuiScreen = when (viewModel) {")
        for (screen in screens) {
            appendLine("        ${screen.vmQualifiedName}::class -> ${screen.screenClassName}(${prop(screen)}Instance, nav, this)")
        }
        appendLine("        else -> error(\"No TUI screen bound for ViewModel \" + viewModel)")
        appendLine("    }")
        appendLine()
        appendLine("    public fun screenForDestination(destination: KClass<*>, args: Any?, nav: TuiNavigation): TuiScreen = when (destination) {")
        for (screen in screens) {
            appendLine("        ${screen.destQualifiedName}::class -> ${screen.screenClassName}(${prop(screen)}Instance, nav, this)")
        }
        appendLine("        else -> error(\"No TUI screen bound for destination \" + destination)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/**")
        appendLine(" * Entry point: `TuiApp { root<SomeViewModel>() }`. Builds the kotlin-inject component and runs the")
        appendLine(" * resolved root screen in a terminal until the user quits.")
        appendLine(" */")
        appendLine("public fun TuiApp(configure: TuiAppBuilder.() -> Unit) {")
        appendLine("    val builder = TuiAppBuilder().apply(configure)")
        appendLine("    val root = builder.requireRoot()")
        appendLine("    val component = GeneratedTuiComponent::class.create()")
        appendLine("    TuiHost { nav -> component.screenForViewModel(root, nav) }.run()")
        appendLine("}")
    }
}
