package com.latenighthack.basekit.catalog.tool

/**
 * Writes the catalog's `types` into one file:
 *
 *  - `enum` -> `enum class`;
 *  - `fields` -> `data class` (`class` when there are none);
 *  - `subtypes` -> `sealed interface`, or `sealed class` when it also has fields. A subtype that is not
 *    itself a catalog type is a case and becomes a nested `data object`; a subtype that is a type
 *    extends the sealed type;
 *  - a dotted name such as `Button.Style` is nested in `Button` (an `object` when `Button` is not a type);
 *  - `external` types are imported where used and not generated.
 */
class TypesGenerator(private val document: CatalogDocument, private val types: TypeResolver) {

    private class Node(val name: String) {
        var type: CatalogTypeDef? = null
        val children = LinkedHashMap<String, Node>()
    }

    private val declared = document.types.filter { it.external == null }.associateBy { it.name }

    /** Class subtype name -> the sealed type it extends. */
    private val parents: Map<String, CatalogTypeDef> = buildMap {
        declared.values.forEach { type ->
            type.subtypes.orEmpty().filter { it in declared }.forEach { put(it, type) }
        }
    }

    fun file(): GeneratedFile? {
        if (declared.isEmpty()) return null
        val root = Node("")
        declared.values.forEach { type ->
            var node = root
            type.name.split('.').forEach { segment -> node = node.children.getOrPut(segment) { Node(segment) } }
            node.type = type
        }
        val imports = mutableSetOf<String>()
        val body = root.children.values.joinToString("\n\n") { declaration(it, imports) }
        return GeneratedFile(
            sourcePath(types.typesPackage, "CatalogTypes"),
            kotlinFile(types.typesPackage, SpecGenerator.HEADER, imports, body),
            overwrite = true,
        )
    }

    private fun declaration(node: Node, imports: MutableSet<String>): String {
        val type = node.type
        val nested = node.children.values.map { declaration(it, imports) }
        val name = node.name
        val (head, entries) = when {
            type == null -> "object $name" to emptyList()
            type.enumCases != null -> {
                val cases = type.enumCases.joinToString(",\n") { identifier(it) } + if (nested.isEmpty()) "," else ";"
                "enum class $name" to listOfNotNull(cases.takeIf { type.enumCases.isNotEmpty() })
            }
            type.subtypes != null -> {
                val fields = type.fields.orEmpty()
                val head = if (fields.isEmpty()) {
                    "sealed interface $name"
                } else {
                    "sealed class $name(${fields.joinToString(", ") { "val ${identifier(it.name)}: ${types.render(it.type, imports)}" }})"
                }
                val cases = type.subtypes.filterNot { it in declared }.map { case ->
                    "data object ${identifier(case)} : $name${superCall(type, imports)}"
                }
                head + supertype(type.name, imports) to cases
            }
            else -> {
                val fields = type.fields.orEmpty()
                val head = if (fields.isEmpty()) {
                    "class $name"
                } else {
                    fields.joinToString(", ", "data class $name(", ")") { "val ${identifier(it.name)}: ${types.render(it.type, imports)}" }
                }
                head + supertype(type.name, imports) to emptyList()
            }
        }
        val members = entries + nested
        if (members.isEmpty()) return head
        return "$head {\n${members.joinToString("\n\n").indent(4)}\n}"
    }

    /** ` : Parent` (or ` : Parent(defaults)` for a sealed class) when [typeName] is a class subtype. */
    private fun supertype(typeName: String, imports: MutableSet<String>): String {
        val parent = parents[typeName] ?: return ""
        return " : ${types.render(parent.name, imports)}${superCall(parent, imports)}"
    }

    private fun superCall(parent: CatalogTypeDef, imports: MutableSet<String>): String {
        val fields = parent.fields.orEmpty()
        if (fields.isEmpty()) return ""
        return fields.joinToString(", ", "(", ")") { types.default(it.type, imports) }
    }
}
