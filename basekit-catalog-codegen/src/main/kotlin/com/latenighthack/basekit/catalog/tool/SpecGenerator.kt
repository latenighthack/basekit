package com.latenighthack.basekit.catalog.tool

/**
 * Writes the `@ViewModelSpec` interfaces. A root's file (a screen, or a component nothing embeds)
 * holds the root, every component only it embeds, and the marker interfaces their lists use. A
 * component that several roots embed gets a file of its own.
 */
class SpecGenerator(private val analysis: CatalogAnalysis, private val types: TypeResolver) {

    fun files(): List<GeneratedFile> {
        val rootFiles = analysis.roots.map { root ->
            val owned = analysis.embeddedComponents(root).filter { analysis.owner(it)?.name == root.name }
            file(root, listOf(root) + owned)
        }
        val shared = analysis.components
            .filter { it !in analysis.roots && analysis.owner(it) == null }
            .map { file(it, listOf(it)) }
        return rootFiles + shared
    }

    private fun file(primary: CatalogVm, vms: List<CatalogVm>): GeneratedFile {
        val imports = mutableSetOf<String>()
        val declarations = vms.flatMap { vm ->
            listOf(declaration(vm, imports)) + analysis.markers.values.filter { it.owner.name == vm.name }.map { marker ->
                "interface ${marker.interfaceName}"
            }
        }
        val content = kotlinFile(primary.packageName, HEADER, imports, declarations.joinToString("\n\n"))
        return GeneratedFile(sourcePath(primary.packageName, primary.specName), content, overwrite = true)
    }

    private fun declaration(vm: CatalogVm, imports: MutableSet<String>): String = buildString {
        val spec = vm.specName
        val navigation = vm.navigation
        if (navigation != null) {
            imports += Symbols.DESTINATION
            val derived = spec.toCatalogName()
            if (derived == vm.name) appendLine("@Destination") else appendLine("@Destination(navName = ${kotlinString(vm.name.toNavName())})")
            if (vm.redirectsTo.isNotEmpty()) {
                imports += Symbols.REDIRECTS_TO
                appendLine("@RedirectsTo(${vm.redirectsTo.joinToString(", ") { "${specReference(it, imports)}::class" }})")
            }
        }
        imports += Symbols.VIEWMODEL_SPEC
        appendLine("@ViewModelSpec")

        val supertypes = buildList {
            if (navigation != null) {
                if (navigation.response != null) {
                    imports += Symbols.RESPONDING_DESTINATION
                    add("RespondingDestination<$spec.Args, ${types.render(navigation.response, imports)}>")
                } else {
                    imports += Symbols.NAVIGATION_DESTINATION
                    add("NavigationDestination<$spec.Args>")
                }
            }
            imports += Symbols.VIEWMODEL
            add("ViewModel<$spec.State>")
            analysis.rowMarkers[vm.name].orEmpty().forEach { marker ->
                imports += marker.qualifiedName
                add(marker.interfaceName)
            }
        }
        appendLine("interface $spec :")
        appendLine(supertypes.joinToString(",\n") { "    $it" } + " {")

        val members = buildList {
            navigation?.let { add(args(it, imports)) }
            add(state(vm, imports))
            vm.lists.filter { it.kind == ListKind.SECTIONED }.forEach { add(header(it)) }
            vm.identity?.let {
                imports += Symbols.VIEWMODEL_IDENTITY
                add("@ViewModelIdentity\nval ${identifier(it)}: String")
            }
            vm.lists.forEach { add(list(vm, it, imports)) }
            vm.children.forEach { child ->
                imports += Symbols.CHILD_VIEWMODEL
                add("@ChildViewModel\nval ${identifier(child.name)}: ${specReference(child.type, imports)}")
            }
            vm.actions.forEach { add(function(it.name, null, it.navigatesTo, imports)) }
            vm.mutations.forEach { add(function(it.name, it.param, it.navigatesTo, imports)) }
        }
        appendLine(members.joinToString("\n\n").indent(4))
        append("}")
    }

