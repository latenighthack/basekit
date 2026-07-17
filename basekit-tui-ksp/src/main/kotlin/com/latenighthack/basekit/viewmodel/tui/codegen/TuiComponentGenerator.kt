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
    // Fully-qualified app DI root (e.g. a runtime-built `Core`) whose `@Provides` back the ViewModels'
    // dependencies. When set, the component takes it as a `@Component` parent and `TuiApp` requires it,
    // so an app whose deps are constructed at runtime (not zero-arg) can drive the TUI. Null keeps the
    // zero-arg `create()` path (deps come only from `@ViewModelModule` interfaces).
    private val appComponent: String?,
) {
    fun generate(screens: List<ScreenInfo>, modules: List<String>) {
        codeGenerator.createNewFile(dependencies, rootPackage, "GeneratedTuiComponent", "kt").use { out ->
            out.writeln(render(screens, modules))
        }
    }

    private fun prop(screen: ScreenInfo): String = screen.vmSimpleName.replaceFirstChar { it.lowercase() }

    /** The comma-joined arguments the component passes to the screen's assisted factory, in ctor order.
     *  A navigator param on a screen with no outbound edges gets an inline close target backed by nav. */
    private fun assistedArgs(screen: ScreenInfo): String = screen.assisted.joinToString(", ") { p ->
        when (p.kind) {
            AssistedKind.NAVIGATOR ->
                screen.navigatorClassName?.let { "$it(nav, this)" }
                    ?: "object : ${p.type} { override fun close(context: Any?) { nav.pop() } }"
            AssistedKind.ARGS -> "args as ${p.type}"
            AssistedKind.RESPONDER -> "responder as ${p.type}"
            AssistedKind.NONE -> ""
        }
    }

    /** How the component builds [screen] when it is the root (via screenForViewModel). */
    private fun buildForViewModel(screen: ScreenInfo): String =
        // ARGS/RESPONDER screens can't be a root: there is no caller to supply the assisted value.
        if (screen.assisted.any { it.kind == AssistedKind.ARGS || it.kind == AssistedKind.RESPONDER })
            "error(\"${screen.vmSimpleName} cannot be a root screen; open it via navigation\")"
        else buildScreenExpr(screen)

    /** How the component builds [screen] when it is a push target (via screenForDestination). */
    private fun buildForDestination(screen: ScreenInfo): String = buildScreenExpr(screen)

    private fun buildScreenExpr(screen: ScreenInfo): String = when {
        !screen.injected ->
            "${screen.screenClassName}(${prop(screen)}(nav), nav)"
        screen.factoryName != null ->
            "${screen.screenClassName}(${screen.factoryName}(${assistedArgs(screen)}), nav)"
        else ->
            "${screen.screenClassName}(${prop(screen)}, nav)"
    }

    private fun render(screens: List<ScreenInfo>, modules: List<String>): String = buildString {
        val supertypes = buildList {
            // GeneratedViewModelModule is only needed when some injected screen is built from a plain module
            // binding (no assisted param). Assisted screens come from kotlin-inject's native impl factory.
            if (screens.any { it.injected && it.factoryName == null }) add("$rootPackage.GeneratedViewModelModule")
            addAll(modules)
        }
        val supertypeClause = if (supertypes.isEmpty()) "" else " : " + supertypes.joinToString(", ")

        appendLine("package $rootPackage")
        appendLine()
        appendLine("import com.latenighthack.basekit.navigation.NavigationResponder")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiAppBuilder")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiHost")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiNavigation")
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiScreen")
        appendLine("import me.tatarka.inject.annotations.Component")
        appendLine("import kotlin.reflect.KClass")
        appendLine()
        val ctorClause = if (appComponent != null) "(\n    @Component public val app: $appComponent,\n)" else ""
        appendLine("@Component")
        appendLine("public abstract class GeneratedTuiComponent$ctorClause$supertypeClause {")

        // kotlin-inject injection points for graph-built ViewModels: an `(Assisted) -> Impl` factory when
        // the impl takes an assisted param (navigator/args/responder), else the ViewModel widened to its
        // interface by GeneratedViewModelModule.
        val injected = screens.filter { it.injected }
        if (injected.isNotEmpty()) {
            appendLine()
            for (screen in injected) {
                if (screen.factoryName != null) {
                    appendLine("    public abstract val ${screen.factoryName}: (${screen.assisted.joinToString(", ") { it.type }}) -> ${screen.implQualifiedName}")
                } else {
                    appendLine("    public abstract val ${prop(screen)}: ${screen.vmQualifiedName}")
                }
            }
        }

        appendLine()
        appendLine("    public fun screenForViewModel(viewModel: KClass<*>, nav: TuiNavigation): TuiScreen = when (viewModel) {")
        for (screen in screens) {
            appendLine("        ${screen.vmQualifiedName}::class -> ${buildForViewModel(screen)}")
        }
        appendLine("        else -> error(\"No TUI screen bound for ViewModel \" + viewModel)")
        appendLine("    }")
        appendLine()
        appendLine("    @Suppress(\"UNCHECKED_CAST\")")
        appendLine("    public fun screenForDestination(destination: KClass<*>, args: Any?, nav: TuiNavigation, responder: NavigationResponder<*>? = null): TuiScreen = when (destination) {")
        for (screen in screens) {
            appendLine("        ${screen.destQualifiedName}::class -> ${buildForDestination(screen)}")
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
        val appParam = if (appComponent != null) "app: $appComponent, " else ""
        val createArg = if (appComponent != null) "app" else ""
        appendLine("public fun TuiApp(${appParam}configure: TuiAppBuilder.() -> Unit) {")
        appendLine("    val builder = TuiAppBuilder().apply(configure)")
        appendLine("    val root = builder.requireRoot()")
        appendLine("    val component = GeneratedTuiComponent::class.create($createArg)")
        appendLine("    TuiHost { nav -> component.screenForViewModel(root, nav) }.run()")
        appendLine("}")
    }
}
