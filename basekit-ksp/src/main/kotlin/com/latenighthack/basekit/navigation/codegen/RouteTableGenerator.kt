package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits `GeneratedRoutes.register(table)`, which registers one deep-link route per `@Route`
 * destination into a [com.latenighthack.basekit.navigation.RouteTable]. Path parameters annotated
 * `@RouteArg` are bound from the matched URL (String-valued in this slice).
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
                val path = destination.routePath!!
                val argsType = destination.argsQualifiedName!!
                if (destination.routeArgs.isEmpty()) {
                    appendLine("        table.register(\"$path\") { _ -> $argsType() }")
                } else {
                    appendLine("        table.register(\"$path\") { params ->")
                    appendLine("            $argsType().apply {")
                    for (arg in destination.routeArgs) {
                        appendLine("                $arg = params.getValue(\"$arg\")")
                    }
                    appendLine("            }")
                    appendLine("        }")
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
