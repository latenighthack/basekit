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
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

private const val VIEWMODEL_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelSpec"
private const val VIEWMODEL_LIST_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelList"
private const val VIEWMODEL_INJECT_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelInject"
private const val VIEWMODEL_MODULE_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ViewModelModule"
private const val VIEWMODEL_INTERFACE = "com.latenighthack.basekit.viewmodel.ViewModel"
private const val TUISCREEN_ANNOTATION = "com.latenighthack.basekit.viewmodel.tui.annotations.TuiScreen"
private const val DESTINATION_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.Destination"
private const val ROUTE_ARG_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.RouteArg"
private const val NAVIGATE_TO_ANNOTATION = "com.latenighthack.basekit.navigation.annotations.NavigateTo"
private const val NAVIGATION_DESTINATION = "com.latenighthack.basekit.navigation.NavigationDestination"
private const val RESPONDING_DESTINATION = "com.latenighthack.basekit.navigation.RespondingDestination"
private const val NAVIGATION_RESPONDER = "com.latenighthack.basekit.navigation.NavigationResponder"
private const val ASSISTED_ANNOTATION = "me.tatarka.inject.annotations.Assisted"
private const val PACKAGE_OPTION = "Basekit_TuiPackage"
private const val NAV_PACKAGE_OPTION = "Basekit_NavigationPackage"
// Optional FQN of an app DI root (e.g. a runtime-built Core) the generated component takes as a
// @Component parent, so ViewModels whose deps are constructed at runtime can drive the TUI.
private const val APP_COMPONENT_OPTION = "Basekit_TuiAppComponent"

/** A `@NavigateTo` edge from a destination action to a target destination. */
private data class Edge(val methodName: String, val targetQualifiedName: String)

/** Everything the processor needs about one `@Destination` to reconstruct the navigation graph. */
private data class DestNode(
    val qualifiedName: String,
    val simpleName: String,
    val packageName: String,
    val navName: String,
    val argsQualifiedName: String?,
    val responseQualifiedName: String?,
    val routeArgs: List<String>,
    val edges: List<Edge>,
)

