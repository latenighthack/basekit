package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits the kotlin-inject `GeneratedTuiComponent` that maps both ViewModel classes and destination
 * classes to their screens, plus the top-level `TuiApp { … }` entry the app calls. Each ViewModel is
 * built per screen instance so its generated navigator (bound to that screen's [TuiNavigation]) can be
 * injected. `TuiApp` applies the builder, creates the component via kotlin-inject's `create()`, and
 * runs the resolved root screen in a [com.latenighthack.basekit.viewmodel.tui.TuiHost].
 *
 * `@ViewModelInject` screens are built through the kotlin-inject graph: the component includes the
 * generated `GeneratedViewModelModule` (plus any `@ViewModelModule` the app supplies) and exposes an
 * injection point per screen — a plain instance, or a `(Navigator) -> Vm` factory when the screen has
 * outbound edges so its per-screen navigator is passed as the assisted argument. Screens whose impl is
 * not `@ViewModelInject` fall back to direct constructor calls.
 */
class TuiComponentGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val rootPackage: String,
) {
    fun generate(screens: List<ScreenInfo>, modules: List<String>) {
        codeGenerator.createNewFile(dependencies, rootPackage, "GeneratedTuiComponent", "kt").use { out ->
            out.writeln(render(screens, modules))
        }
    }

    private fun prop(screen: ScreenInfo): String = screen.vmSimpleName.replaceFirstChar { it.lowercase() }

    /** How the component obtains a fresh ViewModel for [screen], wrapped in its generated screen. */
    private fun buildExpr(screen: ScreenInfo): String = when {
        !screen.injected ->
            "${screen.screenClassName}(${prop(screen)}(nav), nav)"
        screen.navigatorClassName != null ->
            "${screen.screenClassName}(${prop(screen)}Factory(${screen.navigatorClassName}(nav, this)), nav)"
        else ->
            "${screen.screenClassName}(${prop(screen)}, nav)"
    }

    private fun render(screens: List<ScreenInfo>, modules: List<String>): String = buildString {
        val supertypes = buildList {
            // GeneratedViewModelModule only exists when some injected screen is navigator-free (assisted
            // screens are built from kotlin-inject's native impl factory and contribute no module binding).
            if (screens.any { it.injected && it.navigatorInterface == null }) add("$rootPackage.GeneratedViewModelModule")
            addAll(modules)
        }
        val supertypeClause = if (supertypes.isEmpty()) "" else " : " + supertypes.joinToString(", ")

        appendLine("package $rootPackage")
        appendLine()
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiAppBuilder")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiHost")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiNavigation")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiScreen")
        appendLine("import me.tatarka.inject.annotations.Component")
        appendLine("import kotlin.reflect.KClass")
        appendLine()
        appendLine("@Component")
        appendLine("public abstract class GeneratedTuiComponent$supertypeClause {")

        // kotlin-inject injection points for graph-built ViewModels: a factory when the screen has a
        // navigator, else the ViewModel directly. kotlin-inject satisfies these via GeneratedViewModelModule.
        val injected = screens.filter { it.injected }
        if (injected.isNotEmpty()) {
            appendLine()
            for (screen in injected) {
                if (screen.navigatorInterface != null) {
                    // Assisted: kotlin-inject provides the native `(Navigator) -> Impl` factory directly.
                    appendLine("    public abstract val ${prop(screen)}Factory: (${screen.navigatorInterface}) -> ${screen.implQualifiedName}")
                } else {
                    // Navigator-free: widened to its interface by GeneratedViewModelModule.
                    appendLine("    public abstract val ${prop(screen)}: ${screen.vmQualifiedName}")
                }
            }
        }

        appendLine()
        appendLine("    public fun screenForViewModel(viewModel: KClass<*>, nav: TuiNavigation): TuiScreen = when (viewModel) {")
        for (screen in screens) {
            appendLine("        ${screen.vmQualifiedName}::class -> ${buildExpr(screen)}")
        }
        appendLine("        else -> error(\"No TUI screen bound for ViewModel \" + viewModel)")
        appendLine("    }")
        appendLine()
        appendLine("    public fun screenForDestination(destination: KClass<*>, args: Any?, nav: TuiNavigation): TuiScreen = when (destination) {")
        for (screen in screens) {
            appendLine("        ${screen.destQualifiedName}::class -> ${buildExpr(screen)}")
        }
        appendLine("        else -> error(\"No TUI screen bound for destination \" + destination)")
        appendLine("    }")

        // Non-injected screens keep direct construction: with the generated navigator, or none.
        val direct = screens.filter { !it.injected }
        if (direct.isNotEmpty()) {
            appendLine()
            for (screen in direct) {
                val ctorArg = screen.navigatorClassName?.let { "$it(nav, this)" } ?: ""
                appendLine("    private fun ${prop(screen)}(nav: TuiNavigation): ${screen.vmQualifiedName} = ${screen.implQualifiedName}($ctorArg)")
            }
        }
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
