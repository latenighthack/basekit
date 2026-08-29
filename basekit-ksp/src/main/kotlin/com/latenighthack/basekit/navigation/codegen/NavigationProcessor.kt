package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import java.io.File

private const val DESTINATION_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.Destination"
private const val ROUTE_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.Route"
private const val ROUTE_ARG_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.RouteArg"
private const val NAVIGATE_TO_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.NavigateTo"
private const val CHILD_VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ChildViewModel"
private const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
private const val NAVIGATION_DESTINATION = "com.latenighthack.basekit.navigation.NavigationDestination"
private const val RESPONDING_DESTINATION = "com.latenighthack.basekit.navigation.RespondingDestination"
private const val NAVIGATION_PACKAGE_OPTION = "Basekit_NavigationPackage"
private const val GENERATE_TEST_NAVIGATOR_OPTION = "Basekit_GenerateTestNavigator"
private const val SWIFT_FRAMEWORK_IMPORTS_OPTION = "basekit.navigation.swiftFrameworkImports"

/** A `@NavigateTo` edge from a source destination action to a target destination. */
data class NavEdge(val methodName: String, val targetQualifiedName: String)

/** Everything the generators need to know about one `@Destination`. */
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
    private var sourceFiles: List<KSFile> = emptyList()
    private var collected = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (collected) return emptyList()
        collected = true

        val symbols = resolver.getSymbolsWithAnnotation(DESTINATION_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        sourceFiles = symbols.mapNotNull { it.containingFile }
        // A list row's (or child's) @NavigateTo edges frequently live on the concrete implementation
        // rather than the spec interface named in @ViewModelList / @ChildViewModel. Index every
        // concrete class by the specs it implements so those edges can be rolled up (see
        // [embeddedViewModelTypes]).
        val implementorsBySpec = buildImplementorIndex(resolver)
        symbols.forEach { destinations.add(buildDestination(it, implementorsBySpec)) }

        return emptyList()
    }

    /** spec qualified name -> concrete classes in this compilation that implement it. */
    private fun buildImplementorIndex(resolver: Resolver): Map<String, List<KSClassDeclaration>> {
        val index = LinkedHashMap<String, MutableList<KSClassDeclaration>>()
        resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS }
            .forEach { impl ->
                impl.getAllSuperTypes().forEach { superType ->
                    val superName = superType.declaration.qualifiedName?.asString() ?: return@forEach
                    index.getOrPut(superName) { mutableListOf() }.add(impl)
                }
            }
        return index
    }

    private fun buildDestination(
        declaration: KSClassDeclaration,
        implementorsBySpec: Map<String, List<KSClassDeclaration>>,
    ): DestinationInfo {
        val simpleName = declaration.simpleName.asString()
        val qualifiedName = declaration.qualifiedName!!.asString()

        val superTypes = declaration.getAllSuperTypes().toList()

        // Reads the type argument at [index] of the [fqn] supertype as a concrete class, or null if it
        // isn't there / is still an unsubstituted type parameter.
        fun superTypeArgument(fqn: String, index: Int): KSClassDeclaration? =
            superTypes.firstOrNull { it.declaration.qualifiedName?.asString() == fqn }
                ?.arguments?.getOrNull(index)
                ?.type?.resolve()?.declaration as? KSClassDeclaration

        // Args comes from RespondingDestination<Args, R> when present (its args are concrete on the
        // declaration), else from NavigationDestination<Args>. Going through the NavigationDestination
        // supertype of a RespondingDestination would only yield the unsubstituted type parameter.
        val argsDeclaration = superTypeArgument(RESPONDING_DESTINATION, 0)
            ?: superTypeArgument(NAVIGATION_DESTINATION, 0)

        // A RespondingDestination<Args, R> declares its response type R as its second type argument.
        val responseDeclaration = superTypeArgument(RESPONDING_DESTINATION, 1)

        val routeFromArgs = argsDeclaration
            ?.stringArgument(ROUTE_ANNOTATION, "path")
        val webPath = declaration.stringArgument(DESTINATION_ANNOTATION, "webPath")
        val routePath = routeFromArgs?.takeIf { it.isNotEmpty() } ?: webPath?.takeIf { it.isNotEmpty() }

        val routeArgProperties = argsDeclaration
            ?.getDeclaredProperties()
            ?.filter { prop -> prop.annotations.any { it.qualifiedName() == ROUTE_ARG_ANNOTATION } }
            ?.toList()
            .orEmpty()

        // Route params are bound from the matched URL as raw Strings (RouteTableGenerator emits
        // `x = params.getValue("x")`), so a non-String @RouteArg would produce a type error in code the
        // consumer never wrote. Flag it here, where the property's source location is known.
        routeArgProperties
            .filter { it.type.resolve().declaration.qualifiedName?.asString() != "kotlin.String" }
            .forEach { prop ->
                logger.error(
                    "@RouteArg ${simpleName}.${prop.simpleName.asString()} must be a String " +
                        "(route params are bound from the URL as text); convert inside the ViewModel instead",
                    prop,
                )
            }

        val routeArgs = routeArgProperties.map { it.simpleName.asString() }

        val edges = collectEdges(declaration, implementorsBySpec, visited = mutableSetOf())

        val navNameOverride = declaration.stringArgument(DESTINATION_ANNOTATION, "navName")
            ?.takeIf { it.isNotEmpty() }

        return DestinationInfo(
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            navName = navNameOverride ?: simpleName.toDestinationNavName(),
            argsQualifiedName = argsDeclaration?.qualifiedName?.asString(),
            argsSwiftName = argsDeclaration?.swiftExportName(),
            responseQualifiedName = responseDeclaration?.qualifiedName?.asString(),
            responseSwiftName = responseDeclaration?.swiftExportName(),
            routePath = routePath,
            routeArgs = routeArgs,
            edges = edges,
        )
    }

    /**
     * Collects the `@NavigateTo` edges declared on [declaration] plus those on any viewmodel it
     * embeds through `@ChildViewModel` / `@ViewModelList` properties, recursively. A list row or
     * child pane may navigate on its own (it is handed its host's navigator), but only
     * `@Destination`s own a navigator — so embedded edges roll up into the hosting destination's
     * graph. [visited] guards against spec cycles.
     */
    private fun collectEdges(
        declaration: KSClassDeclaration,
        implementorsBySpec: Map<String, List<KSClassDeclaration>>,
        visited: MutableSet<String>,
    ): List<NavEdge> {
        val qualifiedName = declaration.qualifiedName?.asString() ?: return emptyList()
        if (!visited.add(qualifiedName)) return emptyList()

        val own = declaration.getDeclaredFunctions().flatMap { function ->
            val methodName = function.simpleName.asString()
            function.annotations
                .filter { it.qualifiedName() == NAVIGATE_TO_ANNOTATION }
                .mapNotNull { annotation ->
                    val target = annotation.arguments
                        .firstOrNull { it.name?.asString() == "target" }?.value as? KSType
                    target?.declaration?.qualifiedName?.asString()?.let { NavEdge(methodName, it) }
                }
        }.toList()

        val embedded = declaration.getDeclaredProperties()
            .flatMap { embeddedViewModelTypes(it, implementorsBySpec) }
            .flatMap { collectEdges(it, implementorsBySpec, visited) }
            .toList()

        return (own + embedded).distinct()
    }

    /**
     * The viewmodel types a `@ChildViewModel` / `@ViewModelList` property embeds in its host: the spec
     * interface(s) named on the annotation plus every concrete implementation of them found in this
     * compilation. A list row typically declares its `@NavigateTo` edge on the implementation
     * (e.g. `ContactsActionItemViewModelImpl`) rather than on the shared row spec, so the
     * implementations must be traversed too for those edges to roll up into the host navigator.
     */
    private fun embeddedViewModelTypes(
        property: KSPropertyDeclaration,
        implementorsBySpec: Map<String, List<KSClassDeclaration>>,
    ): List<KSClassDeclaration> {
        val isChild = property.annotations.any { it.qualifiedName() == CHILD_VIEWMODEL_ANNOTATION }
        val listAnnotation = property.annotations.firstOrNull { it.qualifiedName() == VIEWMODEL_LIST_ANNOTATION }
        val declared = when {
            isChild -> listOfNotNull(property.type.resolve().declaration as? KSClassDeclaration)
            listAnnotation != null -> {
                val possibleTypes = (
                    listAnnotation.arguments
                        .firstOrNull { it.name?.asString() == "possibleTypes" }?.value as? List<*>
                    ).orEmpty()
                    .mapNotNull { (it as? KSType)?.declaration as? KSClassDeclaration }
                // The property is Flow<Delta<X>>; X is the element spec for lists declared without
                // explicit possibleTypes.
                val element = property.type.resolve()
                    .arguments.firstOrNull()?.type?.resolve()
                    ?.arguments?.firstOrNull()?.type?.resolve()
                    ?.declaration as? KSClassDeclaration
                possibleTypes + listOfNotNull(element)
            }
            else -> return emptyList()
        }.distinctBy { it.qualifiedName?.asString() }

        val implementors = declared.flatMap { spec ->
            implementorsBySpec[spec.qualifiedName?.asString()].orEmpty()
        }
        return (declared + implementors).distinctBy { it.qualifiedName?.asString() }
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
            AppleSwiftNavigationGenerator(codeGenerator, dependencies, imports).generate(destinations)
            return
        }
        if (!marker.isMetadataPass()) return
        if (destinations.isEmpty()) return

        // Every generated navigator/target type is named from the destination's navName, and they all
        // land in one flattened package. Two destinations that derive the same navName would emit the
        // same file twice — a FileAlreadyExistsException with no hint at the cause. Fail with a clear
        // message (and point at @Destination(navName = ...)) instead.
        val collisions = destinations.groupBy { it.navName }.filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            collisions.forEach { (navName, group) ->
                logger.error(
                    "Destinations ${group.map { it.qualifiedName }.sorted()} all derive the navigation " +
                        "name \"$navName\"; disambiguate with @Destination(navName = \"...\") on all but one",
                )
            }
            return
        }

        val navigationPackage = resolveNavigationPackage()
        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())

        NavigatorInterfaceGenerator(codeGenerator, logger, dependencies, navigationPackage).generate(destinations)
        NavigationScreenGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations)
        ObservingNavigatorGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations)
        RouteTableGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations)
        AppleHostedNavigatorGenerator(codeGenerator, dependencies, navigationPackage).generate(destinations)

        if (options[GENERATE_TEST_NAVIGATOR_OPTION]?.toBoolean() == true) {
            TestNavigatorGenerator(codeGenerator, logger, dependencies, navigationPackage).generate(destinations)
        }
    }

    private fun resolveNavigationPackage(): String {
        options[NAVIGATION_PACKAGE_OPTION]?.takeIf { it.isNotEmpty() }?.let { return it }
        // Fallback: the longest common package prefix of all destinations.
        val packages = destinations.map { it.qualifiedName.substringBeforeLast('.').split('.') }
        if (packages.isEmpty()) return "navigation"
        var prefix = packages.first()
        for (parts in packages.drop(1)) {
            prefix = prefix.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        return prefix.joinToString(".").ifEmpty { "navigation" }
    }

    private companion object {
        const val MARKER_PACKAGE = "com.latenighthack.basekit.navigation.gen"

        fun File.isMetadataPass(): Boolean {
            val parts = invariantSeparatorsPath.split('/')
            val kspIndex = parts.indexOf("ksp")
            return kspIndex >= 0 && parts.getOrNull(kspIndex + 1) == "metadata"
        }

        fun File.isApplePass(): Boolean {
            val path = invariantSeparatorsPath.lowercase()
            val afterKsp = path.substringAfter("/ksp/", "")
            return listOf("ios", "macos", "tvos", "watchos").any { afterKsp.contains(it) }
        }
    }
}
