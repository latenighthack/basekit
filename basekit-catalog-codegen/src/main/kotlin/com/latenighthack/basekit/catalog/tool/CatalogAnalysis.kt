package com.latenighthack.basekit.catalog.tool

/**
 * A marker interface generated for a list element that is not itself a component, e.g. element
 * `HomeListItem` becomes `interface IHomeListItemViewModel` that every row of the list extends.
 */
data class Marker(val element: String, val interfaceName: String, val owner: CatalogVm) {
    val qualifiedName: String get() = listOf(owner.packageName, interfaceName).filter { it.isNotEmpty() }.joinToString(".")
}

/** The method name basekit gives a `@RedirectsTo` edge; its source entry is `<SCREEN>_REDIRECT`. */
const val REDIRECT_METHOD = "redirect"

/** One `navigates_to` or `redirects_to` entry: [source] declares [method], which opens [target]. */
data class Edge(val source: CatalogVm, val method: String, val target: CatalogVm)

/**
 * Facts the generators share: who embeds which component, which edges reach a navigator, and the
 * marker interfaces lists need. Construction validates every reference in the catalog.
 */
class CatalogAnalysis(val document: CatalogDocument) {

    val screens: List<CatalogVm> = document.screens
    val components: List<CatalogVm> = document.components
    private val vms: Map<String, CatalogVm>

    /**
     * Screens, and components nothing embeds. Each root gets its own files and implementation; every
     * component it embeds is written with it.
     */
    val roots: List<CatalogVm>

    /** Roots that embed each component, through lists or children at any depth. */
    val hosts: Map<String, Set<String>>

    /** Edges whose source a screen embeds (or is a screen): only these have a navigator to call. */
    val reachableEdges: List<Edge>

    val markers: Map<String, Marker>

    /** Component name -> marker interfaces it implements as a row. */
    val rowMarkers: Map<String, List<Marker>>

    init {
        val all = screens + components
        val duplicates = all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw CatalogException("names used more than once: ${duplicates.sorted()}")
        vms = all.associateBy { it.name }
        val componentNames = components.map { it.name }.toSet()
        val screenNames = screens.map { it.name }.toSet()

        all.forEach { vm ->
            val path = (if (vm.isScreen) "screens." else "components.") + vm.name
            (vm.actions.map { it.name to it.navigatesTo } + vm.mutations.map { it.name to it.navigatesTo })
                .forEach { (method, targets) ->
                    targets.filterNot { it in screenNames }.forEach {
                        throw CatalogException("$path.$method.navigates_to: \"$it\" is not a screen")
                    }
                }
            vm.redirectsTo.filterNot { it in screenNames }.forEach {
                throw CatalogException("$path.redirects_to: \"$it\" is not a screen")
            }
            vm.children.forEach { child ->
                if (child.type !in componentNames) throw CatalogException("$path.children.${child.name}: \"${child.type}\" is not a component")
            }
            vm.lists.forEach { list ->
                list.rows.filterNot { it in componentNames }.forEach {
                    throw CatalogException("$path.lists.${list.name}.rows: \"$it\" is not a component")
                }
                if (list.element in screenNames) throw CatalogException("$path.lists.${list.name}.element: \"${list.element}\" is a screen")
                if (list.element in componentNames && list.rows.any { it != list.element }) {
                    throw CatalogException(
                        "$path.lists.${list.name}: rows other than the element \"${list.element}\" need a marker " +
                            "element that is not a component",
                    )
                }
            }
        }

        checkCycles()
        val embeddedAnywhere = all.flatMap { it.embedded }.toSet()
        roots = screens + components.filter { it.name !in embeddedAnywhere }
        hosts = buildMap<String, MutableSet<String>> {
            roots.forEach { root ->
                embeddedComponents(root).forEach { getOrPut(it.name) { mutableSetOf() }.add(root.name) }
            }
        }

        // Only destinations own a navigator, so only edges a screen reaches can be called.
        val reachable = screenNames + screens.flatMap { embeddedComponents(it) }.map { it.name }
        // A redirect is an edge from the screen itself with method `redirect`, as basekit's
        // @RedirectsTo produces, so it counts as a call site of its target.
        reachableEdges = all.filter { it.name in reachable }.flatMap { vm ->
            (listOf(REDIRECT_METHOD to vm.redirectsTo) + vm.actions.map { it.name to it.navigatesTo } + vm.mutations.map { it.name to it.navigatesTo })
                .flatMap { (method, targets) -> targets.map { Edge(vm, method, vms.getValue(it)) } }
        }

        val markerMap = LinkedHashMap<String, Marker>()
        all.forEach { vm ->
            vm.lists.filter { it.element !in componentNames && it.element !in markerMap }.forEach { list ->
                val prefix = if (vm.specName.hasInterfacePrefix()) "I" else ""
                markerMap[list.element] = Marker(list.element, "$prefix${list.element}ViewModel", vm)
            }
        }
        markers = markerMap
        rowMarkers = buildMap<String, MutableList<Marker>> {
            all.flatMap { it.lists }.filter { it.element in markerMap }.forEach { list ->
                list.rows.forEach { row ->
                    val forRow = getOrPut(row) { mutableListOf() }
                    val marker = markerMap.getValue(list.element)
                    if (marker !in forRow) forRow += marker
                }
            }
        }
    }

    fun vm(name: String): CatalogVm = vms[name] ?: throw CatalogException("unknown screen or component \"$name\"")

    fun isComponent(name: String): Boolean = vms[name]?.isScreen == false

    /** Every component [root] embeds, at any depth, in first-seen order. */
    fun embeddedComponents(root: CatalogVm): List<CatalogVm> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            queue.removeFirst().embedded
                .filter { isComponent(it) && it != root.name && seen.add(it) }
                .forEach { queue.addLast(vm(it)) }
        }
        return seen.map { vm(it) }
    }

    /** Components no screen or component embeds; each gets a top-level implementation. */
    val rootComponents: List<CatalogVm> get() = roots.filterNot { it.isScreen }

    /** The root whose files a component is written into: its only host, or null. */
    fun owner(component: CatalogVm): CatalogVm? = hosts[component.name]?.singleOrNull()?.let { vm(it) }

    /** Whether [screen] or anything it embeds navigates, so its impl receives a navigator. */
    fun needsNavigator(screen: CatalogVm): Boolean {
        val sources = (listOf(screen) + embeddedComponents(screen)).map { it.name }.toSet()
        return reachableEdges.any { it.source.name in sources }
    }

    /** Distinct call sites that open [target]; the navigator method takes a source when there are several. */
    fun callSites(target: CatalogVm): List<Edge> =
        reachableEdges.filter { it.target.name == target.name }.distinctBy { it.source.name to it.method }

    /** The `<Target>Source` entry for an edge, as basekit's `NavEdge.sourceEntry`. */
    fun sourceEntry(edge: Edge): String = "${edge.source.name.toNavName().uppercase()}_${edge.method.toUpperSnakeCase()}"

    /** Embedding cycles would construct rows forever in the generated static lists. */
    private fun checkCycles() {
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visit(name: String, path: List<String>) {
            if (name in done) return
            if (!visiting.add(name)) throw CatalogException("components embed each other: ${(path + name).joinToString(" -> ")}")
            vm(name).embedded.filter { isComponent(it) }.forEach { visit(it, path + name) }
            visiting.remove(name)
            done += name
        }
        (screens + components).forEach { visit(it.name, emptyList()) }
    }
}
