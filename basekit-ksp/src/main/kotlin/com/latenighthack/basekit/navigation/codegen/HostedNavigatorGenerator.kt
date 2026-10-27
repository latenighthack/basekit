package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/** Generates the typed host boundary and scoped navigators that the Swift, Compose and web routers present. */
class HostedNavigatorGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val navPackage: String,
) {
    private data class CallSite(
        val edge: NavEdge,
        val target: DestinationInfo,
        // Unique flat-enum identity for this edge. Equal to [sourceEntry] unless the source action fans
        // out (multiple @NavigateTo) — then it is disambiguated by target so the two edges don't collide.
        val edgeName: String,
    ) {
        // The <Target>Source enum value name — always source+method, independent of edgeName disambiguation.
        val sourceEntry: String get() = edge.sourceEntry
    }

    fun generate(destinations: List<DestinationInfo>, hostedComponents: List<DestinationInfo> = emptyList()) {
        val byName = destinations.associateBy { it.qualifiedName }
        val sources = destinations + hostedComponents
        // A call site is the declaring spec's action, not the hosting destination: a row spec embedded
        // in two destinations is one call site reachable from both navigators.
        val rawSites = sources.flatMap { source ->
            source.edges.mapNotNull { edge -> byName[edge.targetQualifiedName]?.let { edge to it } }
        }.distinctBy { (edge, _) -> edge }
        if (rawSites.isEmpty()) return

        // A single action can carry multiple @NavigateTo (fan out to several targets); those call sites
        // share a source+method base name. Disambiguate colliding names by target so every
        // NavigationEdge entry stays unique and no call site is dropped.
        val colliding = rawSites.groupingBy { (edge, _) -> edge.sourceEntry }
            .eachCount().filterValues { it > 1 }.keys
        val sites = rawSites.map { (edge, target) ->
            val base = edge.sourceEntry
            val edgeName = if (base in colliding) "${base}_TO_${target.navName.uppercase()}" else base
            CallSite(edge, target, edgeName)
        }

        val sitesByTarget = sites.groupBy { it.target.qualifiedName }
        generateEdge(sites)
        generateHost(sitesByTarget)
        // A destination named `close` already gets an `HostCloseNavigator`, which also closes.
        if (sources.none { it.navName.toUpperCamelCase() == "Close" }) generateCloseNavigator()
        sources.forEach { generateNavigator(it, sites, sitesByTarget) }
    }

    private fun generateEdge(sites: List<CallSite>) = write("NavigationEdge") {
        buildString {
            appendLine("public enum class NavigationEdge {")
            sites.forEach { appendLine("    ${it.edgeName},") }
            appendLine("}")
        }
    }

    private fun generateHost(sitesByTarget: Map<String, List<CallSite>>) = write("BasekitNavigationHost") {
        buildString {
            appendLine("import com.latenighthack.basekit.navigation.NavigationResponder")
            appendLine()
            appendLine("public interface BasekitNavigationHost {")
            appendLine("    public fun close(ownerId: String, context: Any?)")
            for (sites in sitesByTarget.values) {
                val target = sites.first().target
                val cap = target.navName.toUpperCamelCase()
                val params = buildList {
                    add("ownerId: String")
                    target.argsQualifiedName?.let { add("args: $it") }
                    add("edge: NavigationEdge")
                    add("context: Any?")
                    target.responseQualifiedName?.let { add("responder: NavigationResponder<$it>") }
                }.joinToString(", ")
                appendLine("    public fun show$cap($params)")
            }
            appendLine("}")
        }
    }

    /**
     * A navigator that only closes, for code that needs a `CloseNavigationTarget` without naming a
     * destination (each leaf destination also gets its own `Host<Name>Navigator`).
     */
    private fun generateCloseNavigator() = write("HostCloseNavigator") {
        buildString {
            appendLine("import com.latenighthack.basekit.navigation.runOnMainThread")
            appendLine()
            appendLine("/** Closes the entry [ownerId] owns in [host]; the call hops to the main thread like every host navigator. */")
            appendLine("public class HostCloseNavigator(")
            appendLine("    public val ownerId: String,")
            appendLine("    private val host: BasekitNavigationHost,")
            appendLine(") : CloseNavigationTarget {")
            appendLine("    override fun close(context: Any?) = runOnMainThread { host.close(ownerId, context) }")
            appendLine("}")
        }
    }

    private fun generateNavigator(
        destination: DestinationInfo,
        sites: List<CallSite>,
        sitesByTarget: Map<String, List<CallSite>>,
    ) {
        val sourceSites = sites.filter { it.edge in destination.edges }
        // A hosted component without edges has no navigator interface to implement.
        if (destination.hosted && sourceSites.isEmpty()) return
        val cap = destination.navName.toUpperCamelCase()
        val navigatorType = if (sourceSites.isEmpty()) "CloseNavigationTarget" else "${cap}Navigator"

        write("Host${cap}Navigator") {
            buildString {
                appendLine("import com.latenighthack.basekit.navigation.awaitNavigationResult")
                appendLine("import com.latenighthack.basekit.navigation.runOnMainThread")
                appendLine()
                appendLine("// Host calls cross into platform UI (UIKit/AppKit), which requires the main thread; the")
                appendLine("// ViewModel action driving navigation may run on any dispatcher, so every call hops to main.")
                appendLine("public class Host${cap}Navigator(")
                appendLine("    public val ownerId: String,")
                appendLine("    private val host: BasekitNavigationHost,")
                appendLine(") : $navigatorType {")
                // A hosted component is not a screen, so its navigator has no close().
                if (!destination.hosted) {
                    appendLine("    override fun close(context: Any?) = runOnMainThread { host.close(ownerId, context) }")
                }

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
                                appendLine("                ${targetCap}NavigationTarget.${targetCap}Source.${site.sourceEntry} -> NavigationEdge.${site.edgeName}")
                            }
                            append("            }")
                        }
                    } else {
                        "NavigationEdge.${allTargetSites.single().edgeName}"
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
