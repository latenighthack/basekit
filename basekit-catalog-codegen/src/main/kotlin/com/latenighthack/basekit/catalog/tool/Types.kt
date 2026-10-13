package com.latenighthack.basekit.catalog.tool

/** A catalog type string, e.g. `List<RecentMessageModel>?`, parsed into its parts. */
data class TypeRef(val name: String, val arguments: List<TypeRef>, val nullable: Boolean) {
    companion object {
        fun parse(text: String): TypeRef {
            val parser = Parser(text)
            val type = parser.type()
            parser.end()
            return type
        }
    }

    private class Parser(val text: String) {
        var index = 0

        fun type(): TypeRef {
            skipSpaces()
            val start = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] in "_.*")) index++
            if (start == index) fail()
            val name = text.substring(start, index)
            val arguments = mutableListOf<TypeRef>()
            skipSpaces()
            if (peek() == '<') {
                index++
                do {
                    arguments += type()
                    skipSpaces()
                } while (peek() == ',' && index++ >= 0)
                if (peek() != '>') fail()
                index++
            }
            skipSpaces()
            val nullable = peek() == '?'
            if (nullable) index++
            return TypeRef(name, arguments, nullable)
        }

        fun end() {
            skipSpaces()
            if (index != text.length) fail()
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun skipSpaces() {
            while (peek() == ' ') index++
        }

        private fun fail(): Nothing = throw CatalogException("cannot read the type \"$text\"")
    }
}

/**
 * Resolves catalog type names to Kotlin: builtins, catalog `types` (declared in their owner's spec
 * package, see [TypePlacement], or else in [typesPackage]), external types (imported by qualified
 * name) and screens/components (their spec interfaces).
 * Rendering records the imports a file needs in the given set.
 */
class TypeResolver(
    private val document: CatalogDocument,
    val typesPackage: String,
    private val placement: TypePlacement,
) {
    private val types = document.types.associateBy { it.name }
    private val vms = (document.screens + document.components).associateBy { it.name }

    fun typeDef(name: String): CatalogTypeDef? = types[name]

    fun render(ref: TypeRef, imports: MutableSet<String>): String {
        val base = when {
            ref.name == "*" -> "*"
            ref.name in BUILTINS -> ref.name
            ref.name in KOTLIN_IMPORTS -> {
                imports += KOTLIN_IMPORTS.getValue(ref.name)
                ref.name
            }
            ref.name in types -> {
                val type = types.getValue(ref.name)
                if (type.external != null) {
                    imports += type.external
                    type.external.substringAfterLast('.')
                } else {
                    val packageName = placement.owner(ref.name)?.packageName ?: typesPackage
                    imports += listOf(packageName, ref.name.substringBefore('.')).filter { it.isNotEmpty() }.joinToString(".")
                    ref.name
                }
            }
            ref.name in vms -> {
                val spec = vms.getValue(ref.name).spec
                imports += spec
                spec.substringAfterLast('.')
            }
            else -> throw CatalogException("unknown type \"${ref.name}\"; declare it under types")
        }
        val arguments = if (ref.arguments.isEmpty()) "" else ref.arguments.joinToString(", ", "<", ">") { render(it, imports) }
        return base + arguments + if (ref.nullable) "?" else ""
    }

    fun render(type: String, imports: MutableSet<String>): String = render(TypeRef.parse(type), imports)

    /** A Kotlin expression of [type] usable as an initial value. */
    fun default(type: String, imports: MutableSet<String>): String = default(TypeRef.parse(type), imports, emptySet())

    private fun default(ref: TypeRef, imports: MutableSet<String>, seen: Set<String>): String {
        if (ref.nullable) return "null"
        BUILTIN_DEFAULTS[ref.name]?.let { return it }
        val type = types[ref.name] ?: return "TODO(\"initial ${ref.name}\")"
        if (type.external != null || ref.name in seen) return "TODO(\"initial ${ref.name}\")"
        val name = render(TypeRef(ref.name, emptyList(), false), imports)
        val next = seen + ref.name
        return when {
            type.enumCases != null ->
                type.enumCases.firstOrNull()?.let { "$name.${identifier(it)}" } ?: "TODO(\"initial ${ref.name}\")"
            type.subtypes != null -> {
                val case = type.subtypes.firstOrNull { it !in types }
                when {
                    case != null -> "$name.${identifier(case)}"
                    type.subtypes.isNotEmpty() -> default(TypeRef(type.subtypes.first(), emptyList(), false), imports, next)
                    else -> "TODO(\"initial ${ref.name}\")"
                }
            }
            else -> constructorCall(name, type.fields.orEmpty(), imports, next)
        }
    }

    /** `Name(a = …, b = …)` with a default for every field. */
    fun constructorCall(name: String, fields: List<Field>, imports: MutableSet<String>, seen: Set<String> = emptySet()): String =
        fields.joinToString(", ", "$name(", ")") { field ->
            "${identifier(field.name)} = ${default(TypeRef.parse(field.type), imports, seen)}"
        }

    companion object {
        /** Kotlin types usable without an import. */
        val BUILTINS = setOf(
            "String", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char", "Unit", "Any",
            "Nothing", "Number", "ByteArray", "IntArray", "LongArray", "ShortArray", "CharArray",
            "BooleanArray", "DoubleArray", "FloatArray", "Array", "List", "MutableList", "Set", "MutableSet",
            "Map", "MutableMap", "Collection", "Iterable", "Pair", "Triple",
        )

        /** Types from `kotlin.*` packages that need an import; the catalog lists them by simple name. */
        val KOTLIN_IMPORTS = mapOf(
            "Duration" to "kotlin.time.Duration",
            "Instant" to "kotlin.time.Instant",
            "Uuid" to "kotlin.uuid.Uuid",
        )

        private val BUILTIN_DEFAULTS = mapOf(
            "String" to "\"\"", "Int" to "0", "Long" to "0L", "Short" to "0", "Byte" to "0",
            "Double" to "0.0", "Float" to "0f", "Boolean" to "false", "Char" to "' '", "Unit" to "Unit",
            "Any" to "Unit", "ByteArray" to "byteArrayOf()", "IntArray" to "intArrayOf()",
            "LongArray" to "longArrayOf()", "List" to "emptyList()", "MutableList" to "mutableListOf()",
            "Collection" to "emptyList()", "Iterable" to "emptyList()", "Set" to "emptySet()",
            "MutableSet" to "mutableSetOf()", "Map" to "emptyMap()", "MutableMap" to "mutableMapOf()",
            "Duration" to "kotlin.time.Duration.ZERO",
        )
    }
}
