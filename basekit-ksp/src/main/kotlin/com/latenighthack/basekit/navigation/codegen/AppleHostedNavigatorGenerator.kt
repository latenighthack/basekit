package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** Generates the typed host boundary and scoped navigators consumed by native Apple routers. */
class AppleHostedNavigatorGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val navPackage: String,
) {
    private data class CallSite(
        val source: DestinationInfo,
        val methodName: String,
        val target: DestinationInfo,
        // Unique flat-enum identity for this edge. Equal to [baseName] unless the source action fans
        // out (multiple @NavigateTo) — then it is disambiguated by target so the two edges don't collide.
        val edgeName: String,
    ) {
        // The <Target>Source enum value name — always source+method, independent of edgeName disambiguation.
        val sourceEntry: String get() = "${source.navName.uppercase()}_${methodName.toUpperSnakeCase()}"
    }

    fun generate(destinations: List<DestinationInfo>) {
        val byName = destinations.associateBy { it.qualifiedName }
        val rawSites = destinations.flatMap { source ->
            source.edges.mapNotNull { edge ->
                byName[edge.targetQualifiedName]?.let { Triple(source, edge.methodName, it) }
            }
        }.distinctBy { (source, method, target) -> Triple(source.qualifiedName, method, target.qualifiedName) }
        if (rawSites.isEmpty()) return

        // A single action can carry multiple @NavigateTo (fan out to several targets); those call sites
        // share a source+method base name. Disambiguate colliding names by target so every
        // AppleNavigationEdge entry stays unique and no call site is dropped.
        val baseName = { source: DestinationInfo, method: String ->
            "${source.navName.uppercase()}_${method.toUpperSnakeCase()}"
        }
        val colliding = rawSites.groupingBy { (source, method, _) -> baseName(source, method) }
            .eachCount().filterValues { it > 1 }.keys
        val sites = rawSites.map { (source, method, target) ->
            val base = baseName(source, method)
            val edgeName = if (base in colliding) "${base}_TO_${target.navName.uppercase()}" else base
            CallSite(source, method, target, edgeName)
        }

        val sitesByTarget = sites.groupBy { it.target.qualifiedName }
        generateEdge(sites)
        generateHost(sitesByTarget)
        destinations.forEach { generateNavigator(it, sites, sitesByTarget) }
    }

    private fun generateEdge(sites: List<CallSite>) = write("AppleNavigationEdge") {
        buildString {
            appendLine("public enum class AppleNavigationEdge {")
            sites.forEach { appendLine("    ${it.edgeName},") }
            appendLine("}")
        }
    }

    private fun generateHost(sitesByTarget: Map<String, List<CallSite>>) = write("AppleNavigationHost") {
        buildString {
            appendLine("import com.latenighthack.basekit.navigation.NavigationResponder")
            appendLine()
            appendLine("public interface AppleNavigationHost {")
            appendLine("    public fun close(ownerId: String, context: Any?)")
            for (sites in sitesByTarget.values) {
                val target = sites.first().target
                val cap = target.navName.toUpperCamelCase()
                val params = buildList {
                    add("ownerId: String")
                    target.argsQualifiedName?.let { add("args: $it") }
                    add("edge: AppleNavigationEdge")
                    add("context: Any?")
                    target.responseQualifiedName?.let { add("responder: NavigationResponder<$it>") }
                }.joinToString(", ")
                appendLine("    public fun show$cap($params)")
            }
            appendLine("}")
        }
    }

    private fun generateNavigator(
        destination: DestinationInfo,
        sites: List<CallSite>,
        sitesByTarget: Map<String, List<CallSite>>,
    ) {
        val sourceSites = sites.filter { it.source.qualifiedName == destination.qualifiedName }
        val cap = destination.navName.toUpperCamelCase()
        val navigatorType = if (sourceSites.isEmpty()) "CloseNavigationTarget" else "${cap}Navigator"

        write("Apple${cap}Navigator") {
            buildString {
                appendLine("import com.latenighthack.basekit.navigation.awaitNavigationResult")
                appendLine("import com.latenighthack.basekit.navigation.runOnMainThread")
                appendLine()
                appendLine("// Host calls cross into platform UI (UIKit/AppKit), which requires the main thread; the")
                appendLine("// ViewModel action driving navigation may run on any dispatcher, so every call hops to main.")
                appendLine("public class Apple${cap}Navigator(")
                appendLine("    public val ownerId: String,")
                appendLine("    private val host: AppleNavigationHost,")
                appendLine(") : $navigatorType {")
                appendLine("    override fun close(context: Any?) = runOnMainThread { host.close(ownerId, context) }")

                sourceSites.groupBy { it.target.qualifiedName }.values.forEach { targetSites ->
                    val target = targetSites.first().target
                    val targetCap = target.navName.toUpperCamelCase()
                    val allTargetSites = sitesByTarget.getValue(target.qualifiedName)
                    val hasSource = allTargetSites.size > 1
                    val params = buildList {
                        target.argsQualifiedName?.let { add("args: $it") }
                        if (hasSource) add("source: ${targetCap}NavigationTarget.${targetCap}Source")
                        add("context: Any?")
                    }.joinToString(", ")
                    val edgeExpr = if (hasSource) {
                        buildString {
                            appendLine("when (source) {")
                            allTargetSites.forEach { site ->
                                appendLine("                ${targetCap}NavigationTarget.${targetCap}Source.${site.sourceEntry} -> AppleNavigationEdge.${site.edgeName}")
                            }
                            append("            }")
                        }
                    } else {
                        "AppleNavigationEdge.${allTargetSites.single().edgeName}"
                    }
                    val callArgs = buildList {
                        add("ownerId")
                        if (target.argsQualifiedName != null) add("args")
                        add(edgeExpr)
                        add("context")
                        if (target.responseQualifiedName != null) add("responder")
                    }.joinToString(", ")

                    appendLine()
                    if (target.responseQualifiedName != null) {
                        appendLine("    override suspend fun navigateTo$targetCap($params): ${target.responseQualifiedName}? =")
                        appendLine("        awaitNavigationResult { responder ->")
                        appendLine("            runOnMainThread { host.show$targetCap($callArgs) }")
                        appendLine("        }")
                    } else {
                        appendLine("    override fun navigateTo$targetCap($params) {")
                        appendLine("        runOnMainThread { host.show$targetCap($callArgs) }")
                        appendLine("    }")
                    }
                }
                appendLine("}")
            }
        }
    }

    private inline fun write(name: String, body: () -> String) {
        codeGenerator.createNewFile(dependencies, navPackage, name, "kt").use { out ->
            out.writeln("package $navPackage")
            out.writeln()
            out.writeln(body().trimEnd())
        }
    }
}