/**
 * Reads `@ViewModelSpec`s bound with `@TuiScreen` (and the `@Destination` navigation graph) from the
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

        // App-supplied kotlin-inject provider interfaces the generated @Component should include, so
        // dependencies of @ViewModelInject ViewModels resolve from the graph.
        val moduleQualifiedNames = declarations
            .filter { it.hasAnnotation(VIEWMODEL_MODULE_ANNOTATION) }
            .mapNotNull { it.qualifiedName?.asString() }

        // Two ViewModels with the same simple name would emit the same `<Name>Screen` file twice — a
        // FileAlreadyExistsException with no hint at the cause. Fail with a clear message instead.
        val screenCollisions = screens.groupBy { it.screenClassName }.filterValues { it.size > 1 }
        if (screenCollisions.isNotEmpty()) {
            screenCollisions.forEach { (screenClass, group) ->
                logger.error(
                    "@TuiScreen ViewModels ${group.map { it.vmQualifiedName }.sorted()} both generate " +
                        "`$screenClass`; give them distinct simple names",
                )
            }
            return emptyList()
        }

        // Generate here (not in finish()): files emitted in finish() are terminal and would never be
        // handed to kotlin-inject's processor, so its `create()` for our @Component would never appear.
        if (!generated && screens.isNotEmpty()) {
            generated = true
            val dependencies = Dependencies(aggregating = true)
            TuiScreenGenerator(codeGenerator, dependencies, rootPackage).generate(screens)
            TuiNavigatorGenerator(codeGenerator, dependencies, rootPackage).generate(screens)
            val appComponent = options[APP_COMPONENT_OPTION]?.takeIf { it.isNotEmpty() }
            TuiComponentGenerator(codeGenerator, dependencies, rootPackage, appComponent).generate(screens, moduleQualifiedNames)
        }
        return emptyList()
    }

    private fun buildDestNode(decl: KSClassDeclaration): DestNode {
        val superTypes = decl.getAllSuperTypes().toList()
        fun superTypeArgument(fqn: String, index: Int): KSClassDeclaration? =
            superTypes.firstOrNull { it.declaration.qualifiedName?.asString() == fqn }
                ?.arguments?.getOrNull(index)?.type?.resolve()?.declaration as? KSClassDeclaration

        // Args comes from RespondingDestination<Args, R> when present (concrete on the declaration), else
        // from NavigationDestination<Args>; the RespondingDestination's second arg is the response type.
        val argsDecl = superTypeArgument(RESPONDING_DESTINATION, 0)
            ?: superTypeArgument(NAVIGATION_DESTINATION, 0)
        val responseDecl = superTypeArgument(RESPONDING_DESTINATION, 1)

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
            responseQualifiedName = responseDecl?.qualifiedName?.asString(),
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

        val implDecl = resolveImplementation(vm, vmQn, declarations)
        if (implDecl == null) {
            logger.warn("@TuiScreen $vmName has no concrete implementation in the scanned package; skipping")
            return null
        }
        val implQn = implDecl.qualifiedName?.asString() ?: return null
        val injected = implDecl.hasAnnotation(VIEWMODEL_INJECT_ANNOTATION)

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
                responseType = target.responseQualifiedName,
            )
        }

        // Suspend methods split by arity: zero-arg ones become key-bound actions; single Bool/String-arg
        // ones become mutations the TUI prompts for. Keys are assigned across both so they never collide.
        val suspendFns = vm.getDeclaredFunctions()
            .filter { it.modifiers.contains(Modifier.SUSPEND) && !it.simpleName.asString().startsWith("<") }
            .toList()
        val actionNames = suspendFns.filter { it.parameters.isEmpty() }.map { it.simpleName.asString() }
        val mutationFns = suspendFns.mapNotNull { fn ->
            fn.parameters.singleOrNull()?.mutationParamKind()?.let { fn.simpleName.asString() to it }
        }
        val keys = assignKeys(actionNames + mutationFns.map { it.first })
        val actions = actionNames.map { Action(it, keys.getValue(it)) }
        val mutations = mutationFns.map { (name, kind) -> Mutation(name, keys.getValue(name), kind) }

        // The impl's `@Assisted` params (in ctor order) — each supplied by the component per screen build.
        // Classified by type so screenForDestination hands over the right value. A screen may take more
        // than one (e.g. navigation args AND its per-screen navigator). A navigator param on a screen with
        // no outbound edges resolves to the shared close target (declared type).
        val assisted = implDecl.primaryConstructor?.parameters.orEmpty()
            .filter { p -> p.annotations.any { it.qualifiedName() == ASSISTED_ANNOTATION } }
            .map { p ->
                val qn = p.type.resolve().declaration.qualifiedName?.asString()
                when (qn) {
                    NAVIGATION_RESPONDER -> AssistedParam(AssistedKind.RESPONDER, "NavigationResponder<${dest.responseQualifiedName}>")
                    dest.argsQualifiedName -> AssistedParam(AssistedKind.ARGS, dest.argsQualifiedName!!)
                    else -> AssistedParam(AssistedKind.NAVIGATOR, navigatorInterface ?: qn ?: "kotlin.Any")
                }
            }

        return ScreenInfo(
            vmSimpleName = vmName,
            vmQualifiedName = vmQn,
            implQualifiedName = implQn,
            injected = injected,
            stateQualifiedName = stateDecl.qualifiedName!!.asString(),
            stateProps = stateDecl.stateProps(),
            actions = actions,
            mutations = mutations,
            list = list,
            destQualifiedName = dest.qualifiedName,
            assisted = assisted,
            navigatorInterface = navigatorInterface,
            navMethods = navMethods,
        )
    }

    private fun resolveImplementation(vm: KSClassDeclaration, vmQn: String, declarations: List<KSClassDeclaration>): KSClassDeclaration? {
        val override = vm.classArgument(TUISCREEN_ANNOTATION, "implementation")?.declaration as? KSClassDeclaration
        if (override != null && override.qualifiedName?.asString() != "kotlin.Unit") return override
        return declarations.firstOrNull { candidate ->
            candidate.classKind == ClassKind.CLASS &&
                !candidate.modifiers.contains(Modifier.ABSTRACT) &&
                candidate.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == vmQn }
        }
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

        // Enter on a row invokes the element's zero-arg suspend action (prefer one named `onSelected`).
        // Whatever it does — including navigating via the element's injected navigator — is the
        // element's concern, not the screen's.
        val zeroArgActions = elementDecl.getDeclaredFunctions()
            .filter { it.modifiers.contains(Modifier.SUSPEND) && it.parameters.isEmpty() && !it.simpleName.asString().startsWith("<") }
            .map { it.simpleName.asString() }
            .toList()
        val selectionAction = zeroArgActions.firstOrNull { it == "onSelected" } ?: zeroArgActions.firstOrNull()

        return ListInfo(
            propertyName = prop.simpleName.asString(),
            elementQualifiedName = elementDecl.qualifiedName!!.asString(),
            elementStateProps = elementState?.stateProps().orEmpty(),
            selectionAction = selectionAction,
        )
    }

    private fun KSClassDeclaration.stateProps(): List<StateProp> =
        getDeclaredProperties().mapNotNull { prop ->
            val type = prop.type.resolve().declaration
            StateProp(prop.simpleName.asString(), type.simpleName.asString())
        }.toList()

    /** The Bool/String argument kind of a mutation parameter, or null for any other type (not exposed). */
    private fun KSValueParameter.mutationParamKind(): MutationParamKind? =
        when (type.resolve().declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> MutationParamKind.BOOL
            "kotlin.String" -> MutationParamKind.STRING
            else -> null
        }

}

/**
 * Assigns a distinct trigger key to each method name (preferring a letter of the name past the `on`
 * prefix), returned as a name -> key map. `q` is pre-reserved so no binding shadows the quit key.
 * A name with no free letter falls back to `'?'`. Top-level + `internal` so it is unit-testable.
 */
internal fun assignKeys(names: List<String>): Map<String, Char> {
    val used = mutableSetOf('q')
    return names.associateWith { name ->
        val base = name.removePrefix("on")
        val ch = base.firstOrNull { it.isLetter() && it.lowercaseChar() !in used }?.lowercaseChar()
            ?: name.firstOrNull { it.isLetter() && it.lowercaseChar() !in used }?.lowercaseChar()
            ?: '?'
        used.add(ch)
        ch
    }
}
