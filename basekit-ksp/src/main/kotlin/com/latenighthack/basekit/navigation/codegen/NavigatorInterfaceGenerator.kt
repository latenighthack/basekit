package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Emits the navigation graph as Kotlin interfaces:
 *
 *  - `CloseNavigationTarget` — a single shared interface with `close(context)`.
 *  - `<X>NavigationTarget` — one per destination that is navigated *to*, holding
 *    `fun navigateTo<X>(args, [source], context)`. When more than one call site navigates to that
 *    destination, an app-wide `<X>Source` enum (one entry per call site) is threaded as `source`.
 *  - `<Name>Navigator` — one per destination that navigates *out*; extends `CloseNavigationTarget`
 *    plus the `<X>NavigationTarget` of every destination it reaches. This is what the concrete
 *    feature is injected with.
 *
 * All interfaces are shared graph nodes emitted into a single package, so the generator consumes the
 * full destination set before writing. Generated only in the metadata (commonMain) pass.
 */
class NavigatorInterfaceGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val dependencies: Dependencies,
    private val navPackage: String,
) {
    private data class CallSite(val sourceNavName: String, val methodName: String)

    fun generate(destinations: List<DestinationInfo>) {
        val byQualifiedName = destinations.associateBy { it.qualifiedName }

        // target qualified name -> ordered, unique call sites navigating to it
        val targetCallSites = LinkedHashMap<String, MutableList<CallSite>>()
        // source qualified name -> ordered, unique target qualified names it navigates to
        val outboundTargets = LinkedHashMap<String, LinkedHashSet<String>>()

        for (destination in destinations) {
            for (edge in destination.edges) {
                val sites = targetCallSites.getOrPut(edge.targetQualifiedName) { mutableListOf() }
                val site = CallSite(destination.navName, edge.methodName)
                if (site !in sites) sites.add(site)
                outboundTargets.getOrPut(destination.qualifiedName) { LinkedHashSet() }
                    .add(edge.targetQualifiedName)
            }
        }

        if (targetCallSites.isEmpty()) return

        writeInterface("CloseNavigationTarget") {
            """
            |interface CloseNavigationTarget {
            |    fun close(context: Any? = null)
            |}
            """.trimMargin()
        }

        for ((targetQualifiedName, callSites) in targetCallSites) {
            val target = byQualifiedName[targetQualifiedName]
            if (target == null) {
                logger.warn("@NavigateTo target $targetQualifiedName is not a @Destination; skipping")
                continue
            }

            val cap = target.navName.toUpperCamelCase()
            val method = "navigateTo$cap"
            val argsType = target.argsQualifiedName
            val hasSource = callSites.size > 1

            val params = buildList {
                if (argsType != null) add("args: $argsType")
                if (hasSource) add("source: ${cap}Source")
                add("context: Any? = null")
            }.joinToString(", ")

            val enumBlock = if (hasSource) {
                val entries = callSites
                    .map { "${it.sourceNavName.uppercase()}_${it.methodName.toUpperSnakeCase()}" }
                    .distinct()
                    .joinToString(",\n        ")
                "\n\n    enum class ${cap}Source {\n        $entries\n    }"
            } else {
                ""
            }

            writeInterface("${cap}NavigationTarget") {
                """
                |interface ${cap}NavigationTarget {
                |    fun $method($params)$enumBlock
                |}
                """.trimMargin()
            }
        }

        for ((sourceQualifiedName, targetQualifiedNames) in outboundTargets) {
            val source = byQualifiedName[sourceQualifiedName] ?: continue
            val sourceCap = source.navName.toUpperCamelCase()

            val supertypes = buildList {
                add("CloseNavigationTarget")
                for (targetQualifiedName in targetQualifiedNames) {
                    byQualifiedName[targetQualifiedName]?.let {
                        add("${it.navName.toUpperCamelCase()}NavigationTarget")
                    }
                }
            }.joinToString(", ")

            writeInterface("${sourceCap}Navigator") {
                "interface ${sourceCap}Navigator : $supertypes"
            }
        }
    }

    private inline fun writeInterface(fileName: String, body: () -> String) {
        codeGenerator.createNewFile(dependencies, navPackage, fileName, "kt").apply {
            writeln("package $navPackage")
            writeln()
            writeln(body())
        }.close()
    }
}
