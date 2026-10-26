package com.latenighthack.basekit.catalog.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.latenighthack.basekit.navigation.codegen.DestinationInfo
import com.latenighthack.basekit.navigation.codegen.NavEdge
import com.latenighthack.basekit.navigation.codegen.NavigationExtractor
import com.latenighthack.basekit.navigation.codegen.toDestinationNavName
import com.latenighthack.basekit.viewmodel.codegen.ViewModelExtractor
import com.latenighthack.basekit.viewmodel.codegen.VmInfo
import com.latenighthack.basekit.viewmodel.codegen.VmType
import com.latenighthack.basekit.viewmodel.codegen.mutatorNoun
import com.latenighthack.basekit.navigation.codegen.toUpperCamelCase as snakeToUpperCamelCase

private const val VIEWMODEL_SPEC = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
private const val VIEWMODEL_LIST = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
private const val CODEGEN_IGNORE = "com.latenighthack.basekit.viewmodel.annotations.CodegenIgnore"
private const val REDIRECTS_TO = "com.latenighthack.basekit.navigation.annotations.RedirectsTo"
private const val FLOW = "kotlinx.coroutines.flow.Flow"
private const val DELTA = "com.latenighthack.deltalist.Delta"
private const val SECTIONED_DELTA = "com.latenighthack.deltalist.SectionedDelta"
private const val DELTA_LIST = "com.latenighthack.deltalist.DeltaList"
private const val SECTIONED_DELTA_LIST = "com.latenighthack.deltalist.SectionedDeltaList"

/**
 * The catalog, the source files it was read from (the output's KSP dependencies), and where each entry
 * is declared: catalog path (`Home`, `Home.actions.onRefresh`, `types.PickResult`) -> `path:line`.
 */
data class CatalogExtraction(
    val catalog: Catalog,
    val sourceFiles: List<KSFile>,
    val locations: CatalogLocations = CatalogLocations(null, emptyMap()),
)

/** Source locations, relative to [root] when it is set. */
data class CatalogLocations(val root: String?, val entries: Map<String, String>)

/**
 * Reads every `@Destination` and `@ViewModelSpec` in a compilation into a [Catalog].
 *
 * The ViewModel and navigation processors already report invalid declarations, so their extractors
 * run here with a silent logger; [logger] only receives catalog warnings: code that catalog/v1 does
 * not allow, such as a mutator with `@NavigateTo`, which is still written as the code declares it.
 */
