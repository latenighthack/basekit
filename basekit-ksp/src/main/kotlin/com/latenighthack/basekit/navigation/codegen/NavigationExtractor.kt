package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * The `@Destination`s and `@HostedComponent`s in one compilation and the files that declare them. A
 * hosted component is a navigation source only: it has edges and a navigator, but no args, route or
 * screen, and nothing navigates to it.
 */
data class NavigationGraph(
    val destinations: List<DestinationInfo>,
    val sourceFiles: List<KSFile>,
    val hostedComponents: List<DestinationInfo> = emptyList(),
) {
    /** Everything that owns a navigator: destinations, then hosted components. */
    val sources: List<DestinationInfo> get() = destinations + hostedComponents
}

/**
 * Reads every `@Destination` in a compilation into [DestinationInfo]s, including the `@NavigateTo`
 * edges of the rows and child panes each destination embeds, and reports invalid declarations to
 * [logger]. [NavigationProcessor] and the catalog processor both read the graph through this class.
 */
class NavigationExtractor(private val logger: KSPLogger) {

    fun extract(resolver: Resolver): NavigationGraph {
        // KSP gives symbols in the order it reads their files, which is the file system's directory
        // order and so differs between machines. Every generated file follows the order here, so sort
        // by name: two builds of the same sources write the same code.
        val symbols = resolver.getSymbolsWithAnnotation(DESTINATION_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .sortedBy { it.qualifiedName?.asString() }
            .toList()

        // A list row's (or child's) @NavigateTo edges live on the row spec, which often extends a
        // marker interface the list is typed with, and sometimes on the concrete implementation.
        // Index every viewmodel type by its supertypes so those edges can be rolled up (see
        // [embeddedViewModelTypes]).
        val subtypesBySupertype = buildSubtypeIndex(resolver)
        val reached = mutableSetOf<String>()
        val destinations = symbols.map { buildDestination(it, subtypesBySupertype, reached) }
        val hostedSymbols = resolver.getSymbolsWithAnnotation(HOSTED_COMPONENT_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .filter { declaration ->
                val isDestination = declaration.annotations.any { it.qualifiedName() == DESTINATION_ANNOTATION }
                if (isDestination) {
                    logger.error(
                        "${declaration.simpleName.asString()} is both a @Destination and a @HostedComponent; " +
                            "a destination already owns a navigator",
                        declaration,
                    )
                }
                !isDestination
            }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()
        reportIndistinctRoutes(symbols.zip(destinations))
        val hostedComponents = hostedSymbols.map { buildHostedComponent(it, subtypesBySupertype, reached) }
        warnUnreachableEdges(resolver, reached)
        resolver.getSymbolsWithAnnotation(REDIRECTS_TO_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .filter { declaration -> declaration.annotations.none { it.qualifiedName() == DESTINATION_ANNOTATION } }
            .forEach { declaration ->
                logger.warn(
                    "@RedirectsTo on ${declaration.simpleName.asString()} has no effect: only a @Destination redirects",
                    declaration,
                )
            }

        return NavigationGraph(
            destinations,
            (symbols + hostedSymbols).mapNotNull { it.containingFile },
            hostedComponents,
        )
    }

    /**
     * Reports `@Route` templates that can match one address with neither more specific, which
     * `RouteTable` could only choose between arbitrarily:
     * - two of the same shape (see [routeShape]), such as `/agents/{agentId}` and `/agents/{id}`;
     * - two whose paths cross (see [routesCross]), such as `/teams/new/{section}` and
     *   `/teams/{teamId}/settings`.
     *
     * A route that is literal wherever the other is, and in more places, is fine: `/agents/add` opens
     * over `/agents/{agentId}`. So are routes on the same path capturing different query keys; a URL
     * carrying both opens the one `GeneratedRoutes` registers first, by destination name.
     */
    private fun reportIndistinctRoutes(destinations: List<Pair<KSClassDeclaration, DestinationInfo>>) {
        val templates = destinations.flatMap { (declaration, info) ->
            (listOfNotNull(info.routePath) + info.routeAliases).map { it to declaration }
        }
        templates.forEachIndexed { index, (first, firstDeclaration) ->
            templates.drop(index + 1).filter { (second, _) -> routesCross(first, second) }.forEach { (second, secondDeclaration) ->
                val pair = "\"$first\" on ${firstDeclaration.simpleName.asString()} and \"$second\" on ${secondDeclaration.simpleName.asString()}"
                listOf(firstDeclaration, secondDeclaration).distinct().forEach { declaration ->
                    logger.error(
                        "@Route templates $pair cross: an address can match both and each has a literal where the " +
                            "other has a parameter; make one literal wherever the other is",
                        declaration,
                    )
                }
            }
        }
        templates
            .map { (template, declaration) -> Triple(routeShape(template), template, declaration) }
            .groupBy { it.first }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val templates = group.joinToString { (_, template, declaration) -> "\"$template\" on ${declaration.simpleName.asString()}" }
                group.forEach { (_, _, declaration) ->
                    logger.error(
                        "@Route templates $templates match the same addresses and neither is more specific; " +
                            "give one a literal segment or query key the other lacks",
                        declaration,
                    )
                }
            }
    }

    /** supertype qualified name -> viewmodel interfaces and classes in this compilation that extend it. */
    private fun buildSubtypeIndex(resolver: Resolver): Map<String, List<KSClassDeclaration>> {
        val index = LinkedHashMap<String, MutableList<KSClassDeclaration>>()
        resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { it.withNestedClasses() }
            .filter { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.INTERFACE }
            .forEach { type ->
                val superTypes = type.getAllSuperTypes()
                    .mapNotNull { it.declaration.qualifiedName?.asString() }
                    .toList()
                if (VIEWMODEL_INTERFACE !in superTypes) return@forEach
                superTypes.forEach { index.getOrPut(it) { mutableListOf() }.add(type) }
            }
        // By name, not file order, as for the destinations themselves.
        index.values.forEach { types -> types.sortBy { it.qualifiedName?.asString() } }
        return index
    }

    private fun KSClassDeclaration.withNestedClasses(): Sequence<KSClassDeclaration> =
        sequenceOf(this) + declarations.filterIsInstance<KSClassDeclaration>().flatMap { it.withNestedClasses() }

    /**
     * A `@NavigateTo` on a type no destination embeds produces no navigator method, and the action's
     * implementation then fails to compile against a navigator without that target. Point at the
     * annotation instead.
     */
    private fun warnUnreachableEdges(resolver: Resolver, reached: Set<String>) {
        resolver.getSymbolsWithAnnotation(NAVIGATE_TO_ANNOTATION)
            .filterIsInstance<KSFunctionDeclaration>()
            .forEach { function ->
                val owner = function.parentDeclaration as? KSClassDeclaration ?: return@forEach
                if (owner.qualifiedName?.asString() in reached) return@forEach
                logger.warn(
                    "@NavigateTo on ${owner.simpleName.asString()}.${function.simpleName.asString()} is not " +
                        "reachable from any @Destination or @HostedComponent; declare it on one of those or on a " +
                        "row/child spec one exposes through @ChildViewModel, @ViewModelList, DeltaList or SectionedDeltaList",
                    function,
                )
            }
    }

    private fun buildDestination(
        declaration: KSClassDeclaration,
        subtypesBySupertype: Map<String, List<KSClassDeclaration>>,
        reached: MutableSet<String>,
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
        val routeAliases = argsDeclaration?.stringListArgument(ROUTE_ANNOTATION, "aliases").orEmpty()
            .filter { it.isNotEmpty() }
        if (routeAliases.isNotEmpty() && routePath == null) {
            logger.error("@Route aliases on $simpleName need a route path", argsDeclaration)
        }
        (listOfNotNull(routePath) + routeAliases).forEach { template ->
            routeTemplateProblems(template).forEach { problem ->
                logger.error("@Route \"$template\" on $simpleName: $problem", argsDeclaration ?: declaration)
            }
        }
        // An alias registers the same args factory, which binds every @RouteArg from the match.
        routePath?.let(::routeParams)?.let { params ->
            routeAliases.filter { routeParams(it) != params }.forEach { alias ->
                logger.error(
                    "@Route alias \"$alias\" on $simpleName must capture the same parameters as \"$routePath\" " +
                        "(${params.sorted().joinToString().ifEmpty { "none" }})",
                    argsDeclaration,
                )
            }
        }

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
        // The generated factory reads every @RouteArg from the match, so each must be captured.
        routePath?.let(::routeParams)?.let { captured ->
            routeArgProperties.filter { it.simpleName.asString() !in captured }.forEach { prop ->
                logger.error(
                    "@RouteArg ${simpleName}.${prop.simpleName.asString()} is not captured by \"$routePath\"; " +
                        "add {${prop.simpleName.asString()}} to its path or query (key={${prop.simpleName.asString()}})",
                    prop,
                )
            }
        }

        // A route sets every argument: a capture, or the default of an argument it does not capture.
        // Without one the destination would read an unset argument, which each platform treats
        // differently, so an argument with no default must be captured.
        val routeDefaults = if (routePath == null) emptyList() else argsDeclaration
            ?.getAllProperties()
            ?.filter { it.isDelegated() && it.isMutable && it.simpleName.asString() !in routeArgs }
            ?.mapNotNull { prop ->
                val name = prop.simpleName.asString()
                val type = prop.type.resolve()
                when {
                    type.isMarkedNullable -> name to "null"
                    type.declaration.qualifiedName?.asString() == "kotlin.Boolean" -> name to "false"
                    else -> {
                        logger.error(
                            "${simpleName}.Args.$name is not captured by \"$routePath\" and has no default; " +
                                "capture it with @RouteArg, or make it nullable (null) or a Boolean (false)",
                            prop,
                        )
                        null
                    }
                }
            }
            ?.toList()
            .orEmpty()

        val navNameOverride = declaration.stringArgument(DESTINATION_ANNOTATION, "navName")
            ?.takeIf { it.isNotEmpty() }
        val navName = navNameOverride ?: simpleName.toDestinationNavName()

        val visited = mutableSetOf<String>()
        val edges = redirectEdges(declaration, navName) + collectEdges(declaration, navName, subtypesBySupertype, visited)
        reached += visited

        return DestinationInfo(
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            navName = navName,
            argsQualifiedName = argsDeclaration?.qualifiedName?.asString(),
            argsSwiftName = argsDeclaration?.swiftExportName(),
            responseQualifiedName = responseDeclaration?.qualifiedName?.asString(),
            responseSwiftName = responseDeclaration?.swiftExportName(),
            routePath = routePath,
            routeArgs = routeArgs,
            edges = edges,
            routeAliases = routeAliases,
            routeDefaults = routeDefaults,
        )
    }

    /**
     * A `@HostedComponent` as a navigation source: its own edges plus those of everything it embeds,
     * collected as for a destination. It has no args, response or route.
     */
    private fun buildHostedComponent(
        declaration: KSClassDeclaration,
        subtypesBySupertype: Map<String, List<KSClassDeclaration>>,
        reached: MutableSet<String>,
    ): DestinationInfo {
        val simpleName = declaration.simpleName.asString()
        val navName = declaration.stringArgument(HOSTED_COMPONENT_ANNOTATION, "navName")
            ?.takeIf { it.isNotEmpty() }
            ?: simpleName.toDestinationNavName()
        val visited = mutableSetOf<String>()
        val edges = collectEdges(declaration, navName, subtypesBySupertype, visited)
        reached += visited
        return DestinationInfo(
            simpleName = simpleName,
            qualifiedName = declaration.qualifiedName!!.asString(),
            navName = navName,
            argsQualifiedName = null,
            argsSwiftName = null,
            responseQualifiedName = null,
            responseSwiftName = null,
            routePath = null,
            routeArgs = emptyList(),
            edges = edges,
            hosted = true,
        )
    }

    /** One edge per `@RedirectsTo` target on the destination itself, in declaration order. */
    private fun redirectEdges(declaration: KSClassDeclaration, navName: String): List<NavEdge> =
        declaration.annotations
            .filter { it.qualifiedName() == REDIRECTS_TO_ANNOTATION }
            .flatMap { annotation ->
                (annotation.arguments.firstOrNull { it.name?.asString() == "targets" }?.value as? List<*>).orEmpty()
            }
            .mapNotNull { (it as? KSType)?.declaration?.qualifiedName?.asString() }
            .distinct()
            .map { NavEdge(navName, REDIRECT_METHOD, it, redirect = true) }
            .toList()

    /**
     * Collects the `@NavigateTo` edges declared on [declaration] plus those on any viewmodel it
     * embeds through `@ChildViewModel`, `@ViewModelList`, `DeltaList` or `SectionedDeltaList`
     * properties, recursively. A list row or child pane navigates with its host's navigator, because
     * only `@Destination`s own a navigator, so embedded edges roll up into the hosting destination's
     * graph. Each edge keeps the name of the spec that declares it ([sourceNavName] for
     * [declaration]). [visited] guards against spec cycles.
     */
    private fun collectEdges(
        declaration: KSClassDeclaration,
        sourceNavName: String,
        subtypesBySupertype: Map<String, List<KSClassDeclaration>>,
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
                    target?.declaration?.qualifiedName?.asString()
                        ?.let { NavEdge(sourceNavName, methodName, it) }
                }
        }.toList()

        val embedded = declaration.getDeclaredProperties()
            .flatMap { embeddedViewModelTypes(it, subtypesBySupertype) }
            .flatMap { collectEdges(it, it.edgeSourceNavName(), subtypesBySupertype, visited) }
            .toList()

        return (own + embedded).distinct()
    }

