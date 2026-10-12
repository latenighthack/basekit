package com.latenighthack.basekit.catalog.tool

/** One `@Assisted` constructor parameter of a screen impl; the component factory takes the same, in order. */
data class AssistedParam(val name: String, val type: String, val imports: Set<String>)

/**
 * Writes starting-point implementations. Each screen gets `@ViewModelInject class <Impl>` with its
 * args, navigator (`CloseNavigationTarget` when nothing on the screen navigates) and responder
 * assisted; every component the screen embeds is a private inner class
 * so its actions reach the screen's navigator. Lists hold one instance of each row type (in every
 * section), navigating actions call the navigator, and binding mutations update State.
 */
class ImplGenerator(
    private val analysis: CatalogAnalysis,
    private val types: TypeResolver,
    private val navigationPackage: String,
) {

    fun files(): List<GeneratedFile> =
        analysis.screens.map { screenFile(it) } +
            analysis.rootComponents.map { standaloneFile(it) }

    /** The assisted parameters of [screen]'s impl, in constructor order. */
    fun assistedParams(screen: CatalogVm): List<AssistedParam> {
        val navigation = screen.navigation ?: return emptyList()
        return buildList {
            if (navigation.args.isNotEmpty()) add(AssistedParam("args", "${screen.specName}.Args", setOf(screen.spec)))
            if (analysis.needsNavigator(screen)) {
                add(AssistedParam("navigator", "${screen.name}Navigator", setOf("$navigationPackage.${screen.name}Navigator")))
            } else if (analysis.reachableEdges.isNotEmpty()) {
                // basekit generates CloseNavigationTarget only when the graph has an edge.
                add(AssistedParam("navigator", "CloseNavigationTarget", setOf("$navigationPackage.CloseNavigationTarget")))
            }
            navigation.response?.let { response ->
                val imports = mutableSetOf(Symbols.NAVIGATION_RESPONDER)
                add(AssistedParam("responder", "NavigationResponder<${types.render(response, imports)}>", imports))
            }
        }
    }

    private fun screenFile(screen: CatalogVm): GeneratedFile {
        val imports = mutableSetOf<String>()
        val params = assistedParams(screen)
        val hasNavigator = analysis.needsNavigator(screen)
        params.forEach { imports += it.imports }
        val constructor = if (params.isEmpty()) {
            imports += Symbols.INJECT
            "@Inject constructor()"
        } else {
            imports += Symbols.INJECT
            imports += Symbols.ASSISTED
            params.joinToString(",\n", "@Inject constructor(\n", ",\n)") { "    @Assisted private val ${it.name}: ${it.type}" }
        }
        val inner = analysis.embeddedComponents(screen).map { innerClass(it, hasNavigator, imports) }
        val body = classDeclaration(screen, "@ViewModelInject\nclass ${implName(screen.specName)} $constructor", hasNavigator, inner, imports)
        imports += Symbols.VIEWMODEL_INJECT
        return GeneratedFile(
            sourcePath(screen.packageName, implName(screen.specName)),
            kotlinFile(screen.packageName, HEADER, imports, body),
            overwrite = false,
        )
    }

    /** A component nothing embeds: a top-level impl without a navigator. */
    private fun standaloneFile(component: CatalogVm): GeneratedFile {
        val imports = mutableSetOf(Symbols.VIEWMODEL_INJECT, Symbols.INJECT)
        val inner = analysis.embeddedComponents(component).map { innerClass(it, false, imports) }
        val body = classDeclaration(component, "@ViewModelInject\nclass ${implName(component.specName)} @Inject constructor()", false, inner, imports)
        return GeneratedFile(
            sourcePath(component.packageName, implName(component.specName)),
            kotlinFile(component.packageName, HEADER, imports, body),
            overwrite = false,
        )
    }

    private fun innerClass(component: CatalogVm, hasNavigator: Boolean, imports: MutableSet<String>): String =
        classDeclaration(component, "private inner class ${implName(component.specName)}", hasNavigator, emptyList(), imports)

    /** `<head> : Spec, StatefulViewModel<Spec.State>(initial) { members; nested }`. */
    private fun classDeclaration(
        vm: CatalogVm,
        head: String,
        hasNavigator: Boolean,
        nested: List<String>,
        imports: MutableSet<String>,
    ): String {
        imports += vm.spec
        imports += Symbols.STATEFUL_VIEWMODEL
        val spec = vm.specName
        val initial = if (vm.state.isEmpty()) "$spec.State()" else types.constructorCall("$spec.State", vm.state, imports)
        val members = members(vm, hasNavigator, imports) + nested
        if (members.isEmpty()) return "$head : $spec, StatefulViewModel<$spec.State>($initial)"
        return buildString {
            appendLine("$head : $spec, StatefulViewModel<$spec.State>($initial) {")
            appendLine(members.joinToString("\n\n").indent(4))
            append("}")
        }
    }

    private fun members(vm: CatalogVm, hasNavigator: Boolean, imports: MutableSet<String>): List<String> = buildList {
        vm.identity?.let { add("override val ${identifier(it)}: String = ${kotlinString(vm.name)}") }
        vm.lists.forEach { add(list(vm, it, imports)) }
        vm.children.forEach { child ->
            val component = analysis.vm(child.type)
            imports += component.spec
            add("override val ${identifier(child.name)}: ${component.specName} = ${implName(component.specName)}()")
        }
        vm.actions.forEach { action ->
            add(function(vm, action.name, null, null, if (hasNavigator) action.navigatesTo else emptyList(), imports))
        }
        vm.mutations.forEach { mutation ->
            val bound = mutation.binds?.takeIf { binds -> vm.state.any { it.name == binds && it.type == mutation.param.type } }
            add(function(vm, mutation.name, mutation.param, bound, if (hasNavigator) mutation.navigatesTo else emptyList(), imports))
        }
        if (hasNavigator) vm.redirectsTo.forEach { add(redirect(vm, analysis.vm(it), imports)) }
    }

    /**
     * `redirectTo<Target>()`: the navigator call for one `@RedirectsTo` target. Nothing calls it; the
     * ViewModel decides when to replace itself.
     */
    private fun redirect(vm: CatalogVm, target: CatalogVm, imports: MutableSet<String>): String {
        val call = navigate(vm, REDIRECT_METHOD, target, imports)
        val body = if ("current." in call) "withState { current ->\n${call.indent(4)}\n}" else call
        return "/** Replaces this screen with ${target.name}; call it when the ViewModel decides to redirect. */\n" +
            "suspend fun redirectTo${target.name}() {\n${body.indent(4)}\n}"
    }

    private fun list(vm: CatalogVm, list: ListDef, imports: MutableSet<String>): String {
        val element = analysis.markers[list.element]?.let {
            imports += it.qualifiedName
            it.interfaceName
        } ?: analysis.vm(list.element).also { imports += it.spec }.specName
        val rows = list.rows.ifEmpty { listOf(list.element).filter { analysis.isComponent(it) } }
        imports += Symbols.TO_DELTA_LIST
        val instances = "listOf<$element>(${rows.joinToString(", ") { "${implName(analysis.vm(it).specName)}()" }}).toDeltaList()"
        return when (list.kind) {
            ListKind.FLAT -> {
                imports += Symbols.FLOW
                imports += Symbols.DELTA
                "override val ${identifier(list.name)}: Flow<Delta<$element>> = $instances"
            }
            ListKind.SECTIONED -> {
                imports += Symbols.SECTIONED_DELTA_LIST
                imports += Symbols.SECTIONED_DELTA_LIST_OF
                val header = "${vm.specName}.${SpecGenerator.headerName(list)}"
                val type = "SectionedDeltaList<$header, $element>"
                if (list.sections.isEmpty()) {
                    "override val ${identifier(list.name)}: $type = sectionedDeltaList<$header, $element>()"
                } else {
                    list.sections.joinToString(",\n", "override val ${identifier(list.name)}: $type = sectionedDeltaList(\n", ",\n)") {
                        "    $header.${identifier(it)} to $instances"
                    }
                }
            }
        }
    }

    private fun function(
        vm: CatalogVm,
        name: String,
        param: Field?,
        binds: String?,
        targets: List<String>,
        imports: MutableSet<String>,
    ): String {
        val signature = "override suspend fun ${identifier(name)}(" +
            (param?.let { "${identifier(it.name)}: ${types.render(it.type, imports)}" } ?: "") + ")"
        val statements = buildList {
            binds?.let { add("update { copy(${identifier(it)} = ${identifier(param!!.name)}) }") }
            targets.forEach { add(navigate(vm, name, analysis.vm(it), imports)) }
        }
        if (statements.isEmpty()) return "$signature = Unit"
        // Navigation args read same-named State properties, which are only available inside withState.
        val readsState = statements.any { "current." in it }
        val body = if (readsState) {
            "withState { current ->\n${statements.joinToString("\n").indent(4)}\n}"
        } else {
            statements.joinToString("\n")
        }
        return "$signature {\n${body.indent(4)}\n}"
    }

    /** `navigator.navigateTo<Target>(<Target>.Args(…), <Target>NavigationTarget.<Target>Source.<ENTRY>)`. */
    private fun navigate(source: CatalogVm, method: String, target: CatalogVm, imports: MutableSet<String>): String {
        val navigation = target.navigation!!
        imports += target.spec
        val args = if (navigation.args.isEmpty()) {
            "${target.specName}.Args()"
        } else {
            navigation.args.joinToString(", ", "${target.specName}.Args(", ")") { arg ->
                val fromState = source.state.any { it.name == arg.name && it.type == arg.type }
                val value = if (fromState) "current.${identifier(arg.name)}" else types.default(arg.type, imports)
                "${identifier(arg.name)} = $value"
            }
        }
        val arguments = mutableListOf(args)
        if (analysis.callSites(target).size > 1) {
            val targetType = "${target.name}NavigationTarget"
            imports += "$navigationPackage.$targetType"
            arguments += "$targetType.${target.name}Source.${analysis.sourceEntry(Edge(source, method, target))}"
        }
        return "navigator.navigateTo${target.name}(${arguments.joinToString(", ")})"
    }

    companion object {
        const val HEADER = "// Generated by basekit-catalog-codegen as a starting point. It is not rewritten once it exists: edit freely."
    }
}
