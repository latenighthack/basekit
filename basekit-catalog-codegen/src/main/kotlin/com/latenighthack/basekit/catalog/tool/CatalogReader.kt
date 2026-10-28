package com.latenighthack.basekit.catalog.tool

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/** A catalog that cannot be generated from; [message] names the YAML path at fault. */
class CatalogException(message: String) : Exception(message)

/**
 * Parses `catalog/v1` YAML into a [CatalogDocument]. Keys outside the format are ignored and listed in
 * [CatalogDocument.warnings].
 */
object CatalogReader {

    const val SCHEMA = "catalog/v1"

    fun read(text: String): CatalogDocument = Parser().read(text)

    /** Parses YAML 1.1, reporting syntax errors as a [CatalogException]. */
    fun loadYaml(text: String): Any? = try {
        Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
    } catch (error: YAMLException) {
        throw CatalogException("not valid YAML: ${error.message?.trim()}")
    }

    private val ROOT_KEYS = setOf("schema", "screens", "components", "types")
    private val VM_KEYS = setOf("spec", "navigation", "hosted", "redirects_to", "identity", "state", "actions", "mutations", "lists", "children")
    private val NAVIGATION_KEYS = setOf("args", "route", "route_aliases", "route_args", "response")
    private val ACTION_KEYS = setOf("navigates_to")
    private val MUTATION_KEYS = setOf("param", "binds", "navigates_to")
    private val LIST_KEYS = setOf("kind", "sections", "element", "rows", "fake")
    private val STATE_FIELD_KEYS = setOf("type", "fake")
    private val LIST_FAKE_KEYS = setOf("size", "min", "max", "rows")
    private val FIELD_FAKE_KEYS = setOf("text", "number", "null_frequency", "case", "value")
    private val TEXT_KEYS = setOf("kind", "min_length", "max_length", "values")
    private val NUMBER_KEYS = setOf("min", "max")
    /** basekit-fake-annotations' FakeTextKind entries. */
    private val TEXT_KINDS = listOf(
        "AUTO", "WORD", "WORDS", "TITLE", "SENTENCE", "PARAGRAPH", "PERSON_NAME", "FIRST_NAME", "LAST_NAME", "USERNAME",
        "EMAIL", "PHONE", "URL", "IMAGE_URL", "ID", "UUID", "CITY", "COUNTRY", "ADDRESS", "COMPANY", "COLOR_HEX", "DATE",
        "TIME", "PRICE",
    )
    private val TYPE_KEYS = setOf("enum", "fields", "subtypes", "external")

    private class Parser {
        private val warnings = mutableListOf<String>()

        private fun known(map: Map<*, *>, keys: Set<String>, path: String) {
            map.keys.map { it.toString() }.filter { it !in keys }.forEach { warnings += "$path.$it" }
        }