    /**
     * The name an embedded type's edges are attributed to: the spec itself, or for an implementation
     * class the first `@ViewModelSpec` it implements, so an edge declared on `RoomRowViewModelImpl`
     * and one on `RoomRowViewModel` name the same call site.
     */
    private fun KSClassDeclaration.edgeSourceNavName(): String {
        val spec = if (classKind == ClassKind.CLASS) {
            getAllSuperTypes()
                .mapNotNull { it.declaration as? KSClassDeclaration }
                .firstOrNull { type -> type.annotations.any { it.qualifiedName() == VIEWMODEL_SPEC_ANNOTATION } }
        } else {
            null
        }
        return (spec ?: this).simpleName.asString().toDestinationNavName()
    }

    /**
     * The viewmodel types a property embeds in its host: the `@ChildViewModel` type, the
     * `@ViewModelList` possible types and element, or the element of a `DeltaList` /
     * `SectionedDeltaList` / `Flow<Delta<X>>`. Rows are often typed by a marker interface (e.g.
     * `SectionedDeltaList<Header, HomeListItem>`), and their implementations may declare edges too,
     * so every viewmodel interface and class extending an embedded type is included.
     */
    private fun embeddedViewModelTypes(
        property: KSPropertyDeclaration,
        subtypesBySupertype: Map<String, List<KSClassDeclaration>>,
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
                possibleTypes + listOfNotNull(deltaListElement(property.type.resolve()))
            }
            else -> listOfNotNull(deltaListElement(property.type.resolve()))
        }.distinctBy { it.qualifiedName?.asString() }

        val subtypes = declared.flatMap { type ->
            subtypesBySupertype[type.qualifiedName?.asString()].orEmpty()
        }
        return (declared + subtypes).distinctBy { it.qualifiedName?.asString() }
    }

    /** The element type of a `DeltaList<X>`, `SectionedDeltaList<S, X>` or their `Flow<Delta<X>>` expansions. */
    private fun deltaListElement(type: KSType): KSClassDeclaration? {
        val element = when (type.declaration.qualifiedName?.asString()) {
            DELTA_LIST, SECTIONED_DELTA_LIST -> type.arguments.lastOrNull()
            FLOW -> type.arguments.singleOrNull()?.type?.resolve()
                ?.takeIf { it.declaration.qualifiedName?.asString() in setOf(DELTA, SECTIONED_DELTA) }
                ?.arguments?.lastOrNull()
            else -> null
        }
        return element?.type?.resolve()?.declaration as? KSClassDeclaration
    }
}
