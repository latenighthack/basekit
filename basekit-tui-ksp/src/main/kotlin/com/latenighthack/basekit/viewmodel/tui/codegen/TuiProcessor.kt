package com.latenighthack.basekit.viewmodel.tui.codegen

import com.google.devtools.ksp.KspExperimental
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
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

private const val VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModel"
private const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
private const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
private const val TUISCREEN_ANNOTATION = "com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen"
private const val TUINAVIGATESTO_ANNOTATION = "com.latenighthack.basekit.viewmodel.tui.annotations.TuiNavigatesTo"
private const val DESTINATION_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.Destination"
private const val ROUTE_ARG_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.RouteArg"
private const val NAVIGATE_TO_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.NavigateTo"
private const val NAVIGATION_DESTINATION = "com.latenighthack.basekit.navigation.NavigationDestination"
private const val PACKAGE_OPTION = "Basekit_TuiPackage"
private const val NAV_PACKAGE_OPTION = "Basekit_NavigationPackage"

/** A `@NavigateTo` edge from a destination action to a target destination. */
private data class Edge(val methodName: String, val targetQualifiedName: String)

/** Everything the processor needs about one `@Destination` to reconstruct the navigation graph. */
private data class DestNode(
    val qualifiedName: String,
    val simpleName: String,
    val packageName: String,
    val navName: String,
    val argsQualifiedName: String?,
    val routeArgs: List<String>,
    val edges: List<Edge>,
)

/**
 * Reads `@ViewModel`s bound with `@TuiScreen` (and the `@Destination` navigation graph) from the
 * configured `Basekit_TuiPackage` — which lives in a *dependency*, so it enumerates via
 * [Resolver.getDeclarationsFromPackage] rather than `getSymbolsWithAnnotation`. It then emits TamboUI
 * screens, a back-stack navigator wired to the navigation slice, and the kotlin-inject component.
 */
class TuiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private val screens = mutableListOf<ScreenInfo>()
    private var rootPackage: String = ""
    private var collected = false
    private var generated = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (collected) return emptyList()
        collected = true

        val pkg = options[PACKAGE_OPTION]?.takeIf { it.isNotEmpty() }
        if (pkg == null) {
            logger.warn("basekit-tui: no $PACKAGE_OPTION option set; nothing to generate")
            return emptyList()
        }
        rootPackage = pkg

        val declarations = resolver.getDeclarationsFromPackage(pkg)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val destinations = declarations
            .filter { it.hasAnnotation(DESTINATION_ANNOTATION) }
            .associate { it.qualifiedName!!.asString() to buildDestNode(it) }

        // Call-site counts across the whole graph decide whether a target's navigateTo… takes a source.
        val callSites = LinkedHashMap<String, MutableSet<String>>()
        for (dest in destinations.values) {
            for (edge in dest.edges) {
                callSites.getOrPut(edge.targetQualifiedName) { linkedSetOf() }
                    .add("${dest.navName}#${edge.methodName}")
            }
        }
        val hasSource: (String) -> Boolean = { (callSites[it]?.size ?: 0) > 1 }

        val navPackage = options[NAV_PACKAGE_OPTION]?.takeIf { it.isNotEmpty() }

        val viewModels = declarations.filter {
            it.hasAnnotation(VIEWMODEL_ANNOTATION) && it.hasAnnotation(TUISCREEN_ANNOTATION)
        }

        for (vm in viewModels) {
            buildScreen(vm, declarations, destinations, hasSource, navPackage)?.let(screens::add)
        }

        // Generate here (not in finish()): files emitted in finish() are terminal and would never be
        // handed to kotlin-inject's processor, so its `create()` for our @Component would never appear.
        if (!generated && screens.isNotEmpty()) {
            generated = true
            val dependencies = Dependencies(aggregating = true)
            TuiScreenGenerator(codeGenerator, dependencies, rootPackage).generate(screens)
            TuiComponentGenerator(codeGenerator, dependencies, rootPackage).generate(screens)
        }
        return emptyList()
    }

    private fun buildDestNode(decl: KSClassDeclaration): DestNode {
        val argsDecl = decl.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == NAVIGATION_DESTINATION }
            ?.arguments?.firstOrNull()?.type?.resolve()?.declaration as? KSClassDeclaration

        val routeArgs = argsDecl?.getDeclaredProperties()
            ?.filter { it.hasAnnotation(ROUTE_ARG_ANNOTATION) }
            ?.map { it.simpleName.asString() }
            ?.toList().orEmpty()

        val edges = decl.getDeclaredFunctions().flatMap { fn ->
            fn.annotations.filter { it.qualifiedName() == NAVIGATE_TO_ANNOTATION }.mapNotNull { ann ->
                val target = ann.arguments.firstOrNull { it.name?.asString() == "target" }?.value as? KSType
                target?.declaration?.qualifiedName?.asString()?.let { Edge(fn.simpleName.asString(), it) }
            }
        }.toList()

        return DestNode(
            qualifiedName = decl.qualifiedName!!.asString(),
            simpleName = decl.simpleName.asString(),
            packageName = decl.packageName.asString(),
            navName = decl.simpleName.asString().toDestinationNavName(),
            argsQualifiedName = argsDecl?.qualifiedName?.asString(),
            routeArgs = routeArgs,
            edges = edges,
        )
    }

    private fun buildScreen(
        vm: KSClassDeclaration,
        declarations: List<KSClassDeclaration>,
        destinations: Map<String, DestNode>,
        hasSource: (String) -> Boolean,
        navPackageOption: String?,
    ): ScreenInfo? {
        val vmName = vm.simpleName.asString()
        val vmQn = vm.qualifiedName?.asString() ?: return null

        val stateDecl = vm.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == VIEWMODEL_INTERFACE }
            ?.arguments?.firstOrNull()?.type?.resolve()?.declaration as? KSClassDeclaration
        if (stateDecl == null) {
            logger.warn("@TuiScreen $vmName does not implement ViewModel<State>; skipping")
            return null
        }

        val destType = vm.classArgument(TUISCREEN_ANNOTATION, "destination")
        val destQn = destType?.declaration?.qualifiedName?.asString()
        val dest = destQn?.let { destinations[it] }
        if (dest == null) {
            logger.warn("@TuiScreen $vmName destination is not a @Destination in the scanned package; skipping")
            return null
        }

        val implQn = resolveImplementation(vm, vmQn, declarations)
        if (implQn == null) {
            logger.warn("@TuiScreen $vmName has no concrete implementation in the scanned package; skipping")
            return null
        }

        val navPackage = navPackageOption ?: dest.packageName

        val list = vm.getDeclaredProperties()
            .firstOrNull { it.hasAnnotation(VIEWMODEL_LIST_ANNOTATION) }
            ?.let { buildList(it) }

        val outboundTargets = dest.edges.map { it.targetQualifiedName }.distinct()

        val navigatorInterface = outboundTargets
            .takeIf { it.isNotEmpty() }
            ?.let { "$navPackage.${dest.navName.toUpperCamelCase()}Navigator" }

        val navMethods = outboundTargets.map { targetQn ->
            val target = destinations.getValue(targetQn)
            val cap = target.navName.toUpperCamelCase()
            NavMethod(
                methodName = "navigateTo$cap",
                targetDestQualifiedName = targetQn,
                argsType = target.argsQualifiedName,
                sourceType = if (hasSource(targetQn)) "$navPackage.${cap}NavigationTarget.${cap}Source" else null,
            )
        }

        val rowNav = if (list != null) buildRowNav(vm, dest, list, destinations, hasSource, navPackage) else null

        return ScreenInfo(
            vmSimpleName = vmName,
            vmQualifiedName = vmQn,
            implQualifiedName = implQn,
            stateQualifiedName = stateDecl.qualifiedName!!.asString(),
            stateProps = stateDecl.stateProps(),
            actions = assignKeys(
                vm.getDeclaredFunctions()
                    .filter { it.modifiers.contains(Modifier.SUSPEND) && !it.simpleName.asString().startsWith("<") && it.parameters.isEmpty() }
                    .map { it.simpleName.asString() }
                    .toList()
            ),
            list = list,
            destQualifiedName = dest.qualifiedName,
            navigatorInterface = navigatorInterface,
            navMethods = navMethods,
            rowNav = rowNav,
        )
    }

    private fun resolveImplementation(vm: KSClassDeclaration, vmQn: String, declarations: List<KSClassDeclaration>): String? {
        val override = vm.classArgument(TUISCREEN_ANNOTATION, "implementation")?.declaration?.qualifiedName?.asString()
        if (override != null && override != "kotlin.Unit") return override
        return declarations.firstOrNull { candidate ->
            candidate.classKind == ClassKind.CLASS &&
                !candidate.modifiers.contains(Modifier.ABSTRACT) &&
                candidate.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == vmQn }
        }?.qualifiedName?.asString()
    }

    private fun buildList(prop: KSPropertyDeclaration): ListInfo? {
        // Property type is Flow<Delta<ElementVm>>.
        val elementDecl = prop.type.resolve()
            .arguments.firstOrNull()?.type?.resolve()          // Delta<X>
            ?.arguments?.firstOrNull()?.type?.resolve()        // X
            ?.declaration as? KSClassDeclaration ?: return null
        val elementState = elementDecl.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == VIEWMODEL_INTERFACE }
            ?.arguments?.firstOrNull()?.type?.resolve()?.declaration as? KSClassDeclaration
        return ListInfo(
            propertyName = prop.simpleName.asString(),
            elementQualifiedName = elementDecl.qualifiedName!!.asString(),
            elementStateProps = elementState?.stateProps().orEmpty(),
        )
    }

    private fun buildRowNav(
        vm: KSClassDeclaration,
        dest: DestNode,
        list: ListInfo,
        destinations: Map<String, DestNode>,
        hasSource: (String) -> Boolean,
        navPackage: String,
    ): RowNav? {
        val listProp = vm.getDeclaredProperties().firstOrNull { it.hasAnnotation(VIEWMODEL_LIST_ANNOTATION) }
        val explicitTarget = listProp?.classArgument(TUINAVIGATESTO_ANNOTATION, "destination")
            ?.declaration?.qualifiedName?.asString()
        val outbound = dest.edges.map { it.targetQualifiedName }.distinct()
        val targetQn = explicitTarget ?: outbound.singleOrNull() ?: return null
        val target = destinations[targetQn] ?: return null
        val cap = target.navName.toUpperCamelCase()

        val sourceConstant = if (hasSource(targetQn)) {
            val explicitSource = listProp?.stringArgument(TUINAVIGATESTO_ANNOTATION, "source")?.takeIf { it.isNotEmpty() }
            val constant = explicitSource ?: dest.edges.firstOrNull { it.targetQualifiedName == targetQn }
                ?.let { "${dest.navName.uppercase()}_${it.methodName.toUpperSnakeCase()}" }
            constant?.let { "$navPackage.${cap}NavigationTarget.${cap}Source.$it" }
        } else {
            null
        }

        val elementStateNames = list.elementStateProps.map { it.name }.toSet()
        val argAssignments = target.routeArgs.map { arg ->
            if (arg in elementStateNames) arg to "item.initialState.$arg" else arg to "\"\""
        }

        return RowNav(
            methodName = "navigateTo$cap",
            argsType = target.argsQualifiedName,
            sourceConstant = sourceConstant,
            argAssignments = argAssignments,
        )
    }

    private fun KSClassDeclaration.stateProps(): List<StateProp> =
        getDeclaredProperties().mapNotNull { prop ->
            val type = prop.type.resolve().declaration
            StateProp(prop.simpleName.asString(), type.simpleName.asString())
        }.toList()

    private fun assignKeys(names: List<String>): List<Action> {
        val used = mutableSetOf<Char>()
        return names.map { name ->
            val base = name.removePrefix("on")
            val ch = base.firstOrNull { it.isLetter() && it.lowercaseChar() !in used }?.lowercaseChar()
                ?: name.firstOrNull { it.isLetter() }?.lowercaseChar()
                ?: '?'
            used.add(ch)
            Action(name, ch)
        }
    }

}
