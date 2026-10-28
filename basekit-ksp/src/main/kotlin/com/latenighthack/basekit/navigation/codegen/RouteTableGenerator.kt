package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits `GeneratedRoutes.register(table)`, which registers one deep-link route per `@Route`
 * destination, plus one per `@Route` alias, into a [com.latenighthack.basekit.navigation.RouteTable].
 * Each template's factory binds the `@RouteArg` properties it captures from the matched URL's path and
 * query (String-valued in this slice): a non-null one with `params.getValue`, a `String?` one with
 * `params[...]`, which is null when an optional `key={name?}` capture is missing. A `String?` route arg
 * the template doesn't capture is set to null. Every other argument gets its
 * [DestinationInfo.routeDefaults] value, so a destination opened from an address never reads an unset
 * argument on any platform.
 */
class RouteTableGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val navPackage: String,
) {
    fun generate(destinations: List<DestinationInfo>) {
        val routed = destinations.filter { it.routePath != null && it.argsQualifiedName != null }
        if (routed.isEmpty()) return

        val body = buildString {
            appendLine("package $navPackage")
            appendLine()
            appendLine("import com.latenighthack.basekit.navigation.RouteTable")
            appendLine()
            appendLine("public object GeneratedRoutes {")
            appendLine("    public fun register(table: RouteTable) {")
            for (destination in routed) {
                val argsType = destination.argsQualifiedName!!
                for (path in listOf(destination.routePath!!) + destination.routeAliases) {
                    val captured = routeParams(path)
                    val readsParams = destination.routeArgs.any { it in captured }
                    val assignments = destination.routeArgs.map { arg ->
                        when {
                            arg !in captured -> "$arg = null"
                            arg in destination.nullableRouteArgs -> "$arg = params[\"$arg\"]"
                            else -> "$arg = params.getValue(\"$arg\")"
                        }
                    } + destination.routeDefaults.map { (arg, value) -> "$arg = $value" }
                    if (assignments.isEmpty()) {
                        appendLine("        table.register(\"$path\") { _ -> $argsType() }")
                    } else {
                        appendLine("        table.register(\"$path\") { ${if (readsParams) "params" else "_"} ->")
                        appendLine("            $argsType().apply {")
                        assignments.forEach { appendLine("                $it") }
                        appendLine("            }")
                        appendLine("        }")
                    }
                }
            }
            appendLine("    }")
            appendLine("}")
        }

        codeGenerator.createNewFile(dependencies, navPackage, "GeneratedRoutes", "kt").apply {
            write(body.encodeToByteArray())
        }.close()
    }
}
