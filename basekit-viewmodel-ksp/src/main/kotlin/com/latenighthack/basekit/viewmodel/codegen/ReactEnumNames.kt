package com.latenighthack.basekit.viewmodel.codegen

internal const val REACT_ENUM_NAMES_OPTION = "basekit.viewmodel.reactEnumNames"
internal const val REACT_ENUM_NAME_ANNOTATION = "com.latenighthack.basekit.viewmodel.annotations.ReactEnumName"

/** How the React bindings spell enum entries without a `@ReactEnumName`: [REACT_ENUM_NAMES_OPTION]. */
enum class ReactEnumNames(val option: String) {
    /** The Kotlin entry name: `SIGNED_IN`. */
    NAME("name"),

    /** Lower camel case: `SIGNED_IN` and `SignedIn` are both `signedIn`. */
    LOWER_CAMEL("lowerCamel");

    fun spell(entry: String): String = when (this) {
        NAME -> entry
        LOWER_CAMEL -> lowerCamelEnumName(entry)
    }

    companion object {
        /** Parses the option's value; null when it is not one of the options. */
        fun parse(value: String?): ReactEnumNames? =
            if (value == null) NAME else entries.firstOrNull { it.option == value }
    }
}

/**
 * Splits [entry] on `_`, lowercases each all-capitals word, then joins the words in lower camel
 * case: `SIGNED_IN` is `signedIn`, `HTTP2_ERROR` is `http2Error`, `SignedIn` is `signedIn`.
 */
internal fun lowerCamelEnumName(entry: String): String {
    val words = entry.split('_').filter { it.isNotEmpty() }
    if (words.isEmpty()) return entry
    return words.mapIndexed { index, word ->
        val normalized = if (word.none { it.isLowerCase() }) word.lowercase() else word
        if (index == 0) normalized.replaceFirstChar { it.lowercase() } else normalized.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}

/** The React spellings of [type]'s enum entries, Kotlin names when it has none of its own. */
internal val VmType.reactEnumNames: List<String>? get() = enumJsNames ?: enumCases

/** `when ([expression]) { Enum.A -> "a" … }` over [type]'s entries. */
internal fun reactEnumToJs(type: VmType, expression: String): String {
    val cases = requireNotNull(type.enumCases)
    val names = requireNotNull(type.reactEnumNames)
    if (cases == names) return "$expression.name"
    val branches = cases.zip(names).joinToString("; ") { (case, name) -> "${type.qualifiedName}.$case -> \"$name\"" }
    return "when ($expression) { $branches }"
}

/**
 * The runtime's `reactEnumValue` over [type]'s entries: the entry spelled [expression], or a throw
 * naming the expected spellings.
 */
internal fun reactEnumFromJs(type: VmType, expression: String): String {
    val cases = requireNotNull(type.enumCases)
    val names = requireNotNull(type.reactEnumNames)
    val spellings = names.joinToString(", ") { kotlinStringLiteral(it) }
    val entries = cases.joinToString(", ") { "${type.qualifiedName}.$it" }
    return "com.latenighthack.basekit.viewmodel.reactEnumValue($expression, arrayOf($spellings), arrayOf($entries), ${kotlinStringLiteral(type.qualifiedName.substringAfterLast('.'))})"
}
