package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import java.io.File

internal const val DESTINATION_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.Destination"
internal const val ROUTE_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.Route"
internal const val ROUTE_ARG_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.RouteArg"
internal const val NAVIGATE_TO_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.NavigateTo"
internal const val HOSTED_COMPONENT_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.HostedComponent"
internal const val REDIRECTS_TO_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.RedirectsTo"

/** The method name of a `@RedirectsTo` edge, so its source entry is `<SCREEN>_REDIRECT`. */
const val REDIRECT_METHOD = "redirect"
internal const val CHILD_VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
internal const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
internal const val VIEWMODEL_SPEC_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
internal const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
internal const val FLOW = "kotlinx.coroutines.flow.Flow"
internal const val DELTA = "com.latenighthack.deltalist.Delta"
internal const val SECTIONED_DELTA = "com.latenighthack.deltalist.SectionedDelta"
internal const val DELTA_LIST = "com.latenighthack.deltalist.DeltaList"
internal const val SECTIONED_DELTA_LIST = "com.latenighthack.deltalist.SectionedDeltaList"
internal const val NAVIGATION_DESTINATION = "com.latenighthack.basekit.navigation.NavigationDestination"
internal const val RESPONDING_DESTINATION = "com.latenighthack.basekit.navigation.RespondingDestination"
internal const val VIEWMODEL_ACTION_OBSERVER = "com.latenighthack.basekit.viewmodel.ViewModelActionObserver"
private const val NAVIGATION_PACKAGE_OPTION = "Basekit_NavigationPackage"
private const val GENERATE_TEST_NAVIGATOR_OPTION = "Basekit_GenerateTestNavigator"
private const val SWIFT_FRAMEWORK_IMPORTS_OPTION = "basekit.navigation.swiftFrameworkImports"

/**
 * A `@NavigateTo` edge from an action to a target destination. [sourceNavName] names the spec that
 * declares the action: the destination itself, or a list row / child spec it embeds. It forms the
 * `<Target>Source` entry, so two rows that each declare `onItemTapped` stay distinct call sites.
 *
 * A `@RedirectsTo` target is an edge too, with [redirect] set and [methodName] [REDIRECT_METHOD]: the
 * destination replaces itself without a user action.
 */
data class NavEdge(
    val sourceNavName: String,
    val methodName: String,
    val targetQualifiedName: String,
    val redirect: Boolean = false,
)

/**
 * Everything the generators need to know about one `@Destination`, or about a `@HostedComponent` when
 * [hosted] is set. A hosted component only has [edges]: it is never a navigation target, has no screen,
 * and its navigator has no `close()`.
 */
data class DestinationInfo(
    val simpleName: String,
    val qualifiedName: String,
    val navName: String,
    val argsQualifiedName: String?,
    val argsSwiftName: String?,
    val responseQualifiedName: String?,
    val responseSwiftName: String?,
    val routePath: String?,
    val routeArgs: List<String>,
    val edges: List<NavEdge>,
    val hosted: Boolean = false,
    /** `@Route(aliases)`: other paths that open the destination, with the same params as [routePath]. */
    val routeAliases: List<String> = emptyList(),
    /**
     * The value a route gives each argument it does not capture, as Kotlin source: `false` for a
     * `Boolean`, `null` for a nullable type. A route sets these so no platform reads an unset argument.
     */
    val routeDefaults: List<Pair<String, String>> = emptyList(),
)

/**
 * Collects `@Destination` declarations into a navigation graph and, in the common metadata pass,
 * emits the navigator/route interfaces. See [NavigatorInterfaceGenerator] and [RouteTableGenerator].
 */
class NavigationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private val destinations = mutableListOf<DestinationInfo>()
    private val hostedComponents = mutableListOf<DestinationInfo>()
    private var sourceFiles: List<KSFile> = emptyList()
    private var collected = false
    /** Whether basekit-viewmodel is on the classpath, so the generated routers can carry an action observer. */
    private var actionObservers = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (collected) return emptyList()
        collected = true
        actionObservers = resolver.getClassDeclarationByName(resolver.getKSNameFromString(VIEWMODEL_ACTION_OBSERVER)) != null

        val graph = NavigationExtractor(logger).extract(resolver)
        sourceFiles = graph.sourceFiles
        destinations.addAll(graph.destinations)
        hostedComponents.addAll(graph.hostedComponents)

        return emptyList()
    }

    override fun finish() {
        // Emit a marker first so we can read its output path and tell the common metadata pass
        // (build/generated/ksp/metadata/commonMain/...) from the per-platform passes. The navigator
        // and route interfaces are pure common types, so they are generated only in the metadata pass.
        codeGenerator.createNewFile(Dependencies(false), MARKER_PACKAGE, "basekit_nav_marker", "log").close()
        val marker = codeGenerator.generatedFile.firstOrNull() ?: return
        if (marker.isApplePass()) {
            if (destinations.isEmpty()) return
            val imports = options[SWIFT_FRAMEWORK_IMPORTS_OPTION]
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: options["basekit.viewmodel.swiftFrameworkImports"]
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    .orEmpty()
            val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
            AppleSwiftNavigationGenerator(codeGenerator, dependencies, imports, actionObservers).generate(destinations, hostedComponents)
            if (options["basekit.navigation.posthog"] == "true") {
                ApplePostHogBridgeGenerator(codeGenerator, dependencies, imports).generate()
            }
            return
        }
        if (marker.isAndroidPass()) {
            // The Compose counterpart of the Apple pass's Swift, for apps that host Compose bindings.
            if (destinations.isEmpty() || options[COMPOSE_OPTION] != "true") return
            val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
            ComposeNavigationGenerator(codeGenerator, dependencies, resolveNavigationPackage(), actionObservers).generate(destinations, hostedComponents)
            return
        }
        if (!marker.isMetadataPass() && marker.isJsPass()) {
            // The web counterpart of the Compose router, for apps that host React bindings.
            if (destinations.isEmpty() || options[REACT_OPTION] != "true") return
            val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
            ReactNavigationGenerator(codeGenerator, dependencies, resolveNavigationPackage(), actionObservers).generate(destinations, hostedComponents)
            return
        }
        if (!marker.isMetadataPass()) return
        if (destinations.isEmpty()) return

        // Every generated navigator/target type is named from the destination's navName, and they all
        // land in one flattened package. Two destinations that derive the same navName would emit the
        // same file twice — a FileAlreadyExistsException with no hint at the cause. Fail with a clear
        // message (and point at @Destination(navName = ...)) instead.
        val collisions = (destinations + hostedComponents).groupBy { it.navName }.filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            collisions.forEach { (navName, group) ->
                logger.error(
                    "Destinations ${group.map { it.qualifiedName }.sorted()} all derive the navigation " +
                        "name \"$navName\"; disambiguate with @Destination(navName = \"...\") or " +
                        "@HostedComponent(navName = \"...\") on all but one",
                )
            }
            return
        }

        val navigationPackage = resolveNavigationPackage()
        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())

        NavigatorInterfaceGenerator(codeGenerator, logger, dependencies, navigationPackage).generate(destinations, hostedComponents)
        NavigationScreenGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations)
        ObservingNavigatorGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations, hostedComponents)
        RouteTableGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations)
        AppleHostedNavigatorGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations, hostedComponents)

        if (options[GENERATE_TEST_NAVIGATOR_OPTION]?.toBoolean() == true) {
            TestNavigatorGenerator(codeGenerator, logger, dependencies, navigationPackage).generate(destinations, hostedComponents)
        }
    }

    private fun resolveNavigationPackage(): String = navigationPackage(options[NAVIGATION_PACKAGE_OPTION], destinations)

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.navigation.gen"

        fun File.isMetadataPass(): Boolean {
            val parts = invariantSeparatorsPath.split('/')
            val kspIndex = parts.indexOf("ksp")
            return kspIndex >= 0 && parts.getOrNull(kspIndex + 1) == "metadata"
        }

        const val COMPOSE_OPTION = "basekit.viewmodel.compose"

        fun File.isAndroidPass(): Boolean {
            val parts = invariantSeparatorsPath.split('/')
            val kspIndex = parts.indexOf("ksp")
            return kspIndex >= 0 && parts.getOrNull(kspIndex + 1) == "android"
        }

        const val REACT_OPTION = "basekit.viewmodel.react"

        /** The same test as the viewmodel processor's JS pass. */
        fun File.isJsPass(): Boolean {
            val afterKsp = invariantSeparatorsPath.lowercase().substringAfter("/ksp/", "")
            return afterKsp.startsWith("js/") || afterKsp.contains("/js") || afterKsp.contains("jsmain")
        }

        fun File.isApplePass(): Boolean {
            val path = invariantSeparatorsPath.lowercase()
            val afterKsp = path.substringAfter("/ksp/", "")
            return listOf("ios", "macos", "tvos", "watchos").any { afterKsp.contains(it) }
        }
    }
}
