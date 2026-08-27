package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits one `Observing<Cap>Navigator` decorator per `@Destination`: a class implementing the exact
 * navigator interface the destination is injected with, holding a `delegate` of that same interface
 * plus a [com.latenighthack.basekit.navigation.NavigationObserver]. Every `navigateTo…`/`close` fires a
 * `NavigationEvent` (carrying the target's `NavigationScreen`) and then forwards to `delegate`.
 *
 *  - A destination with outbound edges implements its `<Cap>Navigator` and overrides every reachable
 *    `navigateTo…` plus `close`.
 *  - A leaf destination (no outbound edges) implements `CloseNavigationTarget` and overrides `close`.
 *
 * Because the decorator sits on the common navigator interface — above the Apple/TUI/test
 * implementations — the observer sees every ViewModel-initiated navigation regardless of what the
 * concrete navigator underneath does. The graph is walked exactly as in [NavigatorInterfaceGenerator]
 * so the emitted overrides match the emitted interfaces. Metadata (commonMain) pass only.
 */
class ObservingNavigatorGenerator(
    private val codeGenerator: CodeGenerator,
    private val dependencies: Dependencies,
    private val navPackage: String,
) {
    private data class CallSite(val sourceNavName: String, val methodName: String)

    fun generate(destinations: List<DestinationInfo>) {
        val byQualifiedName = destinations.associateBy { it.qualifiedName }

        // target qualified name -> ordered, unique call sites navigating to it (size > 1 => has a Source enum)
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

        // No edges => NavigatorInterfaceGenerator emits no navigators, so there is nothing to decorate.
        if (targetCallSites.isEmpty()) return

        for (destination in destinations) {
            val targets = outboundTargets[destination.qualifiedName]
            if (targets == null) {
                writeLeaf(destination)
            } else {
                writeNavigator(destination, targets, byQualifiedName, targetCallSites)
            }
        }
    }

    /** A leaf destination is injected with the shared `CloseNavigationTarget`; only `close` to observe. */
    private fun writeLeaf(destination: DestinationInfo) {
        val cap = destination.navName.toUpperCamelCase()
        writeFile("Observing${cap}Navigator") {
            """
            |public class Observing${cap}Navigator(
            |    private val delegate: CloseNavigationTarget,
            |    private val observer: NavigationObserver,
            |) : CloseNavigationTarget {
            |    override fun close(context: Any?) {
            |        observer.onNavigation(NavigationEvent.Closed(NavigationScreen.${destination.screenName}, context))
            |        delegate.close(context)
            |    }
            |}
            """.trimMargin()
        }
    }

    private fun writeNavigator(
        source: DestinationInfo,
        targetQualifiedNames: Set<String>,
        byQualifiedName: Map<String, DestinationInfo>,
        targetCallSites: Map<String, List<CallSite>>,
    ) {
        val sourceCap = source.navName.toUpperCamelCase()

        val overrides = targetQualifiedNames.mapNotNull { targetQualifiedName ->
            val target = byQualifiedName[targetQualifiedName] ?: return@mapNotNull null
            val cap = target.navName.toUpperCamelCase()
            val callSites = targetCallSites.getValue(targetQualifiedName)
            val hasSource = callSites.size > 1
            val method = "navigateTo$cap"

            val params = buildList {
                target.argsQualifiedName?.let { add("args: $it") }
                if (hasSource) add("source: ${cap}NavigationTarget.${cap}Source")
                add("context: Any?")
            }.joinToString(", ")

            val argsExpr = if (target.argsQualifiedName != null) "args" else "null"
            val sourceExpr = if (hasSource) "source" else "null"
            val delegateArgs = buildList {
                if (target.argsQualifiedName != null) add("args")
                if (hasSource) add("source")
                add("context")
            }.joinToString(", ")

            val navigatedTo =
                "NavigationEvent.NavigatedTo(NavigationScreen.${target.screenName}, " +
                    "${target.qualifiedName}::class, $argsExpr, $sourceExpr, context)"

            if (target.responseQualifiedName != null) {
                // Responding: wrap the suspend call so both the navigation and its result are observed.
                """
                |    override suspend fun $method($params): ${target.responseQualifiedName}? {
                |        observer.onNavigation($navigatedTo)
                |        val result = delegate.$method($delegateArgs)
                |        observer.onNavigation(NavigationEvent.Responded(NavigationScreen.${target.screenName}, result))
                |        return result
                |    }
                """.trimMargin()
            } else {
                """
                |    override fun $method($params) {
                |        observer.onNavigation($navigatedTo)
                |        delegate.$method($delegateArgs)
                |    }
                """.trimMargin()
            }
        }.joinToString("\n\n")

        writeFile("Observing${sourceCap}Navigator") {
            """
            |public class Observing${sourceCap}Navigator(
            |    private val delegate: ${sourceCap}Navigator,
            |    private val observer: NavigationObserver,
            |) : ${sourceCap}Navigator {
            |    override fun close(context: Any?) {
            |        observer.onNavigation(NavigationEvent.Closed(NavigationScreen.${source.screenName}, context))
            |        delegate.close(context)
            |    }
            |
            |$overrides
            |}
            """.trimMargin()
        }
    }

    private inline fun writeFile(fileName: String, body: () -> String) {
        codeGenerator.createNewFile(dependencies, navPackage, fileName, "kt").use { out ->
            out.writeln("package $navPackage")
            out.writeln()
            out.writeln("import com.latenighthack.basekit.navigation.NavigationEvent")
            out.writeln("import com.latenighthack.basekit.navigation.NavigationObserver")
            out.writeln()
            out.writeln(body())
        }
    }
}