class CatalogExtractor(
    private val resolver: Resolver,
    private val logger: KSPLogger,
    /** Source locations are written relative to this directory when set (the root project). */
    private val sourceRoot: String? = null,
) {

    private class Spec(val declaration: KSClassDeclaration, val info: VmInfo)

    /** A type as written in the catalog: simple names, type arguments, `?` for nullable. */
    private data class TypeRef(val qualifiedName: String, val nullable: Boolean, val arguments: List<TypeRef>)

    /** [subtypes] holds qualified names of class subtypes and simple names of `object` subtypes. */
    private class TypeDeclaration(
        val enumCases: List<String>? = null,
        val fields: List<Pair<String, TypeRef>>? = null,
        val subtypes: List<String>? = null,
        val classSubtypes: List<String> = emptyList(),
        val external: Boolean = false,
    )

    private lateinit var destinations: Map<String, DestinationInfo>
    private lateinit var hostedComponents: Map<String, DestinationInfo>
    private lateinit var specs: Map<String, Spec>
    private lateinit var names: Map<String, String>
    private val typeDeclarations = LinkedHashMap<String, TypeDeclaration>()
    private lateinit var typeNames: Map<String, String>

    fun extract(): CatalogExtraction {
        val graph = NavigationExtractor(SilentLogger).extract(resolver)
        destinations = graph.destinations.associateBy { it.qualifiedName }
        hostedComponents = graph.hostedComponents.associateBy { it.qualifiedName }

        val specDeclarations = resolver.getSymbolsWithAnnotation(VIEWMODEL_SPEC)
            .filterIsInstance<KSClassDeclaration>()
            .filterNot { it.hasAnnotation(CODEGEN_IGNORE) }
            .toList()
        val viewModelExtractor = ViewModelExtractor(SilentLogger)
        specs = specDeclarations
            .mapNotNull { declaration -> viewModelExtractor.build(declaration)?.let { Spec(declaration, it) } }
            .associateBy { it.info.qualifiedName }

        names = catalogNames()

        // Every edge, deduplicated: a row embedded by two screens rolls up into both of their graphs.
        val edges = graph.sources.flatMap { it.edges }.distinct()
        val edgesBySource = edges.groupBy { it.sourceNavName }
        warnUnattributedEdges(edges)

        collectTypes()

        val screens = graph.destinations
            .map { destination -> screen(destination, edgesBySource) }
            .sortedBy { it.name }
        val components = specs.values
            .filter { it.info.qualifiedName !in destinations }
            .map { viewModel(it, edgesBySource, navigation = null).copy(hosted = it.info.qualifiedName in hostedComponents) }

        val catalog = Catalog(
            screens = screens,
            componentGroups = groupComponents(screens, components),
            types = typeDeclarations.map { (qualifiedName, declaration) -> catalogType(qualifiedName, declaration) }
                .sortedBy { it.name },
        )
        val sources = (graph.sourceFiles + specDeclarations.mapNotNull { it.containingFile }).distinct()
        return CatalogExtraction(catalog, sources, locations(screens + components))
    }

    // ---- source locations ----

    /** Where each screen, component, member and type is declared, keyed by its catalog path. */
    private fun locations(vms: List<CatalogViewModel>): CatalogLocations {
        val entries = LinkedHashMap<String, String>()
        fun record(key: String, node: KSNode?) {
            val location = node?.location as? FileLocation ?: return
            entries[key] = "${relative(location.filePath)}:${location.lineNumber}"
        }
        vms.forEach { vm ->
            val declaration = specs[vm.spec]?.declaration ?: classDeclaration(vm.spec) ?: return@forEach
            record(vm.name, declaration)
            if (vm.redirectsTo.isNotEmpty()) {
                record(
                    "${vm.name}.redirects_to",
                    declaration.annotations.firstOrNull {
                        it.annotationType.resolve().declaration.qualifiedName?.asString() == REDIRECTS_TO
                    },
                )
            }
            val properties = declaration.getAllProperties().associateBy { it.simpleName.asString() }
            val functions = declaration.getAllFunctions().associateBy { it.simpleName.asString() }
            destinations[vm.spec]?.argsQualifiedName?.let(::classDeclaration)?.let { args ->
                val argProperties = args.getDeclaredProperties().associateBy { it.simpleName.asString() }
                vm.navigation?.args?.forEach { record("${vm.name}.navigation.args.${it.name}", argProperties[it.name]) }
            }
            specs[vm.spec]?.info?.stateQualifiedName?.let(::classDeclaration)?.let { state ->
                val stateProperties = state.getAllProperties().associateBy { it.simpleName.asString() }
                vm.state.forEach { record("${vm.name}.state.${it.name}", stateProperties[it.name]) }
            }
            vm.identity?.let { record("${vm.name}.identity", properties[it]) }
            vm.actions.forEach { record("${vm.name}.actions.${it.name}", functions[it.name]) }
            vm.mutations.forEach { record("${vm.name}.mutations.${it.name}", functions[it.name]) }
            vm.lists.forEach { record("${vm.name}.lists.${it.name}", properties[it.name]) }
            vm.children.forEach { record("${vm.name}.children.${it.name}", properties[it.name]) }
        }
        typeDeclarations.keys.forEach { qualifiedName ->
            record("types.${typeNames.getValue(qualifiedName)}", classDeclaration(qualifiedName))
        }
        return CatalogLocations(sourceRoot, entries)
    }

    private fun relative(path: String): String {
        val root = sourceRoot?.trimEnd('/') ?: return path
        return if (path.startsWith("$root/")) path.removePrefix("$root/") else path
    }

    // ---- names ----

    /** The snake_case name basekit's navigation code knows a spec or destination by. */
    private fun navName(qualifiedName: String, simpleName: String): String =
        destinations[qualifiedName]?.navName ?: hostedComponents[qualifiedName]?.navName ?: simpleName.toDestinationNavName()

    /**
     * catalog/v1 names every screen and component by its navigation name in UpperCamelCase:
     * `IHomeViewModel` is `Home`. Two entries that would share a name keep their qualified names.
     */
    private fun catalogNames(): Map<String, String> {
        val simpleNames = destinations.mapValues { it.value.simpleName } + specs.mapValues { it.value.info.simpleName }
        val derived = simpleNames.mapValues { (qualifiedName, simpleName) ->
            navName(qualifiedName, simpleName).snakeToUpperCamelCase()
        }
        val collisions = derived.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        collisions.forEach { (name, qualifiedNames) ->
            logger.warn(
                "catalog: ${qualifiedNames.sorted()} all derive the catalog name \"$name\"; " +
                    "they are written under their qualified names",
            )
        }
        return derived.mapValues { (qualifiedName, name) -> if (name in collisions) qualifiedName else name }
    }

    private fun nameOf(qualifiedName: String, simpleName: String): String =
        names[qualifiedName] ?: simpleName

    /** A list's element: a spec's catalog name, or a marker interface named by the same rule. */
    private fun elementName(qualifiedName: String, simpleName: String): String =
        names[qualifiedName] ?: simpleName.toDestinationNavName().snakeToUpperCamelCase()

    // ---- screens and components ----

    private fun screen(destination: DestinationInfo, edgesBySource: Map<String, List<NavEdge>>): CatalogViewModel {
        val navigation = CatalogNavigation(
            args = destination.argsQualifiedName?.let(::argsFields).orEmpty(),
            route = destination.routePath,
            routeArgs = destination.routeArgs,
            routeAliases = destination.routeAliases,
            response = destination.responseQualifiedName?.let { typeName(TypeRef(it, false, emptyList())) },
        )
        val spec = specs[destination.qualifiedName]
            ?: return CatalogViewModel(
                name = nameOf(destination.qualifiedName, destination.simpleName),
                spec = destination.qualifiedName,
                navigation = navigation,
                actions = destination.edges
                    .filter { it.sourceNavName == destination.navName && !it.redirect }
                    .groupBy { it.methodName }
                    .map { (method, methodEdges) -> CatalogAction(method, targets(methodEdges)) },
                redirectsTo = targets(destination.edges.filter { it.redirect }),
            )
        return viewModel(spec, edgesBySource, navigation).copy(redirectsTo = targets(destination.edges.filter { it.redirect }))
    }

    private fun viewModel(
        spec: Spec,
        edgesBySource: Map<String, List<NavEdge>>,
        navigation: CatalogNavigation?,
    ): CatalogViewModel {
        val vm = spec.info
        val ownEdges = edgesBySource[navName(vm.qualifiedName, vm.simpleName)].orEmpty().filterNot { it.redirect }
        val edgesByMethod = ownEdges.groupBy { it.methodName }

        val actions = vm.actions.map { CatalogAction(it.name, targets(edgesByMethod[it.name].orEmpty())) }
        val mutations = vm.mutators.map { mutator ->
            val navigatesTo = targets(edgesByMethod[mutator.name].orEmpty())
            if (navigatesTo.isNotEmpty()) {
                logger.warn(
                    "catalog: ${vm.simpleName}.${mutator.name} takes an argument and has @NavigateTo; " +
                        "catalog/v1 only allows navigates_to on zero-argument actions. It is written as a " +
                        "mutation with navigates_to",
                )
            }
            val noun = mutatorNoun(mutator.name)
            val binds = vm.stateProperties
                .firstOrNull { it.name == noun && it.typeQualifiedName == mutator.paramTypeQualifiedName }
                ?.name
            CatalogMutation(mutator.name, CatalogField(mutator.paramName, typeName(mutator.type.ref())), binds, navigatesTo)
        }
        val bound = (vm.actions.map { it.name } + vm.mutators.map { it.name }).toSet()
        ownEdges.filter { it.methodName !in bound }.forEach { edge ->
            logger.warn(
                "catalog: @NavigateTo on ${vm.simpleName}.${edge.methodName} is not a bound action or " +
                    "mutation of the spec; the edge is left out of the catalog",
            )
        }

        return CatalogViewModel(
            name = nameOf(vm.qualifiedName, vm.simpleName),
            spec = vm.qualifiedName,
            navigation = navigation,
            identity = vm.identityProperty,
            state = vm.stateProperties.map { property ->
                CatalogField(property.name, typeName(property.type.ref()), stateFake(vm.stateQualifiedName, property.name))
            },
            actions = actions,
            mutations = mutations,
            lists = lists(spec),
            children = vm.children.map { CatalogField(it.propertyName, nameOf(it.typeQualifiedName, it.typeSimpleName)) },
        )
    }

    private val fakes = CatalogFakes { declaration ->
        val qualifiedName = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        nameOf(qualifiedName, declaration.simpleName.asString())
    }

    /** The fake annotations on a State constructor parameter and its property. */
    private fun stateFake(stateQualifiedName: String, field: String): CatalogFake? {
        val state = classDeclaration(stateQualifiedName) ?: return null
        val parameter = state.primaryConstructor?.parameters?.firstOrNull { it.name?.asString() == field }
        val property = state.getAllProperties().firstOrNull { it.simpleName.asString() == field }
        return fakes.field(parameter, property)
    }

    private fun targets(edges: List<NavEdge>): List<String> = edges.map { edge ->
        val destination = destinations[edge.targetQualifiedName]
        nameOf(edge.targetQualifiedName, destination?.simpleName ?: edge.targetQualifiedName.substringAfterLast('.'))
    }.distinct()

    /**
     * `@ViewModelList` properties come from the ViewModel model. `DeltaList`, `SectionedDeltaList` and
     * `Flow<Delta>` / `Flow<SectionedDelta>` properties without the annotation are read here; their rows
     * are every `@ViewModelSpec` that is, or extends, the element type.
     */
    private fun lists(spec: Spec): List<CatalogList> {
        val annotated = spec.info.lists.associateBy { it.propertyName }
        return spec.declaration.getAllProperties()
            .filterNot { it.hasAnnotation(CODEGEN_IGNORE) }
            .mapNotNull { property ->
                val name = property.simpleName.asString()
                annotated[name]?.let { list ->
                    return@mapNotNull CatalogList(
                        name = name,
                        kind = if (list.sectioned) CatalogListKind.SECTIONED else CatalogListKind.FLAT,
                        sections = list.header?.cases?.map { it.name }.orEmpty(),
                        element = elementName(list.elementQualifiedName, list.elementSimpleName),
                        rows = list.possibleTypes.map { nameOf(it.qualifiedName, it.simpleName) },
                        fake = fakes.list(property),
                    )
                }
                if (property.hasAnnotation(VIEWMODEL_LIST)) return@mapNotNull null
                deltaList(property)
            }
            .toList()
    }

    private fun deltaList(property: KSPropertyDeclaration): CatalogList? {
        val type = property.type.resolve()
        val (kind, arguments) = when (type.declaration.qualifiedName?.asString()) {
            DELTA_LIST -> CatalogListKind.FLAT to type.arguments
            SECTIONED_DELTA_LIST -> CatalogListKind.SECTIONED to type.arguments
            FLOW -> {
                val delta = type.arguments.singleOrNull()?.type?.resolve() ?: return null
                when (delta.declaration.qualifiedName?.asString()) {
                    DELTA -> CatalogListKind.FLAT to delta.arguments
                    SECTIONED_DELTA -> CatalogListKind.SECTIONED to delta.arguments
                    else -> return null
                }
            }
            else -> return null
        }
        val element = arguments.lastOrNull()?.type?.resolve()?.declaration as? KSClassDeclaration ?: return null
        val elementQualifiedName = element.qualifiedName?.asString() ?: return null
        val header = if (kind == CatalogListKind.SECTIONED) {
            arguments.firstOrNull()?.type?.resolve()?.declaration as? KSClassDeclaration
        } else {
            null
        }
        val rows = specs.values
            .filter { spec ->
                spec.info.qualifiedName == elementQualifiedName ||
                    spec.declaration.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == elementQualifiedName }
            }
            .map { nameOf(it.info.qualifiedName, it.info.simpleName) }
            .sorted()
        return CatalogList(
            name = property.simpleName.asString(),
            kind = kind,
            sections = header?.let(::sectionNames).orEmpty(),
            element = elementName(elementQualifiedName, element.simpleName.asString()),
            rows = rows,
            fake = fakes.list(property),
        )
    }

    /** The section header's cases: enum entries, or the subclasses of a sealed header. */
    private fun sectionNames(header: KSClassDeclaration): List<String> = when {
        header.classKind == ClassKind.ENUM_CLASS -> enumEntries(header)
        Modifier.SEALED in header.modifiers -> header.getSealedSubclasses().map { it.simpleName.asString() }.toList()
        else -> emptyList()
    }

    /**
     * Components go under the screen that embeds them, through lists or children at any depth.
     * Components more than one screen embeds come first; those no screen embeds come last.
     */
    private fun groupComponents(
        screens: List<CatalogViewModel>,
        components: List<CatalogViewModel>,
    ): List<CatalogComponentGroup> {
        val byName = (screens + components).associateBy { it.name }
        val hosts = mutableMapOf<String, MutableSet<String>>()
        // A hosted component groups the components it embeds, as a screen does.
        val hostedRoots = components.filter { it.hosted }
        (screens + hostedRoots).forEach { screen ->
            val seen = mutableSetOf(screen.name)
            val queue = ArrayDeque(listOf(screen))
            while (queue.isNotEmpty()) {
                val vm = queue.removeFirst()
                val embedded = vm.lists.flatMap { it.rows + it.element } + vm.children.map { it.type }
                embedded.filter { seen.add(it) }.mapNotNull { byName[it] }.forEach { child ->
                    hosts.getOrPut(child.name) { mutableSetOf() }.add(screen.name)
                    queue.addLast(child)
                }
            }
        }
        val sorted = components.sortedBy { it.name }
        val shared = sorted.filter { hosts[it.name].orEmpty().size > 1 }
        val perScreen = (screens + hostedRoots).map { screen ->
            CatalogComponentGroup("${screen.name} components", sorted.filter { hosts[it.name] == setOf(screen.name) })
        }
        val unhosted = sorted.filter { hosts[it.name].isNullOrEmpty() && !it.hosted }
        return listOf(CatalogComponentGroup("Shared components", shared)) +
            CatalogComponentGroup("Hosted by every screen", sorted.filter { it.hosted }) +
            perScreen +
            CatalogComponentGroup("Not embedded by any screen", unhosted)
    }

    /**
     * An edge declared by a type that is not a `@ViewModelSpec` (a marker interface, or an
     * implementation of no spec) has no catalog entry to sit on.
     */
    private fun warnUnattributedEdges(edges: List<NavEdge>) {
        val known = destinations.values.map { it.navName }.toSet() +
            specs.values.map { navName(it.info.qualifiedName, it.info.simpleName) }
        edges.filter { it.sourceNavName !in known }.forEach { edge ->
            logger.warn(
                "catalog: @NavigateTo on ${edge.sourceNavName}.${edge.methodName} is declared outside any " +
                    "@ViewModelSpec or @Destination; the edge is left out of the catalog",
            )
        }
    }

    // ---- types ----

    private fun argsFields(qualifiedName: String): List<CatalogField> {
        val declaration = classDeclaration(qualifiedName) ?: return emptyList()
        return publicProperties(declaration).map { (name, type) -> CatalogField(name, typeName(type)) }
    }

    /**
     * Collects every non-builtin type referenced from state, mutator params, args and responses, and
     * the types their fields reference in turn, so [typeName] can tell when two share a simple name.
     */
    private fun collectTypes() {
        val roots = buildList {
            specs.values.forEach { spec ->
                spec.info.stateProperties.forEach { add(it.type.ref()) }
                spec.info.mutators.forEach { add(it.type.ref()) }
            }
            destinations.values.forEach { destination ->
                destination.argsQualifiedName?.let(::classDeclaration)?.let { args ->
                    publicProperties(args).forEach { add(it.second) }
                }
                destination.responseQualifiedName?.let { add(TypeRef(it, false, emptyList())) }
            }
        }
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val ref = queue.removeFirst()
            queue.addAll(ref.arguments)
            val qualifiedName = ref.qualifiedName
            if (isBuiltin(qualifiedName) || qualifiedName in typeDeclarations || qualifiedName in specs) continue
            val declaration = declareType(qualifiedName)
            typeDeclarations[qualifiedName] = declaration
            declaration.fields?.forEach { queue.addLast(it.second) }
            declaration.classSubtypes.forEach { queue.addLast(TypeRef(it, false, emptyList())) }
        }

        val bySimpleName = typeDeclarations.keys.groupBy { it.substringAfterLast('.') }
        bySimpleName.filterValues { it.size > 1 }.forEach { (simpleName, qualifiedNames) ->
            logger.warn(
                "catalog: types ${qualifiedNames.sorted()} share the simple name \"$simpleName\"; " +
                    "each is written with as many enclosing names as tell it apart, e.g. Button.Style",
            )
        }
        typeNames = typeDeclarations.keys.associateWith { qualifiedName ->
            uniqueSuffix(qualifiedName, bySimpleName.getValue(qualifiedName.substringAfterLast('.')))
        }
    }

    /** The fewest trailing segments of [qualifiedName] that no other name in [group] ends with. */
    private fun uniqueSuffix(qualifiedName: String, group: List<String>): String {
        val segments = qualifiedName.split('.')
        val others = group.filter { it != qualifiedName }.map { it.split('.') }
        for (count in 1..segments.size) {
            val suffix = segments.takeLast(count)
            if (others.none { it.takeLast(count) == suffix }) return suffix.joinToString(".")
        }
        return qualifiedName
    }

    private fun declareType(qualifiedName: String): TypeDeclaration {
        val declaration = classDeclaration(qualifiedName) ?: return TypeDeclaration(external = true)
        if (declaration.classKind == ClassKind.ENUM_CLASS) return TypeDeclaration(enumCases = enumEntries(declaration))
        if (declaration.origin != Origin.KOTLIN && declaration.origin != Origin.JAVA) {
            return TypeDeclaration(external = true)
        }
        // A sealed subtype that declares no public properties (an `object`, or a case class such as a
        // ktbuf enum entry) is a case and is named in place; a subtype with properties is listed as a
        // type of its own.
        val sealed = if (Modifier.SEALED in declaration.modifiers) declaration.getSealedSubclasses().toList() else null
        fun KSClassDeclaration.isCase() = publicProperties(this).isEmpty()
        val classSubtypes = sealed.orEmpty()
            .filterNot { it.isCase() }
            .mapNotNull { it.qualifiedName?.asString() }
        val subtypes = sealed?.map { subtype ->
            if (subtype.isCase()) subtype.simpleName.asString()
            else subtype.qualifiedName?.asString() ?: subtype.simpleName.asString()
        }
        val fields = publicProperties(declaration)
        return TypeDeclaration(
            fields = fields.takeIf { it.isNotEmpty() || subtypes == null },
            subtypes = subtypes,
            classSubtypes = classSubtypes,
        )
    }

    private fun catalogType(qualifiedName: String, declaration: TypeDeclaration) = CatalogType(
        name = typeNames.getValue(qualifiedName),
        enumCases = declaration.enumCases,
        fields = declaration.fields?.map { (name, type) -> CatalogField(name, typeName(type)) },
        subtypes = declaration.subtypes?.map { typeNames[it] ?: it },
        external = qualifiedName.takeIf { declaration.external },
    )

    /** A class's public properties, in declaration order: its constructor `val`s first. */
    private fun publicProperties(declaration: KSClassDeclaration): List<Pair<String, TypeRef>> =
        declaration.getDeclaredProperties()
            .filter { property -> property.modifiers.none { it in setOf(Modifier.PRIVATE, Modifier.PROTECTED, Modifier.INTERNAL) } }
            .filterNot { it.hasAnnotation(CODEGEN_IGNORE) }
            .map { it.simpleName.asString() to it.type.resolve().ref() }
            .toList()

    private fun typeName(ref: TypeRef): String {
        val base = if (ref.qualifiedName == "*") "*" else typeNames[ref.qualifiedName]
            ?: specs[ref.qualifiedName]?.let { nameOf(ref.qualifiedName, it.info.simpleName) }
            ?: ref.qualifiedName.substringAfterLast('.')
        val arguments = if (ref.arguments.isEmpty()) "" else ref.arguments.joinToString(", ", "<", ">") { typeName(it) }
        return base + arguments + if (ref.nullable) "?" else ""
    }

    private fun isBuiltin(qualifiedName: String): Boolean =
        qualifiedName == "*" || qualifiedName.startsWith("kotlin.")

    private fun classDeclaration(qualifiedName: String): KSClassDeclaration? =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(qualifiedName))

    private fun enumEntries(declaration: KSClassDeclaration): List<String> =
        declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()

    private fun VmType.ref(): TypeRef = TypeRef(qualifiedName, nullable, arguments.map { it.ref() })

    private fun KSType.ref(): TypeRef = TypeRef(
        qualifiedName = declaration.qualifiedName?.asString() ?: "kotlin.Any",
        nullable = isMarkedNullable,
        arguments = arguments.map { it.type?.resolve()?.ref() ?: TypeRef("*", false, emptyList()) },
    )

    private fun com.google.devtools.ksp.symbol.KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
}

/** The extractors' own processors already report their errors; the catalog does not repeat them. */
private object SilentLogger : KSPLogger {
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) = Unit
    override fun error(message: String, symbol: KSNode?) = Unit
    override fun exception(e: Throwable) = Unit
}
