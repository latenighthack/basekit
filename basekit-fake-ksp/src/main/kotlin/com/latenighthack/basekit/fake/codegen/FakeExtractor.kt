package com.latenighthack.basekit.fake.codegen

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.latenighthack.basekit.navigation.codegen.DestinationInfo
import com.latenighthack.basekit.navigation.codegen.NavEdge
import com.latenighthack.basekit.navigation.codegen.NavigationExtractor
import com.latenighthack.basekit.navigation.codegen.navigationPackage
import com.latenighthack.basekit.navigation.codegen.outboundTargets
import com.latenighthack.basekit.navigation.codegen.sourceEntry
import com.latenighthack.basekit.navigation.codegen.targetCallSites
import com.latenighthack.basekit.viewmodel.codegen.ViewModelExtractor
import com.latenighthack.basekit.viewmodel.codegen.VmInfo
import com.latenighthack.basekit.viewmodel.codegen.assistedParameterType
import com.latenighthack.basekit.viewmodel.codegen.commonPackage
import com.latenighthack.basekit.viewmodel.codegen.mutatorNoun
import com.latenighthack.basekit.navigation.codegen.toDestinationNavName as navNameOf
import com.latenighthack.basekit.navigation.codegen.toUpperCamelCase as navCapOf

/** Everything the fakes of one compilation are written from. */
internal class FakeOutput(
    val sharedPackage: String,
    val specs: List<FakeSpec>,
    val destinations: List<FakeDestination>,
    val navigatorTypes: List<String>,
    val hasClose: Boolean,
    val viewModelFunctions: List<Pair<String, FakeSpec>>,
    val valueImports: List<String>,
    val valueFunctions: List<String>,
    val sourceFiles: List<KSFile>,
)

/**
 * Reads every `@ViewModelSpec` of a compilation, the navigation graph, the real `@ViewModelInject`
 * implementations and the `@FakeProvider` functions, and describes the fake of each spec.
 */