    private fun args(navigation: Navigation, imports: MutableSet<String>): String = buildString {
        imports += Symbols.NAVIGATOR_ARGS
        navigation.route?.let {
            imports += Symbols.ROUTE
            appendLine("@Route(${kotlinString(it)})")
        }
        navigation.routeArgs.filter { arg -> navigation.args.none { it.name == arg } }.forEach {
            throw CatalogException("route_args: \"$it\" is not one of the args")
        }
        if (navigation.args.isEmpty()) {
            append("class Args : NavigatorArgs()")
            return@buildString
        }
        val fields = navigation.args.map { it to types.render(it.type, imports) }
        appendLine("class Args() : NavigatorArgs() {")
        appendLine("    constructor(${fields.joinToString(", ") { (field, type) -> "${identifier(field.name)}: $type" }}) : this() {")
        fields.forEach { (field, _) -> appendLine("        this.${identifier(field.name)} = ${identifier(field.name)}") }
        appendLine("    }")
        fields.forEach { (field, type) ->
            appendLine()
            if (field.name in navigation.routeArgs) {
                imports += Symbols.ROUTE_ARG
                appendLine("    @RouteArg")
            }
            appendLine("    var ${identifier(field.name)}: $type by storedProperty()")
        }
        append("}")
    }

    private fun state(vm: CatalogVm, imports: MutableSet<String>): String {
        if (vm.state.isEmpty()) return "class State"
        return vm.state.joinToString(",\n", "data class State(\n", ",\n)") { field ->
            "    val ${identifier(field.name)}: ${types.render(field.type, imports)}"
        }
    }

    private fun header(list: ListDef): String {
        val name = headerName(list)
        if (list.sections.isEmpty()) return "sealed interface $name"
        return list.sections.joinToString("\n", "sealed interface $name {\n", "\n}") { "    data object ${identifier(it)} : $name" }
    }

    private fun list(vm: CatalogVm, list: ListDef, imports: MutableSet<String>): String {
        val element = elementReference(list.element, imports)
        return when (list.kind) {
            ListKind.FLAT -> {
                val rows = list.rows.ifEmpty {
                    if (analysis.isComponent(list.element)) listOf(list.element)
                    else throw CatalogException("${vm.name}.lists.${list.name}: a flat list needs at least one row")
                }
                imports += Symbols.VIEWMODEL_LIST
                imports += Symbols.FLOW
                imports += Symbols.DELTA
                val possible = rows.joinToString(", ") { "${specReference(it, imports)}::class" }
                "@ViewModelList($possible)\nval ${identifier(list.name)}: Flow<Delta<$element>>"
            }
            ListKind.SECTIONED -> {
                imports += Symbols.SECTIONED_DELTA_LIST
                "val ${identifier(list.name)}: SectionedDeltaList<${vm.specName}.${headerName(list)}, $element>"
            }
        }
    }

    private fun function(name: String, param: Field?, targets: List<String>, imports: MutableSet<String>): String = buildString {
        targets.forEach { target ->
            imports += Symbols.NAVIGATE_TO
            appendLine("@NavigateTo(${specReference(target, imports)}::class)")
        }
        val parameter = param?.let { "${identifier(it.name)}: ${types.render(it.type, imports)}" }.orEmpty()
        append("suspend fun ${identifier(name)}($parameter)")
    }

    private fun specReference(name: String, imports: MutableSet<String>): String {
        val vm = analysis.vm(name)
        imports += vm.spec
        return vm.specName
    }

    private fun elementReference(element: String, imports: MutableSet<String>): String {
        analysis.markers[element]?.let {
            imports += it.qualifiedName
            return it.interfaceName
        }
        return specReference(element, imports)
    }

    companion object {
        const val HEADER = "// Generated by basekit-catalog-codegen from catalog.yaml. Rewritten on every run: edit the catalog instead."

        /** The sealed section header nested in a spec for a sectioned list: `sections` -> `SectionsHeader`. */
        fun headerName(list: ListDef): String = list.name.capitalized() + "Header"
    }
}
