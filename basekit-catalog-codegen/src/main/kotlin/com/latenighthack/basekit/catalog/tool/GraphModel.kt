package com.latenighthack.basekit.catalog.tool

/**
 * The data behind the catalog graph page, as JSON-ready maps.
 *
 * `nodes` are the graph boxes: every screen, plus every component nothing embeds whose subtree has
 * events (marked `reachable: false`: no navigator reaches it). Each node lists `items`, the node
 * itself and every component it embeds at any depth that declares `navigates_to`, and each item lists
 * its `events`: one per (method, target), plus one per `redirects_to` target on a screen, so every
 * event becomes its own edge.
 *
 * `vms` and `types` hold everything the screen modal shows, each entry with a resolved source `link`
 * when catalog.sources.yaml knows where it is declared.
 */
class GraphModel(
    private val document: CatalogDocument,
    private val links: SourceLinks,
    private val title: String,
) {
    private val analysis = CatalogAnalysis(document)

    fun toJson(): String = Json.write(
        mapOf(
            "title" to title,
            "nodes" to nodes(),
            "vms" to (document.screens + document.components).associate { it.name to vm(it) },
            "types" to document.types.associate { it.name to type(it) },
        ),
    )

    private fun nodes(): List<Map<String, Any?>> {
        val orphans = analysis.rootComponents.filter { root ->
            (listOf(root) + analysis.embeddedComponents(root)).any { it.hasEvents() }
        }
        // A hosted component has its own navigator, so its edges are reachable like a screen's.
        return (analysis.screens.map { it to true } + orphans.map { it to it.hosted }).map { (root, reachable) ->
            val items = (listOf(root) + analysis.embeddedComponents(root))
                .filter { it == root || it.hasEvents() }
                .map { vm ->
                    mapOf(
                        "vm" to vm.name,
                        "self" to (vm == root),
                        "events" to vm.events().map { (method, kind, target) ->
                            mapOf(
                                "id" to "${root.name}|${vm.name}|$method|$target",
                                "method" to method,
                                "kind" to kind,
                                "target" to target,
                            )
                        },
                    )
                }
            mapOf(
                "name" to root.name,
                "screen" to root.isScreen,
                "reachable" to reachable,
                "hosted" to root.hosted,
                "route" to root.navigation?.route,
                "items" to items,
            )
        }
    }

    private fun CatalogVm.hasEvents() = events().isNotEmpty()

    /**
     * (method, kind, target) for every redirect (kind "redirect", method "redirects_to") and every
     * navigates_to entry (kind "action" or "mutation"), in declaration order.
     */
    private fun CatalogVm.events(): List<Triple<String, String, String>> =
        redirectsTo.map { Triple("redirects_to", "redirect", it) } +
            actions.flatMap { action -> action.navigatesTo.map { Triple(action.name, "action", it) } } +
            mutations.flatMap { mutation -> mutation.navigatesTo.map { Triple(mutation.name, "mutation", it) } }

    private fun vm(vm: CatalogVm): Map<String, Any?> {
        val key = vm.name
        return mapOf(
            "name" to vm.name,
            "spec" to vm.spec,
            "screen" to vm.isScreen,
            "hosted" to vm.hosted,
            "link" to link(key),
            "embeddedBy" to analysis.hosts[vm.name].orEmpty().sorted(),
            "navigation" to vm.navigation?.let { navigation ->
                mapOf(
                    "route" to navigation.route,
                    "routeArgs" to navigation.routeArgs,
                    "response" to navigation.response,
                    "args" to navigation.args.map { field(it, "$key.navigation.args.${it.name}") },
                    "redirectsTo" to vm.redirectsTo,
                    "redirectsLink" to link("$key.redirects_to"),
                )
            },
            "identity" to vm.identity?.let { mapOf("name" to it, "link" to link("$key.identity")) },
            "state" to vm.state.map { field(it, "$key.state.${it.name}") },
            "actions" to vm.actions.map {
                mapOf("name" to it.name, "navigatesTo" to it.navigatesTo, "link" to link("$key.actions.${it.name}"))
            },
            "mutations" to vm.mutations.map {
                mapOf(
                    "name" to it.name,
                    "param" to mapOf("name" to it.param.name, "type" to it.param.type),
                    "binds" to it.binds,
                    "navigatesTo" to it.navigatesTo,
                    "link" to link("$key.mutations.${it.name}"),
                )
            },
            "lists" to vm.lists.map {
                mapOf(
                    "name" to it.name,
                    "kind" to it.kind.name.lowercase(),
                    "sections" to it.sections,
                    "element" to it.element,
                    "rows" to it.rows,
                    "link" to link("$key.lists.${it.name}"),
                )
            },
            "children" to vm.children.map { field(it, "$key.children.${it.name}") },
        )
    }

    private fun type(type: CatalogTypeDef): Map<String, Any?> = mapOf(
        "name" to type.name,
        "enum" to type.enumCases,
        "fields" to type.fields?.map { mapOf("name" to it.name, "type" to it.type) },
        "subtypes" to type.subtypes,
        "external" to type.external,
        "link" to link("types.${type.name}"),
    )

    private fun field(field: Field, key: String): Map<String, Any?> =
        mapOf("name" to field.name, "type" to field.type, "link" to link(key))

    private fun link(key: String): Map<String, String>? =
        links.link(key)?.let { mapOf("href" to it.href, "label" to it.label) }
}
