package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits one `<Vm>TuiNavigator` per screen whose destination has outbound `@NavigateTo` edges: a
 * concrete implementation of the generated `…Navigator` interface backed by the screen's
 * [com.latenighthack.basekit.viewmodel.tui.TuiNavigation]. This is the navigator injected into the
 * ViewModel — the ViewModel calls `navigateTo…`/`close`, and the implementation drives the back stack
 * (`navigateTo…` -> push the target screen, `close` -> pop). Navigation is thus always an explicit
 * ViewModel call, never inferred by the screen from the navigation graph.
 */
class TuiNavigatorGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val rootPackage: String,
) {
    fun generate(screens: List<ScreenInfo>) {
        for (screen in screens) {
            val navigatorInterface = screen.navigatorInterface ?: continue
            val className = screen.navigatorClassName ?: continue
            codeGenerator.createNewFile(dependencies, rootPackage, className, "kt").use { out ->
                out.writeln(render(screen, navigatorInterface, className))
            }
        }
    }

    private fun render(screen: ScreenInfo, navigatorInterface: String, className: String): String = buildString {
        appendLine("package $rootPackage")
        appendLine()
        appendLine("import com.latenighthack.basekit.viewmodel.tui.TuiNavigation")
        appendLine()
        appendLine("/** Generated navigator for [${screen.vmQualifiedName}], injected into the ViewModel. */")
        appendLine("public class $className(")
        appendLine("    private val nav: TuiNavigation,")
        appendLine("    private val component: GeneratedTuiComponent,")
        appendLine(") : $navigatorInterface {")
        appendLine()
        appendLine("    override fun close(context: Any?) {")
        appendLine("        nav.pop()")
        appendLine("    }")
        for (method in screen.navMethods) {
            appendLine()
            val params = buildList {
                method.argsType?.let { add("args: $it") }
                method.sourceType?.let { add("source: $it") }
                add("context: Any?")
            }.joinToString(", ")
            val argExpr = if (method.argsType != null) "args" else "null"
            if (method.responseType != null) {
                // Responding destination: the generated interface method is `suspend` and returns R?.
                // pushForResult builds the screen with a NavigationResponder and suspends until the screen
                // responds (a value) or is dismissed (null).
                appendLine("    override suspend fun ${method.methodName}($params): ${method.responseType}? =")
                appendLine("        nav.pushForResult<${method.responseType}> { responder ->")
                appendLine("            component.screenForDestination(${method.targetDestQualifiedName}::class, $argExpr, nav, responder)")
                appendLine("        }")
            } else {
                appendLine("    override fun ${method.methodName}($params) {")
                appendLine("        nav.push(component.screenForDestination(${method.targetDestQualifiedName}::class, $argExpr, nav))")
                appendLine("    }")
            }
        }
        append("}")
    }
}
