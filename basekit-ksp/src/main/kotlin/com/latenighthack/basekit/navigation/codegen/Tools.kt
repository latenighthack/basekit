package com.latenighthack.basekit.navigation.codegen

import java.io.OutputStream

fun OutputStream.writeln(s: String = "") {
    write(s.encodeToByteArray())
    write("\n".encodeToByteArray())
}

/** Splits an identifier into its words, e.g. "onOpenDetail" -> [on, Open, Detail]. */
fun String.camelWords(): List<String> =
    split(Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])"))

/** "user_profile" -> "UserProfile". */
fun String.toUpperCamelCase(): String = split("_")
    .filter { it.isNotEmpty() }
    .joinToString("") { it[0].uppercase() + it.substring(1) }

/** "onOpenDetail" -> "ON_OPEN_DETAIL". */
fun String.toUpperSnakeCase(): String = camelWords()
    .filter { it.isNotEmpty() }
    .joinToString("_") { it.uppercase() }

/**
 * Derives the snake_case navigation name of a destination from its simple name, dropping a leading
 * interface `I` and a trailing `Screen`/`Destination`/`Route`/`ViewModel` suffix. e.g. "HomeScreen" ->
 * "home", "UserProfileScreen" -> "user_profile", "HomeViewModel" -> "home".
 */
fun String.toDestinationNavName(): String {
    var str = this
    if (str.length > 1 && str[0] == 'I' && str[1].isUpperCase()) {
        str = str.substring(1)
    }
    for (suffix in listOf("Screen", "Destination", "Route", "ViewModel")) {
        if (str.length > suffix.length && str.endsWith(suffix)) {
            str = str.substring(0, str.length - suffix.length)
            break
        }
    }
    return str.camelWords().filter { it.isNotEmpty() }.joinToString("_") { it.lowercase() }
}
