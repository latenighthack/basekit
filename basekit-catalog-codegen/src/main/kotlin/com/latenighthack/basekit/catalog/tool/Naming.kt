package com.latenighthack.basekit.catalog.tool

// These mirror basekit-ksp's navigation Tools.kt so generated names match what the processors
// derive from the generated code.

/** Splits an identifier into its words, e.g. "onOpenDetail" -> [on, Open, Detail]. */
fun String.camelWords(): List<String> =
    split(Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")).filter { it.isNotEmpty() }

/** "onOpenDetail" -> "ON_OPEN_DETAIL". */
fun String.toUpperSnakeCase(): String = camelWords().joinToString("_") { it.uppercase() }

/** "user_profile" -> "UserProfile". */
fun String.snakeToUpperCamel(): String = split("_")
    .filter { it.isNotEmpty() }
    .joinToString("") { it[0].uppercase() + it.substring(1) }

/** A catalog name as basekit's snake_case navigation name: "HomeRoomItem" -> "home_room_item". */
fun String.toNavName(): String = camelWords().joinToString("_") { it.lowercase() }

/**
 * basekit's navigation name for a spec: drops a leading interface `I` and one trailing `Screen`,
 * `Destination`, `Route` or `ViewModel`, then snake_cases the rest.
 */
fun String.toDestinationNavName(): String {
    var str = this
    if (str.length > 1 && str[0] == 'I' && str[1].isUpperCase()) str = str.substring(1)
    for (suffix in listOf("Screen", "Destination", "Route", "ViewModel")) {
        if (str.length > suffix.length && str.endsWith(suffix)) {
            str = str.substring(0, str.length - suffix.length)
            break
        }
    }
    return str.camelWords().joinToString("_") { it.lowercase() }
}

/** The catalog name basekit derives for a spec: "IHomeViewModel" -> "Home". */
fun String.toCatalogName(): String = toDestinationNavName().snakeToUpperCamel()

/** Whether a spec name follows the `I` interface prefix convention, e.g. `IHomeViewModel`. */
fun String.hasInterfacePrefix(): Boolean = length > 1 && this[0] == 'I' && this[1].isUpperCase()

/** The implementation class for a spec: `IHomeViewModel` -> `HomeViewModel`, `HomeViewModel` -> `HomeViewModelImpl`. */
fun implName(specName: String): String =
    if (specName.hasInterfacePrefix()) specName.substring(1) else "${specName}Impl"

/** The State noun a mutator targets: `setName` -> `name`, as basekit's `mutatorNoun`. */
fun mutatorNoun(name: String): String {
    val stripped = if (name.length > 3 && name.startsWith("set") && name[3].isUpperCase()) name.substring(3) else name
    return stripped.replaceFirstChar { it.lowercase() }
}

fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

fun String.decapitalized(): String = replaceFirstChar { it.lowercase() }

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)

/** [name] as a Kotlin identifier, backquoted when it is a hard keyword. */
fun identifier(name: String): String = if (name in KOTLIN_KEYWORDS) "`$name`" else name