        fun read(text: String): CatalogDocument {
            val root = loadYaml(text) as? Map<*, *> ?: throw CatalogException("the catalog must be a YAML map")
            val schema = root["schema"]
            if (schema != SCHEMA) throw CatalogException("schema: expected $SCHEMA, found ${schema ?: "nothing"}")
            root.keys.map { it.toString() }.filter { it !in ROOT_KEYS }.forEach { warnings += it }
            val screens = entries(root, "screens").map { (name, body) -> vm(name, body, "screens.$name", screen = true) }
            val components = entries(root, "components").map { (name, body) -> vm(name, body, "components.$name", screen = false) }
            val types = entries(root, "types").map { (name, body) -> type(name, body, "types.$name") }
            return CatalogDocument(screens, components, types, warnings.toList())
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
            known(body, VM_KEYS, path)
            if (!screen && body.containsKey("redirects_to")) throw CatalogException("$path.redirects_to: only screens redirect")
            if (screen && body.containsKey("hosted")) throw CatalogException("$path.hosted: only components are hosted")
            val hosted = when (val value = body["hosted"]) {
                null -> false
                is Boolean -> value
                else -> throw CatalogException("$path.hosted: expected true or false")
            }
            val navigation = if (screen) {
                val nav = body["navigation"] as? Map<*, *> ?: throw CatalogException("$path.navigation: required for a screen")
                known(nav, NAVIGATION_KEYS, "$path.navigation")
                val route = nav["route"] as? String
                val aliases = strings(nav["route_aliases"], "$path.navigation.route_aliases")
                if (aliases.isNotEmpty() && route == null) {
                    throw CatalogException("$path.navigation.route_aliases: needs a route")
                }
                val args = fields(nav["args"], "$path.navigation.args")
                val routeArgs = strings(nav["route_args"], "$path.navigation.route_args")
                // basekit gives each template its own args factory: a non-null route arg must be a
                // required capture of every template, and a nullable one a capture of at least one.
                if (route != null) {
                    val templates = listOf(route to "$path.navigation.route") +
                        aliases.map { it to "$path.navigation.route_aliases" }
                    routeArgs.forEach { arg ->
                        val isNullable = args.firstOrNull { it.name == arg }?.type?.trim()?.endsWith("?") == true
                        if (isNullable) {
                            if (templates.none { (template, _) -> arg in routeParams(template, required = false) }) {
                                throw CatalogException("$path.navigation.route_args: \"$arg\" is not captured by the route or an alias")
                            }
                        } else {
                            templates.firstOrNull { (template, _) -> arg !in routeParams(template, required = true) }?.let { (template, at) ->
                                throw CatalogException(
                                    "$at: \"$template\" must capture \"$arg\", a non-null route arg, as {$arg} or key={$arg}",
                                )
                            }
                        }
                    }
                }
                Navigation(
                    args = args,
                    route = route,
                    routeArgs = routeArgs,
                    response = nav["response"] as? String,
                    routeAliases = aliases,
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
                state = stateFields(body["state"], "$path.state"),
                actions = map(body["actions"], "$path.actions").map { (action, value) ->
                    (value as? Map<*, *>)?.let { known(it, ACTION_KEYS, "$path.actions.$action") }
                    Action(action, targets((value as? Map<*, *>)?.get("navigates_to"), "$path.actions.$action"))
                },
                mutations = map(body["mutations"], "$path.mutations").map { (mutation, value) ->
                    val entry = value as? Map<*, *> ?: throw CatalogException("$path.mutations.$mutation: expected a map")
                    known(entry, MUTATION_KEYS, "$path.mutations.$mutation")
                    val param = fields(entry["param"], "$path.mutations.$mutation.param").singleOrNull()
                        ?: throw CatalogException("$path.mutations.$mutation.param: expected exactly one parameter")
                    Mutation(mutation, param, entry["binds"] as? String, targets(entry["navigates_to"], "$path.mutations.$mutation"))
                },
                lists = map(body["lists"], "$path.lists").map { (list, value) ->
                    val entry = value as? Map<*, *> ?: throw CatalogException("$path.lists.$list: expected a map")
                    known(entry, LIST_KEYS, "$path.lists.$list")
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
                        fake = entry["fake"]?.let { fake(it, "$path.lists.$list.fake", list = true) },
                    )
                },
                children = fields(body["children"], "$path.children"),
                redirectsTo = strings(body["redirects_to"], "$path.redirects_to"),
                hosted = hosted,
            )
        }

        /** The names [template] captures; with [required], leaving out optional `key={name?}` captures. */
        private fun routeParams(template: String, required: Boolean): Set<String> =
            Regex("""\{([^/}]+)}""").findAll(template).map { it.groupValues[1] }
                .filterNot { required && it.endsWith("?") }
                .map { it.removeSuffix("?") }
                .toSet()

        private fun type(name: String, body: Map<*, *>, path: String): CatalogTypeDef = known(body, TYPE_KEYS, path).let { CatalogTypeDef(
            name = name,
            enumCases = body["enum"]?.let { strings(it, "$path.enum") },
            fields = body["fields"]?.let { fields(it, "$path.fields") },
            subtypes = body["subtypes"]?.let { strings(it, "$path.subtypes") },
            external = body["external"] as? String,
        ) }

