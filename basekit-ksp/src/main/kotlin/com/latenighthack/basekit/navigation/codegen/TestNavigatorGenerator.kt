package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger

private const val NAVIGATION_RESPONDER = "com.latenighthack.basekit.navigation.NavigationResponder"

/**
 * Emits the ViewModel test harness into `<navPackage>.test`, gated on `Basekit_GenerateTestNavigator=true`:
 *
 *  - `TestViewModelRegistry` — a DI-agnostic factory interface with one `create<Destination>` method per
 *    `@Destination`. Adding a destination adds an abstract method, so a consumer's registry impl fails to
 *    compile until it wires the new screen — compiler-enforced completeness (the fix for the rotting
 *    hand-written when-table).
 *  - `TestClientNavigator` — an `open` class implementing every generated navigator interface plus
 *    [com.latenighthack.basekit.navigation.test.TestNavigatorApi]. Every `navigateTo…`/`close`/`launch`
 *    records a `NavigationEvent`; `createDestination` dispatches to the registry. Both when-tables are
 *    generated and exhaustive, so consumers never touch them.
 *
 * The graph (which destination navigates to which, and which targets need a `<X>Source` enum) is computed
 * exactly as in [NavigatorInterfaceGenerator], so the emitted overrides match the emitted interfaces.
 */
class TestNavigatorGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val dependencies: Dependencies,
    private val navPackage: String,
) {
    private data class CallSite(val sourceNavName: String, val methodName: String)

    private val testPackage = "$navPackage.test"

    fun generate(destinations: List<DestinationInfo>) {
        val byQualifiedName = destinations.associateBy { it.qualifiedName }

        // target qualified name -> ordered, unique call sites navigating to it (size > 1 => has a Source enum)
        val targetCallSites = LinkedHashMap<String, MutableList<CallSite>>()
        // source qualified name -> whether it has any outbound edge (=> a <Src>Navigator interface exists)
        val hasOutbound = LinkedHashSet<String>()

        for (destination in destinations) {
            for (edge in destination.edges) {
                val sites = targetCallSites.getOrPut(edge.targetQualifiedName) { mutableListOf() }
                val site = CallSite(destination.navName, edge.methodName)
                if (site !in sites) sites.add(site)
                hasOutbound.add(destination.qualifiedName)
            }
        }

        // No edges => NavigatorInterfaceGenerator emits nothing (no CloseNavigationTarget / navigators), so
        // there is no graph to drive a harness. Skip in lock-step with it.
        if (targetCallSites.isEmpty()) return

        generateRegistry(destinations, hasOutbound)
        generateNavigator(destinations, byQualifiedName, targetCallSites, hasOutbound)
    }

    /** The navigator param type a destination's create/factory takes: its own `<Src>Navigator`, else the shared close target. */
    private fun DestinationInfo.navigatorParamType(hasOutbound: Set<String>): String =
        if (qualifiedName in hasOutbound) "$navPackage.${navName.toUpperCamelCase()}Navigator"
        else "$navPackage.CloseNavigationTarget"

    private fun generateRegistry(destinations: List<DestinationInfo>, hasOutbound: Set<String>) {
        val methods = destinations.joinToString("\n\n") { destination ->
            val params = buildList {
                destination.argsQualifiedName?.let { add("args: $it") }
                add("navigator: ${destination.navigatorParamType(hasOutbound)}")
                // A responding destination's ViewModel is handed the channel it responds through.
                destination.responseQualifiedName?.let { add("responder: $NAVIGATION_RESPONDER<$it>") }
            }.joinToString(", ")
            "    fun create${destination.simpleName}($params): ${destination.qualifiedName}"
        }

        writeFile("TestViewModelRegistry") {
            """
            |package $testPackage
            |
            |/**
            | * DI-agnostic factory the harness delegates to. A consumer implements each method by calling its
            | * own ViewModel constructor / DI factory. Adding a @Destination adds a method here, so an
            | * incomplete registry fails to compile.
            | */
            |interface TestViewModelRegistry {
            |$methods
            |}
            """.trimMargin()
        }
    }

    private fun generateNavigator(
        destinations: List<DestinationInfo>,
        byQualifiedName: Map<String, DestinationInfo>,
        targetCallSites: Map<String, List<CallSite>>,
        hasOutbound: Set<String>,
    ) {
        // Supertypes: every <Src>Navigator + every <X>NavigationTarget + CloseNavigationTarget + TestNavigatorApi.
        // Listing the targets and close explicitly (not just via the navigators) guarantees the registry's
        // typed navigator params are satisfied by `this`.
        val supertypes = buildList {
            add("TestNavigatorApi")
            for (destination in destinations) {
                if (destination.qualifiedName in hasOutbound) {
                    add("$navPackage.${destination.navName.toUpperCamelCase()}Navigator")
                }
            }
            for (targetQualifiedName in targetCallSites.keys) {
                byQualifiedName[targetQualifiedName]?.let {
                    add("$navPackage.${it.navName.toUpperCamelCase()}NavigationTarget")
                }
            }
            add("$navPackage.CloseNavigationTarget")
        }.joinToString(",\n    ")

        val navigateOverrides = targetCallSites.entries.mapNotNull { (targetQualifiedName, callSites) ->
            val target = byQualifiedName[targetQualifiedName] ?: return@mapNotNull null
            val cap = target.navName.toUpperCamelCase()
            val hasSource = callSites.size > 1

            val params = buildList {
                target.argsQualifiedName?.let { add("args: $it") }
                if (hasSource) add("source: $navPackage.${cap}NavigationTarget.${cap}Source")
                add("context: Any?")
            }.joinToString(", ")

            val argsExpr = if (target.argsQualifiedName != null) "args" else "null"
            val sourceExpr = if (hasSource) "source" else "null"
            val response = target.responseQualifiedName

            if (response != null) {
                // Responding: suspend the caller until the destination (or close) responds, returning R?.
                """
                |    override suspend fun navigateTo$cap($params): $response? =
                |        recorder.recordAndAwaitResponse<$response>(${target.qualifiedName}::class, $argsExpr, $sourceExpr, context)
                """.trimMargin()
            } else {
                """
                |    override fun navigateTo$cap($params) {
                |        recorder.record(NavigationEvent.NavigatedTo(${target.qualifiedName}::class, $argsExpr, $sourceExpr, context))
                |    }
                """.trimMargin()
            }
        }.joinToString("\n\n")

        val createBranches = destinations.joinToString("\n") { destination ->
            val args = if (destination.argsQualifiedName != null) "args as ${destination.argsQualifiedName}, " else ""
            val response = destination.responseQualifiedName
            if (response != null) {
                val responderArg =
                    "(responder ?: error(\"${destination.simpleName} is a RespondingDestination; navigate to it so a responder is attached\")) as $NAVIGATION_RESPONDER<$response>"
                "            ${destination.qualifiedName}::class -> registry.create${destination.simpleName}(${args}this, $responderArg) as T"
            } else {
                "            ${destination.qualifiedName}::class -> registry.create${destination.simpleName}(${args}this) as T"
            }
        }

        val destinationOfBranches = destinations
            .filter { it.argsQualifiedName != null }
            .joinToString("\n") { destination ->
                "            is ${destination.argsQualifiedName} -> ${destination.qualifiedName}::class"
            }

        writeFile("TestClientNavigator") {
            """
            |package $testPackage
            |
            |import com.latenighthack.basekit.navigation.NavigationResponder
            |import com.latenighthack.basekit.navigation.NavigatorArgs
            |import com.latenighthack.basekit.navigation.test.NavigationEvent
            |import com.latenighthack.basekit.navigation.test.NavigationRecorder
            |import com.latenighthack.basekit.navigation.test.TestNavigatorApi
            |import com.latenighthack.basekit.navigation.test.recordAndAwaitResponse
            |import kotlin.reflect.KClass
            |import kotlinx.coroutines.flow.Flow
            |
            |/**
            | * Generated test navigator. Records every navigation and delegates destination creation to
            | * [registry]. `open` so consumers can subclass it (e.g. to add convenience accessors).
            | */
            |open class TestClientNavigator(
            |    private val registry: TestViewModelRegistry,
            |) : $supertypes {
            |    protected val recorder: NavigationRecorder = NavigationRecorder()
            |
            |    override val navigationHistory: Flow<List<NavigationEvent>> get() = recorder.history
            |
            |$navigateOverrides
            |
            |    override fun close(context: Any?) {
            |        // Closing a still-pending responding destination dismisses it: the caller resumes with null.
            |        // Keys off the pending-responder stack, not the last event, so it still resolves a picker
            |        // that was navigated away from before the close.
            |        recorder.dismissTopPending()
            |        recorder.record(NavigationEvent.Closed(context))
            |    }
            |
            |    override fun launch(args: NavigatorArgs, context: Any?) {
            |        recorder.record(NavigationEvent.NavigatedTo(destinationOf(args), args, null, context))
            |    }
            |
            |    @Suppress("UNCHECKED_CAST")
            |    override fun <T : Any> createDestination(destination: KClass<T>, args: NavigatorArgs?, responder: NavigationResponder<*>?): T =
            |        when (destination) {
            |$createBranches
            |            else -> error("no TestViewModelRegistry factory for destination ${'$'}destination")
            |        }
            |
            |    private fun destinationOf(args: NavigatorArgs): KClass<*> =
            |        when (args) {
            |$destinationOfBranches
            |            else -> error("no destination for args ${'$'}args")
            |        }
            |}
            """.trimMargin()
        }
    }

    private inline fun writeFile(fileName: String, body: () -> String) {
        codeGenerator.createNewFile(dependencies, testPackage, fileName, "kt").apply {
            write(body().encodeToByteArray())
            write("\n".encodeToByteArray())
        }.close()
    }
}