internal class FakeExtractor(
    private val resolver: Resolver,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) {
    private lateinit var graph: com.latenighthack.basekit.navigation.codegen.NavigationGraph
    private lateinit var navPackage: String
    private lateinit var values: FakeValueBuilder
    private val destinationsByName = mutableMapOf<String, DestinationInfo>()
    private val sourcesByName = mutableMapOf<String, DestinationInfo>()
    private lateinit var callSites: Map<String, List<NavEdge>>
    private lateinit var edgesBySource: Map<String, List<NavEdge>>
    /** Each destination's `@RedirectsTo` edges, by its qualified name, in declaration order. */
    private lateinit var redirectsBySource: Map<String, List<NavEdge>>
    private val fakeClasses = mutableMapOf<String, String>()
    private val infos = mutableMapOf<String, VmInfo>()
    private val needs = mutableMapOf<String, Needs>()

    private data class Needs(val targets: Set<String>, val responds: Boolean)

    fun extract(): FakeOutput? {
        val specs = resolver.getSymbolsWithAnnotation(VIEWMODEL_SPEC)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE && !it.hasAnnotation(CODEGEN_IGNORE) }
            .toList()
        if (specs.isEmpty()) return null

        graph = NavigationExtractor(SilentLogger).extract(resolver)
        navPackage = navigationPackage(options[NAVIGATION_PACKAGE_OPTION], graph.destinations)
        graph.destinations.forEach { destinationsByName[it.qualifiedName] = it }
        graph.sources.forEach { sourcesByName[it.qualifiedName] = it }
        callSites = targetCallSites(graph.sources)
        edgesBySource = graph.sources.flatMap { it.edges }.filterNot { it.redirect }.distinct().groupBy { it.sourceNavName }
        redirectsBySource = graph.destinations.associate { destination -> destination.qualifiedName to destination.edges.filter { it.redirect } }

        val sharedPackage = if (graph.destinations.isNotEmpty()) navPackage else commonPackage(specs.map { it.packageName.asString() })
        values = FakeValueBuilder(
            logger,
            listOf(sharedPackage, "FakeValues").filter { it.isNotEmpty() }.joinToString("."),
            defaultListMin = intOption(LIST_MIN_OPTION),
            defaultListMax = intOption(LIST_MAX_OPTION),
        )
        val providerFunctions = resolver.getSymbolsWithAnnotation("$ANNOTATIONS_PACKAGE.FakeProvider")
            .filterIsInstance<KSFunctionDeclaration>().toList()
        values.addProviders(providerFunctions)

        val viewModelExtractor = ViewModelExtractor(SilentLogger)
        val built = specs.mapNotNull { spec -> viewModelExtractor.build(spec)?.let { spec to it } }
        built.forEach { (spec, info) -> infos[spec.qualifiedName!!.asString()] = info }

        val implementations = resolver.getSymbolsWithAnnotation(VIEWMODEL_INJECT)
            .filterIsInstance<KSClassDeclaration>()
            .filterNot { it.hasAnnotation(CODEGEN_IGNORE) || it.hasAnnotation(GENERATED_FAKE) }
            .sortedBy { it.qualifiedName?.asString() }
            .toList()
        val implementationBySpec = implementations
            .mapNotNull { implementation -> implementation.firstSpec()?.qualifiedName?.asString()?.let { it to implementation } }
            .distinctBy { it.first }
            .toMap()

        for ((spec, _) in built) {
            val qualifiedName = spec.qualifiedName!!.asString()
            val packageName = implementationBySpec[qualifiedName]?.packageName?.asString() ?: spec.packageName.asString()
            val fakeClass = listOf(packageName, "Fake${spec.simpleName.asString()}").filter { it.isNotEmpty() }.joinToString(".")
            val existing = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fakeClass))
            if (existing != null && !existing.hasAnnotation(GENERATED_FAKE)) {
                logger.error("$fakeClass already exists; rename it, since the fake of ${spec.simpleName.asString()} is generated with that name", existing)
                return null
            }
            fakeClasses[qualifiedName] = fakeClass
        }

        val fakes = built.mapNotNull { (spec, info) -> fakeSpec(spec, info, implementationBySpec[spec.qualifiedName!!.asString()]) }
        val bySpec = fakes.associateBy { it.specQualifiedName }

        val destinations = graph.sources.map { source -> destination(source, bySpec[source.qualifiedName]) }
        val navigatorTypes = outboundTargets(graph.sources).keys.mapNotNull { sourcesByName[it] }
            .map { "${it.navName.navCapOf()}Navigator" }
        val functionNames = mutableSetOf<String>()
        val viewModelFunctions = fakes.map { fake ->
            val base = fake.specSimpleName.navNameOf().navCapOf().replaceFirstChar { it.lowercase() }
            var name = base
            var index = 2
            while (!functionNames.add(name)) name = base + index++
            name to fake
        }

        val sourceFiles = (specs + implementations).mapNotNull { it.containingFile } +
            providerFunctions.mapNotNull { it.containingFile } + graph.sourceFiles
        return FakeOutput(
            sharedPackage = sharedPackage,
            specs = fakes,
            destinations = destinations,
            navigatorTypes = navigatorTypes,
            hasClose = callSites.isNotEmpty(),
            viewModelFunctions = viewModelFunctions,
            valueImports = values.imports,
            valueFunctions = values.functions,
            sourceFiles = sourceFiles.distinct(),
        )
    }

    private fun intOption(name: String): Int? {
        val value = options[name]?.takeIf { it.isNotBlank() } ?: return null
        return value.trim().toIntOrNull()?.takeIf { it >= 0 } ?: run {
            logger.error("$name must be a whole number of rows (0 or more), not \"$value\"")
            null
        }
    }

    private fun destination(source: DestinationInfo, fake: FakeSpec?): FakeDestination {
        val args = source.argsQualifiedName?.let(::declaration)
        val response = source.responseQualifiedName?.let(::declaration)
        val cap = source.navName.navCapOf()
        return FakeDestination(
            navCap = cap,
            specType = source.qualifiedName,
            specSimpleName = source.simpleName,
            fakeClass = fake?.qualifiedClassName ?: "",
            argsType = args?.qualifiedName?.asString(),
            argsBuilder = args?.let(values::argsBuilder),
            targets = fake?.targets.orEmpty(),
            responds = fake?.responds == true,
            responseType = source.responseQualifiedName,
            responseValue = response?.let { values.value(it.asStarProjectedType(), "data", "response", FakeAnnotations.NONE, it) },
            hosted = source.hosted,
            navigatedTo = callSites.containsKey(source.qualifiedName),
            hasSource = (callSites[source.qualifiedName]?.size ?: 0) > 1,
        )
    }

    private fun declaration(qualifiedName: String): KSClassDeclaration? =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(qualifiedName))

    /** The navigation name a spec's edges carry: its destination's, else derived from its simple name. */
    private fun edgeName(spec: KSClassDeclaration): String =
        sourcesByName[spec.qualifiedName?.asString()]?.navName ?: spec.simpleName.asString().navNameOf()

    private fun target(qualifiedName: String): FakeTarget? {
        val destination = destinationsByName[qualifiedName] ?: return null
        val cap = destination.navName.navCapOf()
        return FakeTarget(cap, "$navPackage.${cap}NavigationTarget")
    }

    /** The view model specs [spec] shows: list rows, children and other spec-typed properties. */
    private fun embeddedSpecs(spec: KSClassDeclaration): List<KSClassDeclaration> {
        val info = infos[spec.qualifiedName?.asString()]
        val listed = info?.lists.orEmpty().flatMap { list -> list.possibleTypes.mapNotNull { declaration(it.qualifiedName) } }
        val properties = spec.getAllProperties()
            .filter { it.isAbstract() }
            .mapNotNull { it.type.resolve().declaration as? KSClassDeclaration }
            .filter { it.isViewModelSpec() }
            .toList()
        return (listed + properties).distinctBy { it.qualifiedName?.asString() }
    }

    /** The targets [spec] and everything it shows navigate to, and whether any of them responds. */
    private fun needs(spec: KSClassDeclaration, visiting: MutableSet<String> = mutableSetOf()): Needs {
        val qualifiedName = spec.qualifiedName!!.asString()
        needs[qualifiedName]?.let { return it }
        if (!visiting.add(qualifiedName)) return Needs(emptySet(), false)
        val own = (edgesBySource[edgeName(spec)].orEmpty() + redirectsBySource[qualifiedName].orEmpty())
            .map { it.targetQualifiedName }.filter { it in destinationsByName }
        val responds = spec.getAllFunctions().any { it.hasAnnotation(FAKE_RESPONDS) }
        val embedded = embeddedSpecs(spec).map { needs(it, visiting) }
        val result = Needs(own.toSet() + embedded.flatMap { it.targets }, responds || embedded.any { it.responds })
        needs[qualifiedName] = result
        return result
    }

    private fun targets(qualifiedNames: Set<String>): List<FakeTarget> =
        qualifiedNames.mapNotNull(::target).sortedBy { it.navCap }

    private fun embedded(spec: KSClassDeclaration, site: KSNode?): FakeEmbedded {
        val qualifiedName = spec.qualifiedName!!.asString()
        val fakeClass = fakeClasses[qualifiedName] ?: run {
            val external = "${spec.packageName.asString()}.Fake${spec.simpleName.asString()}"
            if (declaration(external) == null) {
                logger.error("No fake for $qualifiedName: switch fakes on in the module that declares it", site)
            }
            external
        }
        val needs = needs(spec)
        return FakeEmbedded(fakeClass, targets(needs.targets), needs.responds)
    }

    private fun fakeSpec(spec: KSClassDeclaration, info: VmInfo, implementation: KSClassDeclaration?): FakeSpec? {
        val qualifiedName = spec.qualifiedName!!.asString()
        val specType = spec.asStarProjectedType()
        val destination = destinationsByName[qualifiedName]
        val args = destination?.argsQualifiedName?.let(::declaration)
        val stateType = spec.getAllSuperTypes().first { it.declaration.qualifiedName?.asString() == VIEWMODEL_INTERFACE }
            .arguments.first().type!!.resolve()
        val defaults = FakeDefaults(spec.annotations.filter { it.qualifiedName() == "$ANNOTATIONS_PACKAGE.FakeDefaults" }.toList())
        val stateBuilder = values.stateBuilder(stateType, args, defaults, spec)
        val initialState = "$stateBuilder(fakeData.child(\"state\")${if (args != null) ", fakeArgs" else ""})"
        val stateFields = stateFields(stateType)
        val needs = needs(spec)
        val targets = targets(needs.targets)

        val functions = spec.getAllFunctions().filter { it.isAbstract }.mapNotNull { function ->
            fakeFunction(spec, specType, function, stateFields)
        }.toList()

        val listNames = info.lists.map { it.propertyName }.toSet()
        val lists = info.lists.map { list ->
            val property = spec.getAllProperties().first { it.simpleName.asString() == list.propertyName }
            val annotations = FakeAnnotations.of(property)
            val rows = list.possibleTypes.mapNotNull { declaration(it.qualifiedName) }.map { embedded(it, property) }
            val placeholderType = annotations.of("FakePlaceholder").firstOrNull()?.let { annotation ->
                when (val value = annotation.argument("type")) {
                    is KSType -> value.declaration.qualifiedName?.asString()
                    is KSClassDeclaration -> value.qualifiedName?.asString()
                    else -> null
                }
            }
            val possibleNames = list.possibleTypes.map { it.qualifiedName }
            if (placeholderType != null && placeholderType !in possibleNames) {
                logger.error("@FakePlaceholder on ${list.propertyName} names $placeholderType, which is not one of the list's possibleTypes", property)
            }
            val placeholderIndex = possibleNames.indexOf(placeholderType)
            val sections = list.header?.let { header ->
                val selected = annotations.of("FakeCase").firstOrNull { it.dataSet().isEmpty() }?.strings("cases")
                    ?.let { names ->
                        names.filter { name -> header.cases.none { it.name == name } }.forEach { name ->
                            logger.error("@FakeCase on ${list.propertyName} names $name, which is not a section of ${header.qualifiedName}", property)
                        }
                        names.mapNotNull { name -> header.cases.firstOrNull { it.name == name } }
                    }
                    ?: header.cases
                if (annotations.of("FakeCase").any { it.dataSet().isNotEmpty() }) {
                    logger.warn("@FakeCase on ${list.propertyName} sets its sections for every data set; its dataSet is not used", property)
                }
                selected.mapIndexed { index, case ->
                    when {
                        header.isEnum -> "${header.qualifiedName}.${case.name}"
                        case.isObject -> case.qualifiedName!!
                        else -> values.value(
                            declaration(case.qualifiedName!!)!!.asStarProjectedType(),
                            "fakeData.child(\"${list.propertyName}Header\", $index)", "header", FakeAnnotations.NONE, property,
                        )
                    }
                }
            }
            FakeList(
                property = list.propertyName,
                type = property.asMemberOf(specType).render(),
                elementType = list.elementQualifiedName,
                size = values.listSize("{data}", list.propertyName, annotations, defaults),
                rows = rows,
                weights = rowWeights(possibleNames, annotations, property, placeholderType),
                sections = sections,
                headerType = list.header?.qualifiedName,
                placeholder = rows.getOrNull(placeholderIndex),
            )
        }

        val children = mutableListOf<FakeChild>()
        val properties = mutableListOf<FakeProperty>()
        spec.getAllProperties()
            .filter { it.isAbstract() }
            .filterNot { it.simpleName.asString() in listNames || it.simpleName.asString() in setOf("initialState", "state") }
            .forEach { property ->
                val name = property.simpleName.asString()
                val type = property.asMemberOf(specType)
                val declaration = type.declaration as? KSClassDeclaration
                when {
                    declaration != null && declaration.isViewModelSpec() ->
                        children += FakeChild(name, type.render(), embedded(declaration, property))
                    name == info.identityProperty ->
                        properties += FakeProperty(name, type.render(), property.isMutable, "fakeData.text(\"$name\", $ANNOTATIONS_PACKAGE.FakeTextKind.ID)")
                    else -> properties += FakeProperty(name, type.render(), property.isMutable, propertyValue(type, name, property))
                }
            }

        val inject = implementation?.let { injectConstructor(it, targets, needs.responds, args) }
        if (implementation != null && inject != null) {
            val provided = inject.arguments.map { it.substringBefore(" =") }.toSet()
            val missing = targets.filter { it.paramName !in provided }
            if (missing.isNotEmpty()) {
                logger.warn(
                    "${implementation.simpleName.asString()} takes no navigator for ${missing.joinToString { it.navCap }}; " +
                        "its fake records those navigations instead of opening them",
                    implementation,
                )
            }
        }
        if (destination?.responseQualifiedName != null && !needs.responds) {
            logger.warn(
                "${spec.simpleName.asString()} responds, but none of its actions (or its rows') is marked @FakeResponds; " +
                    "its fake can only be closed, which answers null",
                spec,
            )
        }

        val redirects = redirectsBySource[qualifiedName].orEmpty().mapNotNull { edge ->
            val target = destinationsByName[edge.targetQualifiedName] ?: return@mapNotNull null
            if (target.responseQualifiedName != null) {
                logger.warn("${spec.simpleName.asString()} redirects to ${target.simpleName}, which responds; its fake does not redirect there", spec)
                return@mapNotNull null
            }
            navigation(edge, REDIRECT_MEMBER, suspend = false, stateFields, spec)?.let { edge.targetQualifiedName to it }
        }

        val className = "Fake${spec.simpleName.asString()}"
        val qualifiedClass = fakeClasses.getValue(qualifiedName)
        return FakeSpec(
            specQualifiedName = qualifiedName,
            specSimpleName = spec.simpleName.asString(),
            packageName = qualifiedClass.substringBeforeLast('.', ""),
            className = className,
            internal = implementation != null && (implementation.isInternal() || implementation.isPrivate()),
            argsType = args?.qualifiedName?.asString(),
            stateType = stateType.render(),
            initialState = initialState,
            targets = targets,
            responds = needs.responds,
            inject = inject,
            functions = functions,
            lists = lists,
            children = children,
            properties = properties,
            redirects = redirects.map { it.second },
            redirectChoice = redirectChoice(spec, redirects.map { it.first }),
        )
    }

    /**
     * The index of the `@FakeRedirect` target among [targets] (-1 for the spec itself), per data set, or
     * null when the spec has no `@FakeRedirect`.
     */
    private fun redirectChoice(spec: KSClassDeclaration, targets: List<String>): String? {
        val annotations = spec.annotations.filter { it.qualifiedName() == "$ANNOTATIONS_PACKAGE.FakeRedirect" }.toList()
        if (annotations.isEmpty() || targets.isEmpty()) return null
        val own = spec.qualifiedName?.asString()
        fun index(annotation: com.google.devtools.ksp.symbol.KSAnnotation): String {
            val target = when (val value = annotation.argument("target")) {
                is KSType -> value.declaration.qualifiedName?.asString()
                is KSClassDeclaration -> value.qualifiedName?.asString()
                else -> null
            }
            return when (target) {
                own -> "-1"
                in targets -> targets.indexOf(target).toString()
                else -> {
                    logger.error(
                        "@FakeRedirect on ${spec.simpleName.asString()} names $target, which is neither one of its " +
                            "@RedirectsTo targets (${targets.joinToString()}) nor ${spec.simpleName.asString()} itself",
                        spec,
                    )
                    "0"
                }
            }
        }
        val sorted = annotations.sortedBy { it.dataSet().isNotEmpty() }
        val default = sorted.firstOrNull { it.dataSet().isEmpty() }?.let(::index) ?: "0"
        return variants("fakeData", default, sorted.filter { it.dataSet().isNotEmpty() }.map { it.dataSet() to index(it) })
    }

    /** State constructor fields by name, with their types, when the State is a data class. */
    private fun stateFields(stateType: KSType): Map<String, String> {
        val declaration = stateType.declaration as? KSClassDeclaration ?: return emptyMap()
        if (Modifier.DATA !in declaration.modifiers) return emptyMap()
        return declaration.primaryConstructor?.parameters.orEmpty()
            .associate { (it.name?.asString() ?: "") to it.type.resolve().render() }
    }

    private fun fakeFunction(spec: KSClassDeclaration, specType: KSType, function: KSFunctionDeclaration, stateFields: Map<String, String>): FakeFunction? {
        val name = function.simpleName.asString()
        if (function.typeParameters.isNotEmpty()) {
            logger.error("Fakes cannot implement the generic function ${spec.simpleName.asString()}.$name", function)
            return null
        }
        val resolved = function.asMemberOf(specType)
        val parameters = function.parameters.mapIndexed { index, parameter ->
            val type = resolved.parameterTypes[index]?.render() ?: parameter.type.resolve().render()
            (parameter.name?.asString() ?: "p$index") to type
        }
        val suspend = Modifier.SUSPEND in function.modifiers
        val returnType = resolved.returnType?.takeUnless { it.declaration.qualifiedName?.asString() == "kotlin.Unit" }
        val returnValue = returnType?.let { type ->
            val declaration = type.declaration as? KSClassDeclaration
            if (declaration != null && declaration.isViewModelSpec()) embedded(declaration, function).create("fakeData.child(\"$name\")")
            else values.value(type, "fakeData", name, FakeAnnotations.of(function), function)
        }
        val binds = parameters.singleOrNull()?.let { (parameter, type) ->
            listOf(mutatorNoun(name), parameter).firstOrNull { stateFields[it] == type }
        }
        val navigations = edgesBySource[edgeName(spec)].orEmpty()
            .filter { it.methodName == name }
            .distinctBy { it.targetQualifiedName }
            .mapNotNull { edge -> navigation(edge, name, suspend, stateFields, function) }
        return FakeFunction(
            name = name,
            suspend = suspend,
            parameters = parameters,
            returnType = returnType?.render(),
            returnValue = returnValue,
            binds = binds,
            navigations = navigations,
            responds = function.hasAnnotation(FAKE_RESPONDS),
        )
    }

    private fun navigation(edge: NavEdge, member: String, suspend: Boolean, stateFields: Map<String, String>, site: KSNode): FakeNavigation? {
        val destination = destinationsByName[edge.targetQualifiedName] ?: return null
        val target = target(edge.targetQualifiedName) ?: return null
        val responding = destination.responseQualifiedName != null
        if (responding && !suspend) {
            logger.warn("$member opens ${destination.simpleName}, which responds, so it must suspend; its fake only records the call", site)
            return null
        }
        val args = destination.argsQualifiedName?.let(::declaration)
        val copies = args?.argsProperties().orEmpty().filter { property ->
            val stateType = stateFields[property.simpleName.asString()] ?: return@filter false
            val argsType = property.type.resolve()
            stateType == argsType.render() || stateType == argsType.makeNotNullable().render()
        }.map { it.simpleName.asString() }
        val sites = callSites[edge.targetQualifiedName].orEmpty()
        return FakeNavigation(
            target = target,
            targetName = destination.simpleName,
            args = args?.let { "${values.argsBuilder(it)}(fakeData.child(\"$member\"))" },
            argsCopies = copies,
            source = if (sites.size > 1) "${target.interfaceType}.${target.navCap}Source.${edge.sourceEntry}" else null,
            responding = responding,
        )
    }

    /** Row type weights by data set from `@FakeRows`, or null for a list with one row type. */
    private fun rowWeights(possibleTypes: List<String>, annotations: FakeAnnotations, site: KSNode, placeholder: String?): String? {
        if (possibleTypes.size < 2) return null
        fun weights(annotation: com.google.devtools.ksp.symbol.KSAnnotation): String {
            val selected = annotation.classes("types").mapNotNull { it.qualifiedName?.asString() }
            val unknown = selected - possibleTypes.toSet()
            if (unknown.isNotEmpty()) logger.error("@FakeRows names ${unknown.joinToString()}, which the list does not hold", site)
            val given = annotation.ints("weights")
            return possibleTypes.joinToString(", ", "intArrayOf(", ")") { type ->
                val index = selected.indexOf(type)
                when {
                    index < 0 -> "0"
                    given.size == selected.size -> given[index].toString()
                    else -> "1"
                }
            }
        }
        val rows = annotations.of("FakeRows")
        // The placeholder is never chosen among the rows; it only fills a list that came out empty.
        val default = rows.firstOrNull { it.dataSet().isEmpty() }?.let(::weights)
            ?: possibleTypes.joinToString(", ", "intArrayOf(", ")") { if (it == placeholder) "0" else "1" }
        return variants("{data}", default, rows.filter { it.dataSet().isNotEmpty() }.map { it.dataSet() to weights(it) })
    }

    /** The initial value of any other abstract property. */
    private fun propertyValue(type: KSType, name: String, property: KSPropertyDeclaration): String {
        val annotations = FakeAnnotations.of(property)
        val declaration = type.declaration
        val qualifiedName = declaration.qualifiedName?.asString()
        val arguments = type.arguments.map { it.type?.resolve() }
        when (qualifiedName) {
            DELTA_LIST -> return "com.latenighthack.deltalist.mutableDeltaListOf<${type.arguments[0].render()}>()"
            SECTIONED_DELTA_LIST -> return "com.latenighthack.deltalist.operators.sectionedDeltaList<${type.arguments[0].render()}, ${type.arguments[1].render()}>(emptyList())"
            FLOW -> {
                val element = arguments.firstOrNull() ?: return "kotlinx.coroutines.flow.emptyFlow()"
                return when (element.declaration.qualifiedName?.asString()) {
                    DELTA -> "com.latenighthack.deltalist.mutableDeltaListOf<${element.arguments[0].render()}>()"
                    SECTIONED_DELTA -> "com.latenighthack.deltalist.operators.sectionedDeltaList<${element.arguments[0].render()}, ${element.arguments[1].render()}>(emptyList())"
                    else -> "kotlinx.coroutines.flow.flowOf(${values.value(element, "fakeData", name, annotations, property)})"
                }
            }
            STATE_FLOW, MUTABLE_STATE_FLOW -> {
                val element = arguments.first()!!
                return "kotlinx.coroutines.flow.MutableStateFlow(${values.value(element, "fakeData", name, annotations, property)})"
            }
        }
        if (declaration is KSTypeAlias) return values.value(type, "fakeData", name, annotations, property)
        return values.value(type, "fakeData", name, annotations, property)
    }

    /**
     * The `@Inject` constructor copied from [implementation]: its `@Assisted` parameters, each passed on
     * as the Args, a navigation target or the responder. Null when a parameter's type cannot be named,
     * in which case the implementation has no factory either.
     */
    private fun injectConstructor(implementation: KSClassDeclaration, targets: List<FakeTarget>, responds: Boolean, args: KSClassDeclaration?): FakeInject? {
        val constructor = implementation.getConstructors().firstOrNull { it.hasAnnotation(INJECT) } ?: implementation.primaryConstructor
            ?: return FakeInject(emptyList(), emptyList())
        val parameters = mutableListOf<Pair<String, String>>()
        val arguments = mutableListOf<String>()
        for (parameter in constructor.parameters.filter { it.hasAnnotation(ASSISTED) }) {
            val name = parameter.name?.asString() ?: return null
            val type = assistedParameterType(parameter, navPackage) ?: return null
            parameters += name to type
            val simple = type.substringBefore('<').substringAfterLast('.')
            when {
                args != null && type == args.qualifiedName?.asString() -> arguments += "fakeArgs = $name"
                type.startsWith(NAVIGATION_RESPONDER) -> if (responds) {
                    val response = parameter.type.resolve().arguments.firstOrNull()?.type?.resolve()
                    if (response != null) {
                        val value = values.value(response, "data", "response", FakeAnnotations.NONE, parameter)
                        arguments += "fakeResponder = $FAKE_PACKAGE.FakeResponder { data -> $name.respond($value) }"
                    }
                }
                simple.endsWith("NavigationTarget") -> {
                    val cap = simple.removeSuffix("NavigationTarget")
                    targets.filter { it.navCap == cap }.forEach { arguments += "${it.paramName} = $name" }
                }
                simple.endsWith("Navigator") -> {
                    val cap = simple.removeSuffix("Navigator")
                    val source = graph.sources.firstOrNull { it.navName.navCapOf() == cap }
                    val reached = source?.edges.orEmpty().mapNotNull { target(it.targetQualifiedName)?.navCap }.toSet()
                    targets.filter { it.navCap in reached }.forEach { arguments += "${it.paramName} = $name" }
                }
            }
        }
        return FakeInject(parameters, arguments.distinct())
    }

    companion object {
        const val NAVIGATION_PACKAGE_OPTION = "Basekit_NavigationPackage"
        const val REDIRECT_MEMBER = "redirect"
        const val LIST_MIN_OPTION = "basekit.fakes.listMin"
        const val LIST_MAX_OPTION = "basekit.fakes.listMax"
        const val VIEWMODEL_INJECT = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
        const val CODEGEN_IGNORE = "com.latenighthack.basekit.viewmodel.annotations.CodegenIgnore"
        const val GENERATED_FAKE = "$FAKE_PACKAGE.GeneratedFake"
        const val FAKE_RESPONDS = "$ANNOTATIONS_PACKAGE.FakeResponds"
        const val INJECT = "me.tatarka.inject.annotations.Inject"
        const val ASSISTED = "me.tatarka.inject.annotations.Assisted"
        const val NAVIGATION_RESPONDER = "com.latenighthack.basekit.navigation.NavigationResponder"
        const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
        const val FLOW = "kotlinx.coroutines.flow.Flow"
        const val STATE_FLOW = "kotlinx.coroutines.flow.StateFlow"
        const val MUTABLE_STATE_FLOW = "kotlinx.coroutines.flow.MutableStateFlow"
        const val DELTA = "com.latenighthack.deltalist.Delta"
        const val DELTA_LIST = "com.latenighthack.deltalist.DeltaList"
        const val SECTIONED_DELTA = "com.latenighthack.deltalist.SectionedDelta"
        const val SECTIONED_DELTA_LIST = "com.latenighthack.deltalist.SectionedDeltaList"
    }
}