        private fun map(value: Any?, path: String): List<Pair<String, Any?>> = when (value) {
            null -> emptyList()
            is Map<*, *> -> value.map { (key, entry) -> key.toString() to entry }
            else -> throw CatalogException("$path: expected a map")
        }

        private fun fields(value: Any?, path: String): List<Field> = map(value, path).map { (name, type) ->
            Field(name, type as? String ?: throw CatalogException("$path.$name: expected a type"))
        }

        /** State fields: `name: Type`, or `name: { type: Type, fake: { … } }`. */
        private fun stateFields(value: Any?, path: String): List<Field> = map(value, path).map { (name, entry) ->
            when (entry) {
                is String -> Field(name, entry)
                is Map<*, *> -> {
                    known(entry, STATE_FIELD_KEYS, "$path.$name")
                    Field(
                        name,
                        entry["type"] as? String ?: throw CatalogException("$path.$name.type: required"),
                        entry["fake"]?.let { fake(it, "$path.$name.fake", list = false) },
                    )
                }
                else -> throw CatalogException("$path.$name: expected a type")
            }
        }

        private fun fake(value: Any?, path: String, list: Boolean): FakeSettings {
            val entry = value as? Map<*, *> ?: throw CatalogException("$path: expected a map")
            val keys = if (list) LIST_FAKE_KEYS + "placeholder" else FIELD_FAKE_KEYS
            known(entry, keys + "data_sets", path)
            val dataSets = map(entry["data_sets"], "$path.data_sets").map { (name, values) ->
                val set = values as? Map<*, *> ?: throw CatalogException("$path.data_sets.$name: expected a map")
                known(set, if (list) LIST_FAKE_KEYS else FIELD_FAKE_KEYS, "$path.data_sets.$name")
                name to fakeValues(set, "$path.data_sets.$name")
            }
            return FakeSettings(
                base = fakeValues(entry, path),
                placeholder = if (list) entry["placeholder"] as? String else null,
                dataSets = dataSets,
            )
        }

        private fun fakeValues(entry: Map<*, *>, path: String): FakeValues = FakeValues(
            size = int(entry["size"], "$path.size"),
            min = int(entry["min"], "$path.min"),
            max = int(entry["max"], "$path.max"),
            rows = strings(entry["rows"], "$path.rows"),
            text = entry["text"]?.let { text ->
                when (text) {
                    is String -> FakeTextValue(kind = textKind(text, "$path.text"))
                    is Map<*, *> -> {
                        known(text, TEXT_KEYS, "$path.text")
                        FakeTextValue(
                            kind = (text["kind"] as? String)?.let { textKind(it, "$path.text.kind") },
                            minLength = int(text["min_length"], "$path.text.min_length"),
                            maxLength = int(text["max_length"], "$path.text.max_length"),
                            values = strings(text["values"], "$path.text.values"),
                        )
                    }
                    else -> throw CatalogException("$path.text: expected a kind or a map")
                }
            },
            number = entry["number"]?.let { number ->
                val range = number as? Map<*, *> ?: throw CatalogException("$path.number: expected { min, max }")
                known(range, NUMBER_KEYS, "$path.number")
                (double(range["min"], "$path.number.min") ?: throw CatalogException("$path.number.min: required")) to
                    (double(range["max"], "$path.number.max") ?: throw CatalogException("$path.number.max: required"))
            },
            nullFrequency = double(entry["null_frequency"], "$path.null_frequency"),
            cases = strings(entry["case"], "$path.case"),
            value = entry["value"]?.toString(),
        )

        private fun textKind(kind: String, path: String): String =
            kind.takeIf { it in TEXT_KINDS } ?: throw CatalogException("$path: \"$kind\" is not a FakeTextKind (${TEXT_KINDS.joinToString()})")

        private fun int(value: Any?, path: String): Int? = when (value) {
            null -> null
            is Int -> value
            else -> throw CatalogException("$path: expected a whole number")
        }

        private fun double(value: Any?, path: String): Double? = when (value) {
            null -> null
            is Number -> value.toDouble()
            else -> throw CatalogException("$path: expected a number")
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
}
