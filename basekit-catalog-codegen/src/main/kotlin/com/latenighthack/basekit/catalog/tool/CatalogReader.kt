package com.latenighthack.basekit.catalog.tool

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/** A catalog that cannot be generated from; [message] names the YAML path at fault. */
class CatalogException(message: String) : Exception(message)

/** Parses `catalog/v1` YAML into a [CatalogDocument]. */
object CatalogReader {

    const val SCHEMA = "catalog/v1"

    fun read(text: String): CatalogDocument {
        val root = loadYaml(text) as? Map<*, *> ?: throw CatalogException("the catalog must be a YAML map")
        val schema = root["schema"]
        if (schema != SCHEMA) throw CatalogException("schema: expected $SCHEMA, found ${schema ?: "nothing"}")
        val screens = entries(root, "screens").map { (name, body) -> vm(name, body, "screens.$name", screen = true) }
        val components = entries(root, "components").map { (name, body) -> vm(name, body, "components.$name", screen = false) }
        val types = entries(root, "types").map { (name, body) -> type(name, body, "types.$name") }
        return CatalogDocument(screens, components, types)
    }

    /** Parses YAML 1.1, reporting syntax errors as a [CatalogException]. */
    fun loadYaml(text: String): Any? = try {
        Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
    } catch (error: YAMLException) {
        throw CatalogException("not valid YAML: ${error.message?.trim()}")
    }

    private fun entries(map: Map<*, *>, key: String): List<Pair<String, Map<*, *>>> {
        val section = map[key] ?: return emptyList()
        val entries = section as? Map<*, *> ?: throw CatalogException("$key: expected a map")
        return entries.map { (name, body) ->
            val text = name?.toString() ?: throw CatalogException("$key: empty name")
            text to (body as? Map<*, *> ?: emptyMap<Any, Any>())
        }
    }

    private fun vm(name: String, body: Map<*, *>, path: String, screen: Boolean): CatalogVm {
        val spec = body["spec"] as? String ?: throw CatalogException("$path.spec: required")
        val navigation = if (screen) {
            val nav = body["navigation"] as? Map<*, *> ?: throw CatalogException("$path.navigation: required for a screen")
            Navigation(
                args = fields(nav["args"], "$path.navigation.args"),
                route = nav["route"] as? String,
                routeArgs = strings(nav["route_args"], "$path.navigation.route_args"),
                response = nav["response"] as? String,
            )
        } else {
            if (body.containsKey("navigation")) throw CatalogException("$path.navigation: only screens navigate")
            null
        }
        return CatalogVm(
            name = name,
            spec = spec,
            navigation = navigation,
            identity = body["identity"] as? String,
            state = fields(body["state"], "$path.state"),
            actions = map(body["actions"], "$path.actions").map { (action, value) ->
                Action(action, targets((value as? Map<*, *>)?.get("navigates_to"), "$path.actions.$action"))
            },
            mutations = map(body["mutations"], "$path.mutations").map { (mutation, value) ->
                val entry = value as? Map<*, *> ?: throw CatalogException("$path.mutations.$mutation: expected a map")
                val param = fields(entry["param"], "$path.mutations.$mutation.param").singleOrNull()
                    ?: throw CatalogException("$path.mutations.$mutation.param: expected exactly one parameter")
                Mutation(mutation, param, entry["binds"] as? String, targets(entry["navigates_to"], "$path.mutations.$mutation"))
            },
            lists = map(body["lists"], "$path.lists").map { (list, value) ->
                val entry = value as? Map<*, *> ?: throw CatalogException("$path.lists.$list: expected a map")
                val kind = when (entry["kind"]) {
                    "flat" -> ListKind.FLAT
                    "sectioned" -> ListKind.SECTIONED
                    else -> throw CatalogException("$path.lists.$list.kind: expected flat or sectioned")
                }
                ListDef(
                    name = list,
                    kind = kind,
                    sections = strings(entry["sections"], "$path.lists.$list.sections"),
                    element = entry["element"] as? String ?: throw CatalogException("$path.lists.$list.element: required"),
                    rows = strings(entry["rows"], "$path.lists.$list.rows"),
                )
            },
            children = fields(body["children"], "$path.children"),
        )
    }

    private fun type(name: String, body: Map<*, *>, path: String) = CatalogTypeDef(
        name = name,
        enumCases = body["enum"]?.let { strings(it, "$path.enum") },
        fields = body["fields"]?.let { fields(it, "$path.fields") },
        subtypes = body["subtypes"]?.let { strings(it, "$path.subtypes") },
        external = body["external"] as? String,
    )

    private fun map(value: Any?, path: String): List<Pair<String, Any?>> = when (value) {
        null -> emptyList()
        is Map<*, *> -> value.map { (key, entry) -> key.toString() to entry }
        else -> throw CatalogException("$path: expected a map")
    }

    private fun fields(value: Any?, path: String): List<Field> = map(value, path).map { (name, type) ->
        Field(name, type as? String ?: throw CatalogException("$path.$name: expected a type"))
    }

    private fun strings(value: Any?, path: String): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.map { it?.toString() ?: throw CatalogException("$path: empty entry") }
        else -> throw CatalogException("$path: expected a list")
    }

    private fun targets(value: Any?, path: String): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        else -> strings(value, "$path.navigates_to")
    }
}
